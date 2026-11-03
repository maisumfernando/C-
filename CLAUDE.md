# CLAUDE.md — memória do projeto "Teclado do Fernando Godinho"

Leia este arquivo inteiro antes de mudar qualquer coisa. Ele resume o que existe, por que foi feito
assim e como continuar. O histórico detalhado de cada sessão está em `docs/DIARIO.md`.
Ao terminar uma sessão de trabalho, **atualize este arquivo e o diário**.

## O produto

- Teclado Android (IME) com ditado por voz que vira texto revisado em português impecável.
- Whisper (whisper.cpp) transcreve; um LLM local (llama.cpp) revisa. **Tudo no aparelho.**
- Dono: Fernando Godinho (GitHub `maisumfernando`). Público: brasileiros, divulgado no canal dele no YouTube.
- Nome do app: "Teclado do Fernando Godinho", traduzido em ~75 idiomas mantendo o nome intacto
  (`app/src/main/res/values-*/strings.xml` e `fastlane/metadata/android/*/title.txt`, limite de 30 caracteres).
- Licença: Apache 2.0 (`LICENSE`, `NOTICE`).
- Prioridades do dono, em ordem: **segurança/privacidade**, facilidade e prazer de uso, qualidade do português,
  rodar na maioria dos Androids (inclusive Huawei com EMUI/Android 12), inovação.

## Estado atual

- Versão: veja `version.properties` (1.0.0 na primeira entrega). `versionCode = major*10000 + minor*100 + patch`.
- Pacote Android: `com.fernandogodinho.teclado`. minSdk 26, targetSdk 36, apenas arm64-v8a.
- **Nunca foi testado em um aparelho real** até a sessão 2: compilado, testes unitários e capturas de tela
  (Robolectric) passam, mas desempenho real e qualidade dos modelos no celular não foram medidos.
  Peça ao dono feedback de uso real antes de grandes refatorações.
- O primeiro push ao GitHub foi bloqueado por falta de acesso do app Claude ao repositório
  (ver "Publicação"). Confira `git log origin/<branch>` antes de supor que algo está publicado.

## Estrutura

```
app/src/main/cpp/                 JNI. Um único ggml (do llama.cpp) atende whisper.cpp e llama.cpp
  CMakeLists.txt                  ggml com variantes de CPU ARM (armv8.0…armv9.2+SME) escolhidas em runtime,
                                  KleidiAI, OpenMP, flags de hardening. Biblioteca: libteclado.so
  jni_whisper.cpp                 transcrição + VAD Silero em streaming (entradas validadas)
  jni_llm.cpp                     geração com cache de prompt: LCP no KV cache + snapshot do prefixo
app/src/main/java/com/fernandogodinho/teclado/
  TecladoApp.kt                   singletons (settings, models, engines, updates, learnedWords)
  core/                           Settings (DataStore), SecureHttp (ÚNICA porta de rede), ProjectInfo,
                                  DeviceProfile (RAM, núcleos grandes), OemTips (Huawei etc.), VocabularyStore
  engine/                         EngineManager (carrega/descarrega modelos), SpeechEngine, TextEngine,
                                  Prompts (instruções + exemplos de revisão por estilo), TextPolisher,
                                  TextTools/WritingTool (ferramentas de IA), SpokenText (códigos, senhas,
                                  e-mails), TranscriptCleaner (alucinações, limpeza, saneamento)
  dictation/DictationSession.kt   gravação 16 kHz, VAD, cortes nas pausas, transcrição incremental,
                                  modos TEXT/CODE/CODE_ALNUM/SECRET/ADDRESS/INSTRUCTION
  ime/                            TecladoIme (InputMethodService + Compose), KeyboardController (toda a
                                  edição de texto), layouts, toque multi-dedo, sugestões (suggest/Dictionary)
  ime/ui/                         KeyboardRoot, KeyGrid, DictationPanel, WritingToolsPanel, Panels (emoji/clipboard)
  models/                         ModelCatalog (embutido + catálogo online validado), ModelRepository,
                                  ModelDownloadWorker (retomável, SHA-256, marcador de verificação)
  updates/UpdateChecker.kt        catálogo de modelos + versão do app (só avisa; nunca instala)
  ui/                             app de configuração (onboarding, home, modelos, ajustes, vocabulário, sobre)
app/src/main/assets/              dicionários pt_br/en, emojis, modelo VAD Silero
app/src/test, app/src/testDebug   testes JVM e capturas de tela (Roborazzi) em app/build/screenshots/
catalog/models.json               catálogo online (publicado no site; só ADICIONA modelos)
site/                             página pública (GitHub Pages). build.py gera _site/ e uma prévia única
CHANGELOG.md                      fonte das "Novidades" do site e das notas de versão
SECURITY.md                       modelo de ameaças e controles — mantenha atualizado
third_party/                      submódulos fixados: whisper.cpp v1.9.5, llama.cpp b12068
```

## Regras de segurança (não negociáveis)

1. O código do teclado (pacote `ime`, `dictation`, `engine`) **nunca** acessa a rede. Toda rede passa por
   `core/SecureHttp.kt`, com lista de hosts permitidos verificada em cada redirecionamento.
2. Nenhum log com texto do usuário. Release remove `Log.v/d/i`.
3. Campos de senha e "sem aprendizado personalizado" (`privateField`): sem aprendizado, sugestões, histórico,
   emojis recentes ou prévia de tecla. Senha usa `FLAG_SECURE`. Ditado de senha: sem IA, mascarado, com confirmação.
4. Modelos: commit fixo + SHA-256 + marcador `.verified`. Catálogo online só adiciona, com validação estrita
   (`ModelCatalog.parseRemote`). Nunca baixar nada sem ação do usuário.
5. Nenhuma chave/segredo no repositório. Assinatura vem de *secrets* do GitHub (`TECLADO_KEYSTORE_BASE64`,
   `TECLADO_KEYSTORE_PASSWORD`, alias `teclado`). Certificado oficial (SHA-256):
   `F0:16:1C:1E:FF:9B:C0:9F:30:BB:D7:A9:68:A6:DD:63:73:E0:92:BD:7C:38:BF:79:F8:64:D7:DB:A1:D7:99:07`
6. Nada de SDKs de analytics, anúncios, Firebase, WebView ou bibliotecas de rede extras. O app não tem
   `REQUEST_INSTALL_PACKAGES`.
7. Dependências novas exigem atualizar `gradle/verification-metadata.xml` (comando abaixo). Actions do GitHub
   sempre fixadas por SHA.
8. Site: sem recursos de terceiros (fontes hospedadas no próprio site), CSP restritiva, sem scripts inline.

## Como compilar (ambiente de nuvem do Claude Code)

O contêiner não vem com Android SDK. Na sessão 1 foi instalado em `/opt/android-sdk`:
cmdline-tools, `platforms;android-36`, `build-tools;36.0.0`, `ndk;28.2.13676358`, `cmake;3.31.6`.
- `dl.google.com` é lento por conexão: baixe com várias conexões em paralelo (curl com `-r` por partes) e
  extraia nas pastas do SDK; depois `yes | sdkmanager --licenses`.
- `repo.maven.apache.org` responde 429 aqui: use um init script local (fora do repo) em
  `~/.gradle/init.d/mirror.gradle` que troca a URL pelo espelho
  `https://maven-central.storage-download.googleapis.com/maven2/`.
- Robolectric baixa o android-all: exporte `ROBOLECTRIC_REPO` com o mesmo espelho.
- `local.properties` com `sdk.dir=/opt/android-sdk` (ignorado pelo git).

```bash
./gradlew :app:testReleaseUnitTest               # lógica (dicionário, SpokenText, catálogo, limpeza)
./gradlew :app:testDebugUnitTest                 # + capturas de tela em app/build/screenshots/
./gradlew :app:assembleRelease                   # APK (assinado com debug se não houver TECLADO_KEYSTORE_FILE)
./gradlew --write-verification-metadata sha256 :app:assembleRelease :app:testReleaseUnitTest :app:testDebugUnitTest
python3 site/build.py --out _site --apk app/build/outputs/apk/release/app-release.apk
```
A primeira compilação nativa leva ~5 min (7 variantes de CPU do ggml + KleidiAI baixado do GitHub).

## Publicação e versões

1. Atualize `version.properties` (ex.: 1.1.0) e adicione a seção `## 1.1.0 — AAAA-MM-DD` no topo do
   `CHANGELOG.md` (itens com "- ").
2. Faça push. O workflow `.github/workflows/android.yml` testa, compila, e — se os secrets de assinatura
   existirem e a tag `v<versão>` ainda não existir — cria a release no GitHub e publica o site no branch
   `gh-pages` com o APK, o SHA-256, `version.json` e `catalog/models.json`.
3. O app instalado descobre a nova versão por `version.json` e avisa o usuário.
- Site: `https://maisumfernando.github.io/C-/` (se o repositório for renomeado, atualize `core/ProjectInfo.kt`,
  `site/build.py` e este arquivo). O GitHub Pages precisa estar ativo para o branch `gh-pages`.

## Atualizar modelos (sem nova versão do app)

Edite `catalog/models.json` (incremente `catalogVersion`) e publique o site. Cada entrada precisa de:
`id, kind (speech|text), name, badge, description, repo (org confiável), revision (commit de 40 hex),
file (.bin para voz, .gguf para texto), size, sha256, license, minRamGb, quality, speed` e opcional
`supersedes` (ids que o novo modelo substitui — vira "Atualizar" no app).
Pegue commit, tamanho e SHA-256 pela API: `https://huggingface.co/api/models/<repo>` (campo `sha`) e
`https://huggingface.co/api/models/<repo>/tree/main` (campos `size` e `lfs.oid`).
Organizações aceitas: ggerganov, ggml-org, unsloth, Qwen, google, openai (ver `ModelCatalog.trustedOrgs`).
Para mudar a recomendação padrão ou a lista embutida, edite `ModelCatalog.kt`.

## Decisões importantes (e por quê)

- whisper.cpp compilado contra o ggml do llama.cpp: uma só cópia do ggml, sem conflito de símbolos,
  e as duas IAs ganham as variantes de CPU. A API do ggml usada pelo whisper é compatível (só adições).
- `extractNativeLibs=true` (useLegacyPackaging): o ggml procura as variantes de CPU numa pasta.
- Sem composing text: o teclado comita direto e acompanha a palavra atual; menos bugs em apps diversos.
- Prompt de revisão com exemplos por estilo; o prefixo fica em cache (KV/snapshot), então só a fala é processada.
- Saída da IA validada; se suspeita, usa a limpeza por regras (`TranscriptCleaner.basicPolish`).
- Segurar espaço = ditado rápido; segurar emoji = trocar de teclado.
- UI do app e do teclado está em português direto no código (Compose). Só o nome do app é traduzido.

## Ideias e pendências (backlog)

- Testar em aparelhos reais (Samsung, Huawei Pura 80 Pro/EMUI 15, celulares de 3–4 GB) e ajustar tempos.
- Medir a qualidade da revisão de cada LLM com frases reais; ajustar `Prompts.kt`.
- Traduzir a interface (mover textos para `strings.xml`).
- Digitação por deslize (swipe typing), layouts de outros idiomas, armeabi-v7a.
- Aceleração por GPU/NPU (OpenCL Adreno, Vulkan) com fallback seguro.
- Publicar na Google Play/AppGallery (metadados em `fastlane/metadata/android`).
