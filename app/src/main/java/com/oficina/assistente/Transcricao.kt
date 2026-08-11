package com.oficina.assistente

import android.content.Context
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.text.Normalizer

class TranscritorWhisper(
    context: Context,
    nomeModelo: String = "ggml-small-q5_1.bin"
) : Transcritor {

    private val ctx: WhisperContext =
        WhisperContext.createContextFromAsset(context.assets, nomeModelo)

    override suspend fun transcrever(audio: FloatArray): String =
        ctx.transcribeData(audio, printTimestamp = false).trim()

    override fun liberar() = runBlocking { ctx.release() }
}

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

        expressoes.forEach { e ->
            val antes = t
            t = e.regex.replace(t, e.substituto)
            if (t != antes) n++
        }

        val palavras = t.split(" ").map { p ->
            if (p.length < 4) return@map p

            val alvo = normalizar(p)
            if (alvo.isEmpty()) return@map p
            if (alvo in lexicoNorm) return@map p

            var melhorIdx = -1
            var melhorD = Int.MAX_VALUE
            lexicoNorm.forEachIndexed { i, cand ->
                if (' ' in lexico[i]) return@forEachIndexed
                val d = levenshtein(alvo, cand, melhorD)
                if (d < melhorD) { melhorD = d; melhorIdx = i }
            }

            val limite = maxOf(1, alvo.length / 4)
            if (melhorIdx >= 0 && melhorD <= limite) {
                n++
                lexico[melhorIdx]
            } else p
        }

        return Resultado(palavras.joinToString(" "), n)
    }

    private fun normalizar(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]"), "")

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