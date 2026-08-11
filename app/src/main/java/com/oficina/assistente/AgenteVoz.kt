package com.oficina.assistente

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * O AGENTE
 *
 * Nenhuma linha aqui é "inteligência artificial". É ORQUESTRAÇÃO — a
 * arquitetura híbrida em camadas da Unidade VII, em Kotlin.
 *
 * Três coisas que valem reparar:
 *   1. A camada de segurança não chama modelo nenhum.
 *   2. O estado do procedimento não mora em modelo nenhum.
 *   3. A fala começa ANTES de o LLM terminar.
 */
class AgenteVoz(
    private val transcritor: Transcritor,
    private val corretor: CorretorJargao,
    private val roteador: Roteador,
    private val estado: EstadoSessao,
    private val voz: VozStreaming,
    private val embedder: Embedder,
    private val conteudo: BaseConteudo,
    private val llm: ModeloLinguagem?,          // pode ser null no estágio 3
    private val visao: ClassificadorVisual?,    // pode ser null no estágio 3
    private val capturarFrame: (suspend () -> ByteArray)? = null,
    private val escopo: CoroutineScope,
    private val onTelemetria: (Telemetria) -> Unit = {}
) {

    data class Telemetria(
        val bruto: String,
        val corrigido: String,
        val correcoes: Int,
        val intencao: Intencao?,
        val confianca: Float,
        val caminho: Caminho,
        val msTranscricao: Long,
        val msRoteamento: Long,
        val msAtePrimeiroSom: Long
    )

    fun aoTerminarFala(audio: FloatArray) = escopo.launch {
        val t0 = System.currentTimeMillis()

        // ── bipe em ~100 ms. Não acelera nada; muda tudo na percepção.
        voz.bipeConfirmacao()

        // ── 1. PERCEPÇÃO
        val bruto = transcritor.transcrever(audio)
        val tTranscricao = System.currentTimeMillis()
        if (bruto.isBlank()) return@launch

        // ── 2. CORREÇÃO DE JARGÃO
        val corr = corretor.corrigir(bruto)

        // ── 3. VISÃO EM PARALELO — não espera para descobrir se vai precisar.
        //     Se a pergunta for dêitica, o resultado já estará pronto.
        val visaoAsync = if (visao != null && capturarFrame != null && ehDeitica(corr.texto))
            async(Dispatchers.Default) { visao.identificar(capturarFrame.invoke()) }
        else null

        // ── 4. ROTEAMENTO
        val d = roteador.rotear(corr.texto)
        val tRoteamento = System.currentTimeMillis()

        var msPrimeiroSom: Long

        when (d.caminho) {

            // ── CAMINHOS RÁPIDOS: resposta pronta, sem LLM. ~150–350 ms.
            Caminho.REATIVO, Caminho.RAPIDO, Caminho.DESCONHECIDO -> {
                visaoAsync?.cancel()
                d.respostaPronta?.let { voz.enfileirar(it) }
                msPrimeiroSom = System.currentTimeMillis() - t0
                estado.aplicar(d.acao)
            }

            // ── CAMINHO DELIBERADO: recupera contexto e transmite a resposta.
            Caminho.DELIBERADO -> {
                val visto = visaoAsync?.await()

                val consulta = listOfNotNull(corr.texto, visto?.rotulo)
                    .joinToString(" ")
                val trechos = conteudo.buscar(
                    embedder.gerar(consulta),
                    filtroProc = estado.procedimento,
                    k = 3
                )

                if (llm == null) {
                    // estágio 3: ainda sem LLM — devolve o trecho mais próximo
                    val fala = trechos.firstOrNull()?.texto
                        ?: "Ainda não sei responder isso."
                    voz.enfileirar(fala)
                    msPrimeiroSom = System.currentTimeMillis() - t0
                } else {
                    msPrimeiroSom = transmitirResposta(
                        montarPrompt(corr.texto, visto, trechos), t0
                    )
                }
            }
        }

        onTelemetria(
            Telemetria(
                bruto            = bruto,
                corrigido        = corr.texto,
                correcoes        = corr.correcoes,
                intencao         = d.intencao,
                confianca        = d.confianca,
                caminho          = d.caminho,
                msTranscricao    = tTranscricao - t0,
                msRoteamento     = tRoteamento - tTranscricao,
                msAtePrimeiroSom = msPrimeiroSom
            )
        )
    }

    /**
     * O CORAÇÃO DA LATÊNCIA PERCEBIDA.
     *
     * Não espera o LLM terminar. Assim que a primeira frase fecha, ela já vai
     * pro TTS. Enquanto a pessoa ouve a frase 1 (~1,5 s falada), o modelo já
     * gerou a frase 2. A costura não aparece.
     *
     * Retorna o tempo até o primeiro som.
     */
    private suspend fun transmitirResposta(prompt: String, t0: Long): Long {
        val acumulador = AcumuladorDeFrases()
        var primeiroSom = -1L

        llm!!.gerarEmFluxo(prompt, maxTokens = 80).collect { pedaco ->
            acumulador.alimentar(pedaco).forEach { frase ->
                if (primeiroSom < 0) primeiroSom = System.currentTimeMillis() - t0
                voz.enfileirar(frase)
            }
        }
        acumulador.drenar()?.let { resto ->
            if (primeiroSom < 0) primeiroSom = System.currentTimeMillis() - t0
            voz.enfileirar(resto)
        }
        return if (primeiroSom < 0) System.currentTimeMillis() - t0 else primeiroSom
    }

    /** pergunta dêitica = aponta para algo no mundo, precisa da câmera */
    private fun ehDeitica(t: String): Boolean {
        val l = t.lowercase()
        return listOf("isso", "isto", "essa", "esse", "aqui", "aquilo", "este")
            .any { Regex("\\b$it\\b").containsMatchIn(l) }
    }

    private fun montarPrompt(
        pergunta: String, visto: Deteccao?, trechos: List<Chunk>
    ) = """
        Você é um assistente de mecânica básica. Responda em no máximo
        2 frases curtas, para serem OUVIDAS, não lidas. Nunca invente
        passos que não estejam no CONTEXTO. Português do Brasil.

        ESTADO: ${estado.resumo()}
        VISÃO: ${visto?.let { "${it.rotulo} (${"%.2f".format(it.confianca)})" } ?: "—"}
        CONTEXTO:
        ${trechos.joinToString("\n") { "- ${it.texto}" }}
        PERGUNTA: $pergunta
    """.trimIndent()

    fun liberar() {
        transcritor.liberar(); embedder.liberar()
        llm?.liberar(); visao?.liberar(); voz.liberar()
    }
}
