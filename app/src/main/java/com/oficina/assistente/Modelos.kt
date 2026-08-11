package com.oficina.assistente

/**
 * CONTRATOS
 *
 * Tudo que é modelo vira interface. O agente nunca sabe qual runtime está
 * por baixo — isso te deixa trocar whisper.cpp por Vosk, ou o LLM por um
 * mock, sem mexer no orquestrador.
 *
 * Regra: se é interface aqui, é um ARQUIVO de modelo lá fora.
 *        Se é data class, é dado seu.
 */

// ---------------------------------------------------------------- intenções

enum class Intencao {
    PROXIMO_PASSO,        // "e agora?", "próximo", "feito"
    REPETIR,              // "de novo", "não entendi"
    PASSO_ANTERIOR,       // "volta um"
    INICIAR_PROCEDIMENTO, // "quero trocar o óleo"
    QUAL_FERRAMENTA,      // "que chave eu uso?"
    IDENTIFICAR_LUZ,      // "que luz é essa?"
    IDENTIFICAR_PECA,     // "o que é isso aqui?"
    DESCREVER_SINTOMA,    // "tá fazendo um barulho estranho"
    EMERGENCIA,           // "tá saindo fumaça"
    ENCERRAR              // "pode parar", "terminei"
}

/**
 * Caminho que a fala vai seguir. Definido pelo Roteador.
 *
 * RAPIDO      → resposta já existe no chunk. ~350 ms. Não chama LLM.
 * REATIVO     → regra fixa de segurança. ~150 ms. Não chama modelo nenhum.
 * DELIBERADO  → precisa de raciocínio. ~1,3 s. Chama o LLM.
 * DESCONHECIDO→ confiança baixa. Pede para repetir.
 */
enum class Caminho { REATIVO, RAPIDO, DELIBERADO, DESCONHECIDO }

data class ExemploIntencao(
    val intencao: Intencao,
    val texto: String,
    val vetor: FloatArray? = null   // preenchido pelo gerar_embeddings.py
)

data class ResultadoIntencao(
    val intencao: Intencao?,
    val confianca: Float
)

// ---------------------------------------------------------------- conteúdo

/**
 * Um chunk = um passo de procedimento. Este é o esquema que o Python
 * exporta e o Kotlin consome. Fonte da verdade: a planilha.
 */
data class Chunk(
    val id: String,
    val texto: String,
    val procedimento: String,
    val passo: Int,
    val totalPassos: Int,
    val ferramenta: String? = null,
    val risco: String? = null,
    val verificacaoVisual: String? = null,
    val preRequisito: String? = null,   // ex: "motor_desligado"
    val vetor: FloatArray? = null
)

// ---------------------------------------------------------------- respostas

data class RespostaAgente(
    val fala: String,
    val acao: Acao,
    val confianca: Float,
    val caminho: Caminho
)

enum class Acao { AVANCAR, REPETIR, VOLTAR, AGUARDAR, ALERTA, ENCERRAR, NADA }

// ---------------------------------------------------------------- modelos

interface Transcritor {
    /** audio em 16 kHz mono float normalizado → texto */
    suspend fun transcrever(audio: FloatArray): String
    fun liberar()
}

interface Embedder {
    /** texto → vetor de significado. MESMO modelo usado no Python. */
    fun gerar(texto: String): FloatArray
    val dimensao: Int
    fun liberar()
}

interface ClassificadorVisual {
    fun identificar(frameJpeg: ByteArray): Deteccao?
    fun liberar()
}

data class Deteccao(val rotulo: String, val confianca: Float)

/**
 * O LLM devolve um FLUXO de pedaços de texto, não uma string pronta.
 * É isso que permite começar a falar antes de ele terminar.
 */
interface ModeloLinguagem {
    fun gerarEmFluxo(prompt: String, maxTokens: Int = 80): kotlinx.coroutines.flow.Flow<String>
    fun liberar()
}

interface Voz {
    /** enfileira uma frase; toca em ordem, sem cortar a anterior */
    suspend fun enfileirar(frase: String)
    /** bipe curto de confirmação — a otimização de maior retorno do app */
    fun bipeConfirmacao()
    fun interromper()
    fun liberar()
}
