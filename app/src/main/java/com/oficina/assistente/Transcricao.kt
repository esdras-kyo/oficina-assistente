package com.oficina.assistente

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer

/**
 * TRANSCRIÇÃO EM DUAS CAMADAS
 *
 * Camada 1 — viés por prompt.  O Whisper aceita um initialPrompt que ele lê
 *            como contexto anterior. Colocando o vocabulário de oficina ali,
 *            ele passa a PREFERIR essas palavras na decodificação.
 *            Custo: zero treino. É a intervenção de melhor retorno do projeto.
 *
 * Camada 2 — pós-correção por léxico.  O que escapar, você conserta depois.
 *            Código comum, sem modelo. É onde mora o ganho real em jargão.
 *
 * Camada 3 — fine-tuning do Whisper.  Só depois de MEDIR que 1 e 2 não bastam.
 */

// ─────────────────────────────────────────────────────── camada 1

class TranscritorWhisper(
    context: Context,
    private val nomeModelo: String = "ggml-small-q5_1.bin",
    private val vocabulario: String = VOCABULARIO_OFICINA
) : Transcritor {

    // Substitua pelo binding do módulo examples/whisper.android do whisper.cpp.
    // Não escreva JNI do zero — copie o módulo pronto do repositório.
    private val ctx: WhisperContext =
        WhisperContext.createContextFromAsset(context.assets, nomeModelo)

    override suspend fun transcrever(audio: FloatArray): String =
        withContext(Dispatchers.Default) {
            ctx.transcribeData(
                audio,
                language      = "pt",
                initialPrompt = vocabulario,   // ← camada 1
                temperature   = 0.0f,          // determinístico
                noContext     = true,          // não arrasta contexto entre falas
                singleSegment = true
            ).trim()
        }

    override fun liberar() = ctx.release()

    companion object {
        /**
         * Mantenha CURTO. Cada token aqui é prefill em toda transcrição.
         * ~60 palavras é o ponto de equilíbrio. Priorize o que ele erra mais.
         */
        const val VOCABULARIO_OFICINA = """
Manutenção automotiva: cárter, bujão, vareta de óleo, filtro de óleo,
filtro de ar, correia dentada, coxim, sonda lambda, vela de ignição,
reservatório de expansão, radiador, torquímetro, macaco hidráulico,
triângulo, calço, terminal da bateria, 5W-30, 15W-40, luz da injeção.
"""
    }
}

// ─────────────────────────────────────────────────────── camada 2

/**
 * Corretor de jargão. Carrega lexico.json.
 *
 * ATENÇÃO AO LIMIAR: corrigir demais é MUITO pior que corrigir de menos.
 * Se ele transformar "carro" em "cárter", você quebrou o app.
 * Calibre com o seu conjunto de 30 frases gravadas.
 */
class CorretorJargao(json: String) {

    private data class Expressao(val regex: Regex, val substituto: String)

    private val expressoes: List<Expressao>
    private val lexico: List<String>
    private val lexicoNorm: List<String>

    init {
        val raiz = JSONObject(json)

        val arrExpr = raiz.getJSONArray("expressoes")
        expressoes = (0 until arrExpr.length()).map { i ->
            val o = arrExpr.getJSONObject(i)
            Expressao(
                Regex(o.getString("padrao"), RegexOption.IGNORE_CASE),
                o.getString("substituto")
            )
        }

        val arrTermos = raiz.getJSONArray("termos")
        lexico = (0 until arrTermos.length()).map { arrTermos.getString(it) }
        lexicoNorm = lexico.map(::normalizar)
    }

    data class Resultado(val texto: String, val correcoes: Int)

    fun corrigir(entrada: String): Resultado {
        var t = entrada
        var n = 0

        // 1. expressões inteiras primeiro (multi-palavra)
        expressoes.forEach { e ->
            val antes = t
            t = e.regex.replace(t, e.substituto)
            if (t != antes) n++
        }

        // 2. termos isolados, por proximidade
        val palavras = t.split(" ").map { p ->
            if (p.length < 4) return@map p

            val alvo = normalizar(p)
            if (alvo.isEmpty()) return@map p
            if (alvo in lexicoNorm) return@map p   // já está certo

            var melhorIdx = -1
            var melhorD = Int.MAX_VALUE
            lexicoNorm.forEachIndexed { i, cand ->
                // termos multi-palavra não entram na correção palavra a palavra
                if (' ' in lexico[i]) return@forEachIndexed
                val d = levenshtein(alvo, cand, melhorD)
                if (d < melhorD) { melhorD = d; melhorIdx = i }
            }

            // limiar proporcional: palavra curta tolera 1 erro, longa tolera mais
            val limite = maxOf(1, alvo.length / 4)
            if (melhorIdx >= 0 && melhorD <= limite) {
                n++
                lexico[melhorIdx]
            } else p
        }

        return Resultado(palavras.joinToString(" "), n)
    }

    /** minúsculas, sem acento, só letras e números */
    private fun normalizar(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]"), "")

    /**
     * Levenshtein com poda: aborta cedo se já passou do melhor conhecido.
     * Em 150 termos por palavra, isso importa.
     */
    private fun levenshtein(a: String, b: String, teto: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > teto) return Int.MAX_VALUE
        var anterior = IntArray(b.length + 1) { it }
        var atual = IntArray(b.length + 1)

        for (i in 1..a.length) {
            atual[0] = i
            var minLinha = atual[0]
            for (j in 1..b.length) {
                val custo = if (a[i - 1] == b[j - 1]) 0 else 1
                atual[j] = minOf(
                    atual[j - 1] + 1,
                    anterior[j] + 1,
                    anterior[j - 1] + custo
                )
                if (atual[j] < minLinha) minLinha = atual[j]
            }
            if (minLinha > teto) return Int.MAX_VALUE
            val tmp = anterior; anterior = atual; atual = tmp
        }
        return anterior[b.length]
    }
}
