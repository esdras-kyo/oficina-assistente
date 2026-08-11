package com.oficina.assistente

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.math.sqrt

// ══════════════════════════════════════════════════════════════════ ENTRADA

/**
 * Captura em 16 kHz mono, formato que o Whisper espera.
 *
 * VOICE_RECOGNITION não é detalhe: liga o cancelamento de eco e a supressão
 * de ruído do próprio aparelho. Numa oficina isso vale mais que trocar de
 * modelo.
 *
 * O VAD embutido detecta quando a pessoa parou de falar, para você não
 * precisar de botão. Limiar por energia RMS — simples e suficiente.
 */
class GravadorAudio(
    private val silencioParaFecharMs: Long = 700,
    private val limiarRms: Float = 0.012f,
    private val maxSegundos: Int = 12
) {
    companion object { const val TAXA = 16_000 }

    private var record: AudioRecord? = null
    @Volatile private var gravando = false

    @SuppressLint("MissingPermission")
    fun gravarAteSilencio(onFimDaFala: (FloatArray) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(
            TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            TAXA,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4
        ).apply { startRecording() }

        gravando = true
        thread(name = "captura-audio") {
            val buf = ShortArray(minBuf)
            val acumulado = ArrayList<Float>(TAXA * 10)
            var ultimoSomMs = System.currentTimeMillis()
            var jaFalou = false

            while (gravando) {
                val n = record?.read(buf, 0, buf.size) ?: 0
                if (n <= 0) continue

                var soma = 0.0
                for (i in 0 until n) {
                    val amostra = buf[i] / 32768.0f
                    acumulado.add(amostra)
                    soma += (amostra * amostra).toDouble()
                }
                val rms = sqrt(soma / n).toFloat()
                val agora = System.currentTimeMillis()
                if (acumulado.size > TAXA * maxSegundos) break

                if (rms > limiarRms) {
                    ultimoSomMs = agora
                    jaFalou = true
                } else if (jaFalou && agora - ultimoSomMs > silencioParaFecharMs) {
                    break   // fim da fala
                }
            }

            parar()
            if (jaFalou) onFimDaFala(acumulado.toFloatArray())
        }
    }

    fun parar() {
        gravando = false
        runCatching { record?.stop() }
        record?.release()
        record = null
    }
}

// ══════════════════════════════════════════════════════════════════ SAÍDA

/**
 * TTS com duas coisas que mudam a percepção de velocidade:
 *
 * 1. bipeConfirmacao() — 80 ms, dispara ~100 ms depois do fim da fala.
 *    Não acelera nada, mas leva a tolerância do usuário de ~1 s para ~3 s.
 *
 * 2. enfileirar() por FRASE — você chama assim que o LLM fecha a primeira
 *    frase, sem esperar ele terminar. Enquanto a pessoa ouve a frase 1
 *    (~1,5 s falada), o modelo já gerou a frase 2. A costura não aparece.
 */
class VozStreaming(context: Context) : Voz {

    private var pronto = false
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)

    private val tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            pronto = true
        }
    }.apply {
        setLanguage(Locale("pt", "BR"))
        setSpeechRate(1.05f)     // levemente acelerado: fala de instrução
    }

    private val terminou = Channel<String>(Channel.UNLIMITED)

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { terminou.trySend(it) }
            }
            @Deprecated("compat") override fun onError(utteranceId: String?) {
                utteranceId?.let { terminou.trySend(it) }
            }
        })
    }

    override fun bipeConfirmacao() {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    override suspend fun enfileirar(frase: String) {
        if (!pronto || frase.isBlank()) return
        val id = UUID.randomUUID().toString()
        tts.speak(frase, TextToSpeech.QUEUE_ADD, null, id)
        // não bloqueia: quem quiser esperar chama aguardarFim()
    }

    /** só use quando precisar sincronizar com algo depois da fala */
    suspend fun aguardarFim() = suspendCancellableCoroutine<Unit> { cont ->
        thread {
            while (tts.isSpeaking) Thread.sleep(50)
            cont.resume(Unit)
        }
    }

    override fun interromper() { tts.stop() }

    override fun liberar() {
        tts.stop(); tts.shutdown(); tone.release()
    }
}

/**
 * Recorta um fluxo de tokens em frases completas.
 *
 * O LLM cospe "É a luz de" · " pressão do óleo." · " Pare o carro" · "..."
 * Este acumulador devolve "É a luz de pressão do óleo." assim que o ponto
 * chega — e é aí que você manda pro TTS.
 */
class AcumuladorDeFrases(private val minChars: Int = 12) {
    private val buffer = StringBuilder()
    private val fim = charArrayOf('.', '!', '?', '\n')

    /** alimenta um pedaço; devolve as frases fechadas (0, 1 ou mais) */
    fun alimentar(pedaco: String): List<String> {
        buffer.append(pedaco)
        val prontas = mutableListOf<String>()
        var corte: Int
        while (true) {
            corte = buffer.indexOfFirst { it in fim }
            if (corte < 0 || corte + 1 < minChars) break
            val frase = buffer.substring(0, corte + 1).trim()
            buffer.delete(0, corte + 1)
            if (frase.isNotEmpty()) prontas.add(frase)
        }
        return prontas
    }

    /** o que sobrou sem pontuação no fim do stream */
    fun drenar(): String? =
        buffer.toString().trim().ifBlank { null }.also { buffer.clear() }

    private fun StringBuilder.indexOfFirst(p: (Char) -> Boolean): Int {
        for (i in indices) if (p(this[i])) return i
        return -1
    }
}
