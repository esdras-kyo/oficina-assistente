package com.oficina.assistente

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var transcritor: TranscritorWhisper
    private lateinit var corretor: CorretorJargao
    private val gravador = GravadorAudio()

    private var estado by mutableStateOf("carregando modelo…")
    private var bruto by mutableStateOf("")
    private var corrigido by mutableStateOf("")
    private var correcoes by mutableStateOf(0)
    private var msWhisper by mutableStateOf(0L)
    private var gravando by mutableStateOf(false)

    private val pedirPermissao = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> if (!ok) estado = "permissão de microfone negada" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) pedirPermissao.launch(Manifest.permission.RECORD_AUDIO)

        lifecycleScope.launch {
            runCatching {
                transcritor = TranscritorWhisper(this@MainActivity)
                corretor = CorretorJargao(
                    assets.open("lexico.json").bufferedReader().use { it.readText() }
                )
            }.onSuccess { estado = "pronto" }
                .onFailure { estado = "erro ao carregar: ${it.message}" }
        }

        setContent { Tela() }
    }

    @Composable
    private fun Tela() = MaterialTheme {
        Surface {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text("Assistente de oficina", fontSize = 22.sp)
                Text(estado, fontSize = 13.sp)

                Button(
                    onClick = { alternarGravacao() },
                    enabled = estado == "pronto" || gravando,
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) { Text(if (gravando) "gravando… toque para parar" else "🎙 falar") }

                HorizontalDivider()

                Campo("bruto", bruto)
                Campo("corrigido", "$corrigido   ($correcoes correções)")
                Campo("latência", "whisper ${msWhisper}ms")
            }
        }
    }

    @Composable
    private fun Campo(rotulo: String, valor: String) = Column {
        Text(rotulo, fontSize = 11.sp)
        Text(valor.ifBlank { "—" }, fontSize = 17.sp)
    }

    private fun alternarGravacao() {
        if (gravando) { gravador.parar(); gravando = false; return }

        gravando = true
        estado = "ouvindo…"
        gravador.gravarAteSilencio { audio ->
            android.util.Log.d(
                "OFICINA",
                "audio recebido: ${audio.size} amostras (${audio.size / 16000f}s)"
            )
            lifecycleScope.launch {
                gravando = false
                estado = "transcrevendo…"
                val t0 = System.currentTimeMillis()
                runCatching { transcritor.transcrever(audio) }
                    .onSuccess {
                        android.util.Log.d("OFICINA", "transcrito: '$it'")
                        bruto = it
                    }
                    .onFailure {
                        android.util.Log.e("OFICINA", "falhou", it)
                        estado = "erro: ${it.message}"
                    }
                msWhisper = System.currentTimeMillis() - t0
                val r = corretor.corrigir(bruto)
                corrigido = r.texto
                correcoes = r.correcoes
                if (estado == "transcrevendo…") estado = "pronto"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::transcritor.isInitialized) transcritor.liberar()
    }
}