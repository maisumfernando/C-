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

## Sessão 2 — 2026-11-03 — primeiro uso real e versão 1.1.0

**Pedido (com capturas do Huawei Pura 80 Pro):** altura do teclado começar em 100%; textos escuros ilegíveis no
modo escuro; a mensagem "Ouvindo… solte para inserir" continuava na barra de espaço depois de soltar; um teste
de velocidade dos modelos e das combinações (ex.: Whisper Base + Qwen 4B, Turbo + Gemma) com o tempo por
quantidade de palavras; o botão de download do site não baixava nada; site mais bonito, com capturas de tela.

**Diagnóstico:**
- Modo escuro: as telas não tinham `Surface`, então `Text` sem cor usava o preto padrão de `LocalContentColor`.
- Barra de espaço: o rótulo dependia de `quickDictation`, que só volta a falso quando o texto é inserido.
- Download: a CI compilou, mas os secrets de assinatura não existem, então não houve release nem site
  (gh-pages inexistente, Pages 404). A prévia apontava para um APK no Pages que nunca foi publicado.

**Feito:**
- Tema com `Surface` (app) e `LocalContentColor` no teclado; capturas novas em modo escuro para conferir.
- Altura: nova escala 70–100% (100% = máximo e padrão), altura cheia adaptada à tela, chave nova para todos
  começarem em 100%.
- `quickHolding` + fase da sessão controlam a dica da barra de espaço; gesto interrompido também finaliza.
- Teste de velocidade (Início → "Teste de velocidade", e atalho em Modelos): mede cada modelo instalado com
  um trecho de 15 s de "O Alienista" (LibriVox, domínio público) e um ditado bagunçado de 55 palavras; mostra
  espera estimada para 15/50/150 palavras por combinação, precisão do Whisper, palavras/s do LLM, saídas para
  comparar qualidade, selos "Mais rápida / Melhor qualidade / Equilíbrio", estimativa de modelos não
  baixados e botão "Usar esta combinação". 9 testes novos (`BenchmarkTest`).
- Site refeito: herói com celular e teclado real ditando, diagrama "dentro do seu celular", galeria com 8
  capturas, recursos com mini-ilustrações, seção do teste de velocidade, telas do app, lista de endereços
  permitidos, faixas de memória, instalação com QR code. Download aponta para
  `releases/latest/download/Teclado-do-Fernando-Godinho.apk` (a CI agora anexa esse nome fixo).
- CI roda em qualquer branch; site só é republicado em versão nova, branch padrão ou execução manual.
- Versão 1.1.0, CHANGELOG, README, NOTICE (crédito do áudio), SECURITY (benchmark.json).

**Pendências:**
1. O dono precisa cadastrar os secrets e reexecutar a CI deste branch (passo a passo no CLAUDE.md) para
   publicar a release 1.1.0 e o site; depois ativar o Pages no branch `gh-pages`.
2. Pedir os resultados do teste de velocidade no Pura 80 Pro para calibrar estimativas e recomendações.
3. Considerar unir este branch ao branch padrão (PR) quando o dono quiser.

## Sessão 2 (continuação) — versão 1.2.0

**Pedidos:** barra de espaço como trackpad (igual ao iPhone) e ditado só no microfone; botão do microfone
sem o azul; 4 ou 5 temas novos com cuidado de legibilidade; teclado na TV (detectar TVs na rede, avisar
quando a TV abrir uma caixa de texto, digitar pelo celular) para todas as marcas vendidas no Brasil; página
sobre o Fernando com a chave Pix (app e site); implementar as sugestões do documento
`sugestoes_teclado_android_100_por_cento_local.md` e fazer uma revisão completa em busca de bugs.

**Feito:**
- Trackpad no espaço (dois dedos selecionam), setas protegidas contra troca de foco; ajuste removido.
- Microfone grafite neutro; temas Grafite, Meia-noite, Sálvia, Areia, Lavanda e Alto contraste, com seletor
  visual; `ThemeContrastTest` (teclado e app) — corrigiu o verde de "Pronto para ditar" e o texto secundário.
- Teclado na TV: Google TV/Android TV (pareamento por código, TLS com chave no Keystore), Samsung Tizen,
  LG webOS e Roku; descoberta mDNS + SSDP; serviço opcional que avisa quando a TV abre uma busca;
  tela "Digitar na TV" (funciona com o ditado do teclado); bloco nas Configurações rápidas; só rede local.
- Apoie o projeto: tela no app e seção no site com a chave Pix fjgs@outlook.com (botão copiar).
- Do documento de sugestões: calculadora, conversor de unidades, respostas salvas com /atalho (app para
  criar/editar/exportar), lembretes e timers por regras, domínios de e-mail, alerta de cartão/CPF/CNPJ,
  editor "Escrever com calma", prévia antes de qualquer alteração da IA e novas ferramentas (mais claro,
  simples, descontraído, assertivo, profissional, expandir, resumir, sugerir resposta, perguntas sem
  resposta, ambiguidades, coerência, sinônimos).
- Revisão de bugs: painéis fechavam quando o app reiniciava o campo; sessão da TV caía para "parear de
  novo" após uma queda de Wi-Fi e parava de reconectar; laço ocupado ao fechar WebSocket; ditado não passava
  pelo alerta de dados sensíveis; efeito colateral em composição no painel de prévia.
- Testes: 9 novos de TV (protocolo byte a byte, WebSocket, diff de texto), contraste, ferramentas locais;
  capturas de todos os temas e telas novas.

**Pendências:** testar a TV com aparelhos reais; secrets de assinatura (ver CLAUDE.md); itens do backlog.

**Depois:** o dono pediu digitação por deslize (swipe), considerada essencial. Feito: `SwipeDecoder` (forma +
posição + frequência, índice por primeira/última tecla, acentos e ç), rastro desenhado sobre as teclas,
palavra inserida com espaço automático, alternativas na barra (tocar troca a palavra), apagar remove a palavra
inteira, ajuste "Digitação por deslize" (ligado por padrão). Teste com deslizes simulados com ruído:
97,9% de acerto em 1º lugar e 100% entre os 3 primeiros em 96 palavras frequentes. Precisa de teste real.

## Sessão 3 — 2026-11-03 — união dos branches

**Pedido:** continuar a partir do branch `ccr-ea7b7ccf-yjpxlg` (1.2.0 + deslize).

**Feito:** o commit do modo privado existia só num branch paralelo (feito a partir da 1.0.0). Ele foi
reaplicado sobre a 1.2.0 no branch `ccr-df405e2e-dciwx8`: ajuste `autoPrivateApps` ao lado de trackpad e
deslize (o antigo `holdSpaceToDictate` continua removido), `applyPrivacy` mantendo a regra de não fechar
painéis no mesmo campo, botão de óculos na barra junto de "Respostas salvas" e "Escrever com calma", cartão
"Modo privado automático" no site novo, item na seção 1.2.0 do CHANGELOG. Em modo privado também ficam
desligados os atalhos inteligentes, o aviso de dados sensíveis e o editor "Escrever com calma" (já usavam
`privateField`). Testes JVM e capturas passam.

**Pendências:** as mesmas da sessão 2 (secrets de assinatura, Pages, teste real de TV, deslize e modo privado).
