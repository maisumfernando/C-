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

- Versão: veja `version.properties` (1.0.0 na sessão 1, 1.1.0 e 1.2.0 na sessão 2). `versionCode = major*10000 + minor*100 + patch`.
- Pacote Android: `com.fernandogodinho.teclado`. minSdk 26, targetSdk 36, apenas arm64-v8a.
- **Primeiro uso real (sessão 2):** o dono instalou o 1.0.0 no Huawei Pura 80 Pro (EMUI) com Whisper Large v3
  Turbo + Gemma 4 E2B. Funcionou; ele relatou só bugs de interface (corrigidos na 1.1.0). Desempenho medido
  ainda não foi informado: o novo "Teste de velocidade" serve para isso — peça os números a ele.
- Branches: sessão 1 em `claude/android-keyboard-whisper-dictation-bd4ozi` (branch padrão do GitHub);
  sessão 2 em `ccr-ea7b7ccf-yjpxlg`. A CI roda em qualquer branch.
- **Os secrets de assinatura ainda NÃO existiam no fim da sessão 2** (a CI pulou release e site), por isso o
  botão de download não funcionava e o GitHub Pages não existe. O dono recebeu na sessão 1 o APK 1.0.0
  assinado e um arquivo privado com a chave; ele precisa cadastrar os secrets (passo a passo em "Publicação").
- Prévia da página (artifact privado do dono): https://claude.ai/artifact/3bn2jntjLVTuyY6HojJM2G
- O envio do APK como anexo no chat do Claude falhou no celular do dono: entregue sempre por link
  (site no GitHub Pages ou release do GitHub).
- Modo privado: `ime/PrivacyMode.kt` (lista de apps) + `KeyboardController.applyPrivacy`. Sites não são
  detectáveis por teclados no Android; abas anônimas são, pela flag do campo.

## Estrutura

```
app/src/main/cpp/                 JNI. Um único ggml (do llama.cpp) atende whisper.cpp e llama.cpp
  CMakeLists.txt                  ggml com variantes de CPU ARM (armv8.0…armv9.2+SME) escolhidas em runtime,
                                  KleidiAI, OpenMP, flags de hardening. Biblioteca: libteclado.so
  jni_whisper.cpp                 transcrição + VAD Silero em streaming (entradas validadas)
  jni_llm.cpp                     geração com cache de prompt: LCP no KV cache + snapshot do prefixo
app/src/main/java/com/fernandogodinho/teclado/
  TecladoApp.kt                   singletons (settings, models, engines, updates, learnedWords, benchmark, tv,
                                  snippets, composer)
  core/                           Settings (DataStore), SecureHttp (ÚNICA porta para a internet), LocalNetwork
                                  (só endereços privados, usado pela TV), ProjectInfo (site, chave Pix),
                                  DeviceProfile, OemTips, VocabularyStore, SnippetStore (respostas salvas),
                                  ComposerHandoff (texto do editor "Escrever com calma", só em memória)
  engine/                         EngineManager (carrega/descarrega modelos), SpeechEngine, TextEngine,
                                  Benchmark (cálculos puros do teste de velocidade) e BenchmarkRunner,
                                  Prompts (instruções + exemplos de revisão por estilo), TextPolisher,
                                  TextTools/WritingTool (ferramentas de IA), SpokenText (códigos, senhas,
                                  e-mails), TranscriptCleaner (alucinações, limpeza, saneamento)
  dictation/DictationSession.kt   gravação 16 kHz, VAD, cortes nas pausas, transcrição incremental,
                                  modos TEXT/CODE/CODE_ALNUM/SECRET/ADDRESS/INSTRUCTION
  ime/                            TecladoIme (InputMethodService + Compose), KeyboardController (toda a
                                  edição de texto, botões inteligentes, prévia da IA), layouts, toque
                                  multi-dedo e trackpad, sugestões (suggest/Dictionary)
  ime/smart/                      regras locais: Calculator, UnitConverter, Reminders, SensitiveData
  ime/suggest/SwipeDecoder.kt     digitação por deslize (caminho desenhado × caminho ideal de cada palavra)
  ime/ui/                         KeyboardRoot, KeyGrid, KeyboardTheme (7 temas), DictationPanel,
                                  WritingToolsPanel, SmartPanels (botões, respostas salvas, prévia), Panels
  tv/                             Teclado na TV: AtvProtocol/AndroidTvLink (Google TV), TvBrands (Samsung,
                                  LG, Roku), LanNet (WebSocket/HTTP/SSDP mínimos), TvDiscovery, TvManager,
                                  TvLinkService (aviso em segundo plano), TvKeyboardActivity, bloco rápido
  models/                         ModelCatalog (embutido + catálogo online validado), ModelRepository,
                                  ModelDownloadWorker (retomável, SHA-256, marcador de verificação)
  updates/UpdateChecker.kt        catálogo de modelos + versão do app (só avisa; nunca instala)
  ui/                             app de configuração (onboarding, home, modelos, teste de velocidade, ajustes,
                                  vocabulário, respostas, TV, apoie/Pix, sobre) e ComposerActivity
app/src/main/assets/              dicionários pt_br/en, emojis, modelo VAD Silero, benchmark/alienista.wav
                                  (15 s de "O Alienista", LibriVox, domínio público, para o teste de velocidade)
app/src/test, app/src/testDebug   testes JVM e capturas de tela (Roborazzi) em app/build/screenshots/
catalog/models.json               catálogo online (publicado no site; só ADICIONA modelos)
site/                             página pública (GitHub Pages). build.py gera _site/ e uma prévia única;
                                  img/ tem capturas reais (geradas por make_images.py a partir do Roborazzi)
CHANGELOG.md                      fonte das "Novidades" do site e das notas de versão
SECURITY.md                       modelo de ameaças e controles — mantenha atualizado
third_party/                      submódulos fixados: whisper.cpp v1.9.5, llama.cpp b12068
```

## Regras de segurança (não negociáveis)

1. O código do teclado (pacote `ime`, `dictation`, `engine`) **nunca** acessa a rede. Toda conexão com a internet
   passa por `core/SecureHttp.kt`, com lista de hosts permitidos verificada em cada redirecionamento. A única
   exceção é o pacote `tv` (Teclado na TV), que só fala com endereços **privados** da rede local
   (`core/LocalNetwork.requireLocal`) e só envia o texto da tela "Digitar na TV" para uma TV pareada.
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
- O AGP também quer `build-tools;35.0.0` (baixe `build-tools_r35_linux.zip` como os outros). O `sdkmanager`
  novo não grava mais licenças: crie `/opt/android-sdk/licenses/android-sdk-license` com os hashes conhecidos
  (`24333f8a63b6825ea9c5514f83c2829b004d1fee` etc.), senão o Gradle falha dizendo que a licença não foi aceita.
- Submódulos: `git submodule update --init --depth 1`.

```bash
./gradlew :app:testReleaseUnitTest               # lógica (dicionário, SpokenText, catálogo, limpeza)
./gradlew :app:testDebugUnitTest                 # + capturas de tela em app/build/screenshots/
./gradlew :app:assembleRelease                   # APK (assinado com debug se não houver TECLADO_KEYSTORE_FILE)
# Regere a verificação de dependências com um cache VAZIO (um cache antigo esconde entradas e a CI falha):
GRADLE_USER_HOME=/tmp/gradle-limpo ./gradlew --write-verification-metadata sha256 help :app:assembleRelease :app:testReleaseUnitTest :app:testDebugUnitTest
#   (copie antes o init script do espelho para /tmp/gradle-limpo/init.d/)
python3 site/build.py --out _site --apk app/build/outputs/apk/release/app-release.apk
python3 site/make_images.py      # depois do testDebugUnitTest: atualiza as capturas do site (precisa de Pillow)
python3 site/build.py --artifact preview.html   # prévia de arquivo único (imagens embutidas)
```
As capturas são renderizadas em `pt-rBR` com 8 GB de RAM simulados (ver `ScreenshotTest`).
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
- Download estável: cada release também leva `Teclado-do-Fernando-Godinho.apk` (nome fixo), então
  `https://github.com/maisumfernando/C-/releases/latest/download/Teclado-do-Fernando-Godinho.apk` sempre
  baixa a versão mais nova. A prévia (artifact) e o README usam esse link; o site usa a cópia em `download/`.
- O site é republicado quando sai uma versão nova, no branch padrão ou por execução manual.
- **Cadastrar os secrets (dono):** GitHub → repositório → Settings → Secrets and variables → Actions →
  New repository secret: `TECLADO_KEYSTORE_BASE64` (o conteúdo do arquivo .p12 em base64, do arquivo privado
  da sessão 1) e `TECLADO_KEYSTORE_PASSWORD`. Depois: Actions → última execução → Re-run all jobs. Por fim,
  Settings → Pages → Deploy from a branch → `gh-pages` / root.

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
- Segurar espaço = trackpad (pedido do dono, como no iPhone; dois dedos selecionam). Ditado só pelo
  microfone (toque, ou segure o microfone para falar). Segurar emoji = trocar de teclado. Setas só são
  enviadas quando há texto naquela direção (evita o app mudar o foco para outro campo).
- Deslize: começa quando o dedo sai de uma letra e anda mais que ~0,85 tecla (antes disso vale o toque e o
  "deslizar para corrigir"). `SwipeDecoder` reamostra o traço e o caminho ideal de cada palavra (48 pontos),
  soma distância de posição + forma e subtrai a frequência; candidatos só com a 1ª e a última tecla perto do
  início e do fim. Teste com deslizes simulados: ~98% de acerto em 1º lugar. A palavra entra com espaço;
  as alternativas ficam na barra; apagar logo depois remove a palavra. Desligado em campos de senha.
- Temas: `KeyboardThemeId` + `KeyboardPalette`. `ThemeContrastTest` reprova qualquer par de cores abaixo do
  WCAG (texto 4,5:1, ícones 3:1), inclusive no esquema de cores do app.
- Ferramentas de IA nunca alteram o texto direto: o resultado vai para `ToolResult`/painel de prévia
  (substituir, inserir abaixo, copiar, descartar). Ferramentas de resposta usam só a mensagem que o
  usuário copiou (`clipboard.recent()`), nunca leem outros apps.
- Teclado na TV: Google TV usa o protocolo do app Google TV (pareamento por código, chave no Keystore);
  Samsung/LG usam WebSocket com autorização na TV (token/client-key salvos em `tvs.json`); Roku usa ECP.
  Certificados TLS da TV são fixados no primeiro uso. Hisense VIDAA e Panasonic não são suportadas.
- Altura do teclado: 100% = tamanho máximo e padrão (escala 70–100%, chave `keyboard_height_v2`). A altura
  cheia de cada fileira acompanha a tela (`fullRowHeight`: 6,6% da altura, entre 52 e 64 dp; paisagem menor).
- Tema do app: `TecladoTheme` envolve tudo num `Surface` (sem ele, Text sem cor saía preto no modo escuro).
- Teste de velocidade: um modelo carregado por vez (`withDedicatedSpeech/Text`), áudio e ditado fixos,
  precisão = 1 − WER contra o texto de referência. O tempo de espera simula os cortes nas pausas (~8 s) e
  soma a revisão (leitura + escrita em tokens/s). Modelos não baixados são estimados a partir do medido mais
  parecido (`BenchmarkMath.relativeCost`). Resultados em `files/benchmark.json`.
- UI do app e do teclado está em português direto no código (Compose). Só o nome do app é traduzido.

## Ideias e pendências (backlog)

- **Teclado na TV nunca foi testado com uma TV real**: os protocolos foram conferidos byte a byte contra
  as definições/implementações de referência (androidtvremote2, samsungtvws, aiowebostv), mas peça ao dono
  para testar com a TV TCL dele e, se possível, Samsung/LG. Ajustar mensagens de erro conforme o retorno.
- Do documento de sugestões do dono, ficaram para depois: previsão de frases (bigramas aprendidos, opt-in),
  busca semântica nas respostas salvas (precisa de modelo de embeddings local), OCR de imagem escolhida
  pelo usuário (sem ML Kit, que envia métricas) e tradução com modelo dedicado (hoje a tradução usa o LLM).

- Pedir ao dono os números do "Teste de velocidade" no Pura 80 Pro e calibrar `relativeCost`, os segmentos
  e as recomendações de `ModelCatalog.recommended` com dados reais (e de celulares de 3–4 GB).
- Testar em outros aparelhos (Samsung, celulares simples) e ajustar tempos.
- Medir a qualidade da revisão de cada LLM com frases reais; ajustar `Prompts.kt`.
- Traduzir a interface (mover textos para `strings.xml`).
- Deslize: medir com uso real e ajustar pesos (`PRIOR_WEIGHT`, forma × posição); previsão da próxima palavra.
- Layouts de outros idiomas, armeabi-v7a.
- Aceleração por GPU/NPU (OpenCL Adreno, Vulkan) com fallback seguro.
- Publicar na Google Play/AppGallery (metadados em `fastlane/metadata/android`).
