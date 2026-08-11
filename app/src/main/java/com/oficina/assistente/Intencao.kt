package com.oficina.assistente

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * CLASSIFICADOR DE INTENÇÃO POR EMBEDDING
 *
 * Sem LLM. Sem banco vetorial. Sem HNSW.
 *
 * Você embeda a frase, compara por cosseno com ~100 exemplos, pega o vizinho
 * mais próximo. Com 100 vetores, varredura linear roda em microssegundos —
 * exatamente o que a Unidade X diz sobre coleções pequenas.
 *
 * Estas 20 linhas SÃO a busca vetorial. Quando você trocar os exemplos de
 * intenção pelos chunks do manual, é literalmente o mesmo código.
 */
class ClassificadorIntencao(
    private val embedder: Embedder,
    private val exemplos: List<ExemploIntencao>,
    private val limiar: Float = 0.55f
) {
    init {
        require(exemplos.all { it.vetor != null }) {
            "Exemplos sem vetor. Rode python/gerar_embeddings.py antes."
        }
        require(exemplos.all { it.vetor!!.size == embedder.dimensao }) {
            "Dimensão divergente: o Python usou um modelo de embedding " +
            "diferente do Kotlin. Os dois TÊM que usar o mesmo."
        }
    }

    fun classificar(frase: String): ResultadoIntencao {
        if (frase.isBlank()) return ResultadoIntencao(null, 0f)

        val v = embedder.gerar(frase)
        var melhor: Intencao? = null
        var melhorScore = -1f

        exemplos.forEach { ex ->
            val s = cosseno(v, ex.vetor!!)
            if (s > melhorScore) { melhorScore = s; melhor = ex.intencao }
        }

        // abaixo do limiar = fora de escopo. Admitir é melhor que chutar.
        return if (melhorScore >= limiar) ResultadoIntencao(melhor, melhorScore)
               else ResultadoIntencao(null, melhorScore)
    }

    companion object {
        fun cosseno(a: FloatArray, b: FloatArray): Float {
            var dot = 0f; var na = 0f; var nb = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
            }
            return dot / (sqrt(na) * sqrt(nb) + 1e-8f)
        }

        fun carregar(json: String): List<ExemploIntencao> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val vet = o.optJSONArray("vetor")?.let { a ->
                    FloatArray(a.length()) { k -> a.getDouble(k).toFloat() }
                }
                ExemploIntencao(
                    intencao = Intencao.valueOf(o.getString("intencao")),
                    texto    = o.getString("texto"),
                    vetor    = vet
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════ O ROTEADOR

/**
 * A PEÇA QUE RESOLVE A LATÊNCIA.
 *
 * Num procedimento guiado, a maior parte do que a pessoa fala é NAVEGAÇÃO,
 * não raciocínio: "e agora?", "próximo", "repete", "feito". A resposta já
 * está escrita no chunk. O LLM nem precisa acordar.
 *
 *   REATIVO    ~150 ms   regra fixa, zero modelo
 *   RAPIDO     ~350 ms   intenção → chunk → TTS
 *   DELIBERADO ~1,3 s    pipeline completo com LLM
 *
 * Se ~60% dos turnos caem no caminho rápido, o app É rápido — mesmo com um
 * LLM lento no fundo.
 */
class Roteador(
    private val classificador: ClassificadorIntencao,
    private val conteudo: BaseConteudo,
    private val estado: EstadoSessao
) {

    /** palavras que NUNCA passam por modelo. Segurança não é probabilística. */
    private val gatilhosEmergencia = listOf(
        "fumaça", "fumaca", "fogo", "queimado", "cheiro de queimado",
        "vazando muito", "socorro", "para tudo", "parar tudo"
    )

    data class Decisao(
        val caminho: Caminho,
        val intencao: Intencao?,
        val confianca: Float,
        val respostaPronta: String? = null,   // preenchida nos caminhos rápidos
        val acao: Acao = Acao.NADA
    )

    fun rotear(fraseCorrigida: String): Decisao {
        val t = fraseCorrigida.lowercase()

        // ── 1. CAMADA REATIVA — antes de qualquer modelo
        if (gatilhosEmergencia.any { it in t }) {
            return Decisao(
                caminho = Caminho.REATIVO,
                intencao = Intencao.EMERGENCIA,
                confianca = 1f,
                respostaPronta = "Pare o carro em local seguro e desligue o " +
                                 "motor. Não abra o capô agora.",
                acao = Acao.ALERTA
            )
        }

        // interlock de pré-requisito do passo atual
        estado.chunkAtual()?.preRequisito?.let { req ->
            if (req !in estado.confirmados) {
                return Decisao(
                    caminho = Caminho.REATIVO,
                    intencao = null,
                    confianca = 1f,
                    respostaPronta = mensagemPreRequisito(req),
                    acao = Acao.AGUARDAR
                )
            }
        }

        // ── 2. INTENÇÃO
        val r = classificador.classificar(fraseCorrigida)
            ?: return Decisao(Caminho.DESCONHECIDO, null, 0f)

        if (r.intencao == null) {
            return Decisao(
                caminho = Caminho.DESCONHECIDO,
                intencao = null,
                confianca = r.confianca,
                respostaPronta = "Não entendi. Pode repetir?"
            )
        }

        // ── 3. CAMINHO RÁPIDO — resposta já existe, sem LLM
        return when (r.intencao) {
            Intencao.PROXIMO_PASSO -> rapido(
                conteudo.passo(estado.procedimento, estado.passo + 1)?.texto
                    ?: "Esse foi o último passo. Procedimento concluído.",
                r, Acao.AVANCAR
            )

            Intencao.REPETIR -> rapido(
                estado.chunkAtual()?.texto ?: "Não temos um passo em andamento.",
                r, Acao.REPETIR
            )

            Intencao.PASSO_ANTERIOR -> rapido(
                conteudo.passo(estado.procedimento, estado.passo - 1)?.texto
                    ?: "Já estamos no primeiro passo.",
                r, Acao.VOLTAR
            )

            Intencao.QUAL_FERRAMENTA -> rapido(
                estado.chunkAtual()?.ferramenta
                    ?.let { "Use $it." }
                    ?: "Esse passo não pede ferramenta específica.",
                r, Acao.NADA
            )

            Intencao.ENCERRAR -> rapido(
                "Certo, encerrando o procedimento.", r, Acao.ENCERRAR
            )

            // ── 4. CAMINHO DELIBERADO — precisa pensar
            Intencao.IDENTIFICAR_LUZ,
            Intencao.IDENTIFICAR_PECA,
            Intencao.DESCREVER_SINTOMA,
            Intencao.INICIAR_PROCEDIMENTO ->
                Decisao(Caminho.DELIBERADO, r.intencao, r.confianca)

            Intencao.EMERGENCIA ->
                Decisao(Caminho.REATIVO, r.intencao, r.confianca,
                        "Pare o carro em local seguro e desligue o motor.",
                        Acao.ALERTA)
        }
    }

    private fun rapido(texto: String, r: ResultadoIntencao, acao: Acao) =
        Decisao(Caminho.RAPIDO, r.intencao, r.confianca, texto, acao)

    private fun mensagemPreRequisito(req: String) = when (req) {
        "motor_desligado" -> "Desligue o motor antes de continuar."
        "motor_frio"      -> "Espere o motor esfriar. O óleo quente queima."
        "freio_mao"       -> "Puxe o freio de mão antes de levantar o carro."
        "calco_rodas"     -> "Calce as rodas antes de continuar."
        else              -> "Confirme o passo anterior antes de seguir."
    }
}

// ═════════════════════════════════════════════════════════════ ESTADO

/**
 * O estado interno da Unidade VI. Nenhum modelo guarda isso — é variável sua.
 */
class EstadoSessao(private val conteudo: BaseConteudo) {
    var procedimento: String? = null; private set
    var passo: Int = 0; private set
    val confirmados = mutableSetOf<String>()

    fun chunkAtual(): Chunk? = conteudo.passo(procedimento, passo)

    fun iniciar(proc: String) {
        procedimento = proc; passo = 1; confirmados.clear()
    }

    fun aplicar(acao: Acao, visto: Deteccao? = null) {
        when (acao) {
            Acao.AVANCAR -> {
                visto?.let { confirmados.add(it.rotulo) }
                chunkAtual()?.verificacaoVisual?.let { confirmados.add(it) }
                if (conteudo.passo(procedimento, passo + 1) != null) passo++
            }
            Acao.VOLTAR   -> if (passo > 1) passo--
            Acao.ENCERRAR -> { procedimento = null; passo = 0; confirmados.clear() }
            else -> Unit   // REPETIR, AGUARDAR, ALERTA e NADA não mexem no passo
        }
    }

    fun resumo() = "proc=${procedimento ?: "nenhum"} passo=$passo/" +
                   "${chunkAtual()?.totalPassos ?: 0}"
}

// ═════════════════════════════════════════════════════════════ CONTEÚDO

class BaseConteudo(private val chunks: List<Chunk>) {

    fun passo(procedimento: String?, n: Int): Chunk? =
        chunks.firstOrNull { it.procedimento == procedimento && it.passo == n }

    /** busca semântica — o mesmo cosseno, agora sobre os chunks */
    fun buscar(vetorConsulta: FloatArray, filtroProc: String?, k: Int = 3): List<Chunk> =
        chunks
            .filter { it.vetor != null }
            .filter { filtroProc == null || it.procedimento == filtroProc }
            .map { it to ClassificadorIntencao.cosseno(vetorConsulta, it.vetor!!) }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }

    companion object {
        fun carregar(json: String): BaseConteudo {
            val arr = JSONArray(json)
            val lista = (0 until arr.length()).map { i ->
                val o: JSONObject = arr.getJSONObject(i)
                val vet = o.optJSONArray("vetor")?.let { a ->
                    FloatArray(a.length()) { k -> a.getDouble(k).toFloat() }
                }
                Chunk(
                    id                = o.getString("id"),
                    texto             = o.getString("texto"),
                    procedimento      = o.getString("procedimento"),
                    passo             = o.getInt("passo"),
                    totalPassos       = o.getInt("total_passos"),
                    ferramenta        = o.optString("ferramenta").ifBlank { null },
                    risco             = o.optString("risco").ifBlank { null },
                    verificacaoVisual = o.optString("verificacao_visual").ifBlank { null },
                    preRequisito      = o.optString("pre_requisito").ifBlank { null },
                    vetor             = vet
                )
            }
            return BaseConteudo(lista)
        }
    }
}
