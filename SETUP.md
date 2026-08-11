# Setup do ambiente

Este projeto depende do **whisper.cpp**, que **não está versionado aqui**. Ele
mora fora do repositório e precisa ser clonado e ajustado à mão em cada máquina.

Sem seguir este documento, o projeto **não sincroniza**. o Gradle vai falhar
logo no início, antes mesmo de compilar qualquer coisa.

---

## Índice

1. [Pré-requisitos](#1-pré-requisitos)
2. [Clonar o whisper.cpp](#2-clonar-o-whispercpp)
3. [Descobrir sua versão do NDK](#3-descobrir-sua-versão-do-ndk)
4. [Editar o build.gradle do whisper](#4-editar-o-buildgradle-do-whisper)
5. [Apontar o projeto para o clone](#5-apontar-o-projeto-para-o-clone)
6. [Baixar o modelo](#6-baixar-o-modelo)
7. [Verificação final](#7-verificação-final)
8. [Problemas conhecidos](#8-problemas-conhecidos)

---

## 1. Pré-requisitos

No **Android Studio**, abra `Tools → SDK Manager → SDK Tools` e confirme que
estão instalados (caixa marcada, não apenas listados):

- [ ] **NDK (Side by side)**
- [ ] **CMake**

O NDK ocupa cerca de 2 GB. Sem ele, o código C++ do whisper não compila.

No **aparelho Android** de teste:

- [ ] Opções de desenvolvedor ativadas
  (`Configurações → Sobre o telefone → tocar 7× em "Número da versão"`)
- [ ] **Depuração USB** ligada

---

## 2. Clonar o whisper.cpp

O clone precisa ficar **fora** da pasta do projeto. Este documento assume que
ele fica direto na home (`~`).

```bash
cd ~
git clone https://github.com/ggml-org/whisper.cpp.git
```

Confirme que o módulo Android veio junto:

```bash
ls ~/whisper.cpp/examples/whisper.android/lib/src/main/
```

Deve listar três itens: `AndroidManifest.xml`, `java` e `jni`.

> **Por que não copiar a pasta `lib` para dentro do projeto?**
> O `CMakeLists.txt` dentro de `jni/whisper/` referencia o código-fonte do
> whisper por caminhos relativos, subindo até a raiz do repositório. Copiando
> só a pasta `lib`, esses caminhos quebram e a compilação do C++ falha.
> Por isso apontamos o Gradle para o clone, em vez de duplicá-lo.

---

## 3. Descobrir sua versão do NDK

Cada máquina tem uma versão diferente. Liste as suas:

```bash
ls ~/Library/Android/sdk/ndk/          # macOS
ls ~/Android/Sdk/ndk/                  # Linux
```

Anote uma das versões listadas — você vai usá-la no passo 4.
Exemplo de saída:

```
25.1.8937393   26.1.10909125   27.0.12077973   27.1.12297006
```

---

## 4. Editar o build.gradle do whisper

Este é o passo que mais confunde. **O arquivo a editar está no clone, não no
projeto**:

```
~/whisper.cpp/examples/whisper.android/lib/build.gradle
```

Para abrir:

```bash
open -a TextEdit ~/whisper.cpp/examples/whisper.android/lib/build.gradle   # macOS
xdg-open ~/whisper.cpp/examples/whisper.android/lib/build.gradle           # Linux
```

São **três** alterações. Faça as três antes de salvar.

### 4.1 Remover o plugin Kotlin

O módulo foi escrito para o AGP 8. A partir do **AGP 9, o suporte a Kotlin é
embutido**, e aplicar o plugin antigo por cima causa erro fatal.

**Antes:**
```groovy
plugins {
    id 'com.android.library'
    id 'org.jetbrains.kotlin.android'
}
```

**Depois:**
```groovy
plugins {
    id 'com.android.library'
}
```

> Sem isso: `Failed to apply plugin 'org.jetbrains.kotlin.android'.`
> `Cannot add extension with name 'kotlin', as there is an extension already registered with that name.`

### 4.2 Remover o bloco kotlinOptions

Também descontinuado no AGP 9. Fica por volta da linha 50.

**Remova estas três linhas por inteiro:**
```groovy
    kotlinOptions {
        jvmTarget = '1.8'
    }
```

Não substitua por nada. O AGP 9 define o alvo da JVM sozinho.

> Sem isso: `Could not find method kotlinOptions() for arguments [...] on`
> `object of type com.android.build.gradle.internal.dsl.LibraryExtensionImpl`

### 4.3 Ajustar o ndkVersion

Troque pela versão que você anotou no passo 3.

**Antes:**
```groovy
    ndkVersion "25.2.9519653"
```

**Depois** (exemplo. use a **sua** versão):
```groovy
    ndkVersion "26.1.10909125"
```

> Sem isso: `NDK not configured` ou
> `No version of NDK matched the requested version`

### 4.4 Salvar

`Cmd+S` no macOS, `Ctrl+S` no Linux. **Confirme que salvou:**

```bash
grep -n "kotlin\|ndkVersion" ~/whisper.cpp/examples/whisper.android/lib/build.gradle
```

O resultado deve mostrar **apenas** a linha do `ndkVersion` com a sua versão.
Se aparecer `org.jetbrains.kotlin.android` ou `kotlinOptions`, o arquivo não
foi salvo.

---

## 5. Apontar o projeto para o clone

O caminho abaixo contém o nome de usuário e **muda em cada máquina**. Ajuste.

Em `settings.gradle.kts`, no final do arquivo, deve existir:

```kotlin
include(":app")
include(":whisperlib")
project(":whisperlib").projectDir =
    file("/Users/SEU_USUARIO/whisper.cpp/examples/whisper.android/lib")
```

Em `app/build.gradle.kts`, dentro do bloco `dependencies { }`:

```kotlin
implementation(project(":whisperlib"))
```

No `build.gradle.kts` da **raiz**, o bloco `plugins` precisa declarar
`com.android.library`, o módulo do whisper é uma biblioteca, não um app:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("com.android.library") version "9.2.1" apply false
}
```

> A versão `9.2.1` é a do AGP e deve bater com a chave `agp` em
> `gradle/libs.versions.toml`. Se aquela mudar, esta muda junto.
>
> **Não** declare `org.jetbrains.kotlin.android` aqui. Ver seção 4.1.

Agora rode **Sync Now** no Android Studio. Deve concluir sem erro.

---

## 6. Baixar o modelo

O arquivo do modelo **não é versionado** (é grande demais para o Git).

```bash
cd ~/whisper.cpp
sh ./models/download-ggml-model.sh base
```

Copie o resultado para os assets do projeto:

```bash
mkdir -p ~/AndroidStudioProjects/AssistenteOficina/app/src/main/assets
cp ~/whisper.cpp/models/ggml-base.bin \
   ~/AndroidStudioProjects/AssistenteOficina/app/src/main/assets/
```

| Modelo  | Tamanho | Velocidade | Precisão |
|---------|---------|------------|----------|
| `base`  | ~150 MB | rápido     | suficiente para validar o pipeline |
| `small` | ~490 MB | ~3× mais lento | bem melhor com sotaque e jargão |

Comece pelo `base`. Trocar depois é só substituir o arquivo e o nome no
construtor de `TranscritorWhisper`.

> Modelos acima de 20 MB não deveriam ir dentro do APK em produção. o certo
> é baixar na primeira execução. Para desenvolvimento, `assets/` está ok.

---

## 7. Verificação final

Marque cada item:

- [ ] `~/whisper.cpp` existe e tem a pasta `examples/whisper.android/lib`
- [ ] `grep` do passo 4.4 mostra só o `ndkVersion`
- [ ] `settings.gradle.kts` aponta para o caminho correto **da sua máquina**
- [ ] **Sync** conclui sem erro
- [ ] Em `Transcricao.kt`, o import `com.whispercpp.whisper.WhisperContext`
  não está vermelho
- [ ] `app/src/main/assets/ggml-base.bin` existe
- [ ] `AndroidManifest.xml` tem a permissão `RECORD_AUDIO`
- [ ] O app instala e abre no aparelho

---

## 8. Problemas conhecidos

### O import fica vermelho mesmo com o build passando

Índice do editor desatualizado. `File → Invalidate Caches → Invalidate and Restart`.

**Como distinguir de erro real:** se aparecer na janela de *Build* com stack
trace do Gradle, é erro de verdade e reiniciar não resolve. Se for só vermelho
no editor enquanto o build passa, é o índice.

### transcribeData não aceita idioma nem prompt

A assinatura desta versão do módulo é:

```kotlin
suspend fun transcribeData(data: FloatArray, printTimestamp: Boolean = true): String
```

Não existem os parâmetros `language`, `initialPrompt` nem `temperature` — o JNI
não os expõe. Consequências:

- o idioma é detectado automaticamente;
- o **viés por vocabulário** (`initialPrompt`) não está disponível sem
  modificar o `jni.c`;
- por enquanto, a correção de jargão fica toda por conta do `CorretorJargao`.

### Atualizar o whisper.cpp desfaz as edições

Um `git pull` dentro de `~/whisper.cpp` sobrescreve o `build.gradle` e você
precisa refazer o passo 4. Para evitar, fixe o clone numa versão conhecida:

```bash
cd ~/whisper.cpp && git log --oneline -1   # anote o hash e compartilhe com o time
```
