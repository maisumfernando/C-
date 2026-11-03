# Diário de desenvolvimento

Registro cronológico das sessões. Cada entrada diz o que foi pedido, o que foi feito, o que ficou
pendente e o que verificar na próxima vez. O resumo atual do projeto está em `CLAUDE.md`.

## Sessão 1 — 2026-11-03 — primeira versão

**Pedido:** teclado Android completo (inspirado no da Samsung) com ditado estilo Wispr Flow, usando Whisper
local e um segundo modelo de IA que transforma a fala em texto impecável em português, tudo no aparelho,
com APK pronto e design caprichado.

**Feito:**
- Projeto Android do zero (Kotlin + Jetpack Compose), whisper.cpp v1.9.5 e llama.cpp b12068 como submódulos,
  uma só build do ggml com variantes de CPU ARM escolhidas em runtime.
- Motores: transcrição com VAD Silero, cortes nas pausas para transcrever enquanto o usuário fala,
  filtro de alucinações do Whisper ("Legendas pela comunidade Amara.org"…), contexto de áudio reduzido com
  refazer automático, revisão por LLM com prompts e exemplos por estilo e cache do prefixo.
- Teclado: QWERTY PT-BR com ç, acentos no toque longo, símbolos, numérico, telefone, emojis, área de
  transferência, sugestões e autocorreção com acentos ("nao" → "não"), multi-toque, cursor no espaço.
- App: onboarding em 4 passos, catálogo de modelos com downloads retomáveis e SHA-256, ajustes, vocabulário.
- Testes unitários + capturas de tela com Robolectric/Roborazzi.

**Pendências:** push bloqueado (app Claude sem acesso de escrita ao repositório).

## Sessão 1 (continuação) — pedidos do dono

**Pedido:** licença aberta; segurança como prioridade número um; ditar códigos de verificação e senhas;
facilidade extrema de uso; inovações dos teclados topo de linha; rodar na maioria dos Androids e em Huawei
(Pura 80 Pro, EMUI 15/Android 12); landing page bonita com versões; arquivo de memória do projeto;
atualização dos modelos; nome "Teclado do Fernando Godinho" traduzido em todos os idiomas.

**Feito:**
- Renomeado para `com.fernandogodinho.teclado`, nome traduzido em ~75 idiomas (+ títulos para lojas).
- Segurança: `SecureHttp` com lista de hosts, network security config, marcador de verificação dos modelos,
  catálogo online validado, campos privados (senha/anônimo), `FLAG_SECURE` em senhas, histórico de cópia que
  expira em 1 h, áudio zerado após o uso, backup desativado, logs removidos no release, JNI validado e
  compilado com hardening, Actions fixadas por SHA, checksum do Gradle, verificação de dependências,
  chave de assinatura fora do repositório (secrets). `SECURITY.md` com o modelo de ameaças.
- Recursos: modos de ditado por campo (código, código alfanumérico, senha soletrada, e-mail/link, comando),
  segurar espaço para falar, ferramentas de IA (revisar, corrigir, formal, amigável, curto, tópicos, e-mail,
  traduzir, comando de voz), colar inteligente (inclusive só o código), apagar palavras deslizando no
  apagar, desfazer ditado, modo uma mão, tom adaptado ao app (opcional), modelo Qwen 3.5 0.8B para
  aparelhos simples, minSdk 26.
- Atualizações: catálogo `catalog/models.json` + `version.json` publicados no site; verificação periódica
  com notificação; card "Atualizações de modelos".
- Huawei/OEM: dica do "Teclado seguro" e de bateria/segundo plano para downloads.
- Site em `site/` (GitHub Pages via branch gh-pages), versões semânticas (`version.properties`),
  `CHANGELOG.md`, licença Apache 2.0.

**Pendências para a próxima sessão:**
1. Confirmar que o push funcionou e que os secrets de assinatura foram criados; ver a primeira release e o site.
2. Confirmar que o GitHub Pages está ativo para o branch `gh-pages`.
3. Coletar feedback de uso real (velocidade, qualidade da revisão, bugs em apps específicos).

## Sessão 1 (continuação 2) — download e modo privado

**Pedido:** o download do APK enviado pelo chat falhava no celular; manter o nome "Teclado do Fernando
Godinho"; modo privado automático em apps de privacidade (Firefox Focus, DuckDuckGo…) e em sites adultos,
com símbolo de privacidade ao fundo como no SwiftKey; avisar quando for hora de abrir uma nova sessão.

**Feito:**
- Modo privado (`ime/PrivacyMode.kt`, `KeyboardController.applyPrivacy/togglePrivateMode`): liga sozinho em
  senhas, abas anônimas (flag `IME_FLAG_NO_PERSONALIZED_LEARNING`) e numa lista de apps de privacidade
  (navegadores de privacidade, mensageiros seguros, gerenciadores de senha, Proton/Tuta); botão de óculos
  na barra para ligar manualmente; marca d'água de incógnito sobre as teclas (`ime/ui/PrivacyVisuals.kt`);
  ajuste "Modo privado automático". Em modo privado: nada aprendido, sem histórico de cópias, sem emojis
  recentes, sem prévia de tecla; sugestões do dicionário e ferramentas de IA continuam (só senhas as bloqueiam).
- Sites adultos: o Android não informa ao teclado o site aberto; documentado no app, no site e no SECURITY.md.
- CI: a primeira execução falhou na verificação de dependências (cache local escondia 3 BOMs); corrigido
  regerando com cache vazio. Execução #2 passou (testes + APK).
- APK 1.0.0 recompilado e assinado com a chave oficial; site gerado com download direto.

**Pendências:**
1. Publicar o site (branch `gh-pages`) e/ou cadastrar os secrets `TECLADO_KEYSTORE_BASE64` e
   `TECLADO_KEYSTORE_PASSWORD` para a CI publicar release + site automaticamente.
2. Confirmar que o GitHub Pages está ativo (Settings → Pages → branch `gh-pages`).
3. Feedback de uso real no celular (Huawei Pura 80 Pro do dono).
