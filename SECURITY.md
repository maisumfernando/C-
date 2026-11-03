# Segurança

Um teclado vê tudo o que a pessoa escreve. A segurança deste projeto parte de uma regra:
**texto e voz do usuário nunca saem do aparelho e nunca ficam guardados além do necessário.**

## Como reportar uma vulnerabilidade

Use o recurso *Report a vulnerability* (Security Advisories) do GitHub neste repositório. Não abra
issues públicas para falhas de segurança. Responderemos o mais rápido possível.

## Modelo de ameaças

| Ameaça | Mitigação |
|---|---|
| Vazamento do que é digitado/ditado para a internet | O código do teclado (IME) não chama a rede. Todo acesso de rede passa por `core/SecureHttp.kt`, usado apenas para baixar modelos e verificar atualizações. |
| Conexão com servidores maliciosos ou interceptação (MITM) | Somente HTTPS; `network_security_config.xml` bloqueia texto puro e não confia em certificados instalados pelo usuário; lista de destinos permitidos (`huggingface.co`, `*.hf.co`, o site do projeto) checada em **cada** redirecionamento; sem cookies nem credenciais. |
| Modelo adulterado (ataque ao parser GGUF/ggml) | Cada modelo é fixado em um commit exato do Hugging Face e conferido por **SHA-256** antes do uso; um marcador de verificação (hash + tamanho + data) é exigido a cada inicialização; arquivos ficam somente leitura no armazenamento privado do app. |
| Catálogo de atualizações malicioso | `catalog/models.json` só **adiciona** modelos; cada entrada é validada (organizações confiáveis no Hugging Face, commit de 40 hex, SHA-256 de 64 hex, extensão `.bin`/`.gguf`, tamanho, sem `..`, limites de texto); o catálogo inteiro é rejeitado se algo falhar; não aceita versão de catálogo mais antiga; nada é baixado sem toque do usuário. |
| Atualização do app falsificada | O app **não instala** nada sozinho e não tem a permissão `REQUEST_INSTALL_PACKAGES`; apenas avisa e abre o site oficial. Os APKs são assinados sempre com a mesma chave (guardada em *secrets*, nunca no repositório). |
| Senhas e campos anônimos | Em senhas e em campos com `IME_FLAG_NO_PERSONALIZED_LEARNING`: sem aprendizado de palavras, sem sugestões, sem histórico de área de transferência, sem emojis recentes e sem prévia ampliada de teclas. Em senhas, a janela do teclado usa `FLAG_SECURE` (bloqueia capturas e gravação de tela). |
| Ditado de senhas | Não passa pela IA nem pelo vocabulário pessoal, aparece mascarado e só é inserido após confirmação; nada é salvo. |
| Dados em repouso | Áudio só em memória e sobrescrito com zeros ao fim de cada ditado. Transcrições não são salvas. Histórico da área de transferência só em memória, expira em 1 hora, ignora conteúdo marcado como sensível. Palavras aprendidas ficam no armazenamento privado e podem ser apagadas. O teste de velocidade usa só um áudio e um ditado de exemplo embutidos no app (nunca o microfone) e guarda os tempos medidos em `benchmark.json`, no armazenamento privado. |
| Backup / transferência | `allowBackup=false` e regras de extração que excluem tudo (nuvem e transferência entre aparelhos). |
| Logs | Builds de release removem `Log.v/d/i` (R8); nenhum log contém texto do usuário. |
| Componentes expostos | Só a tela inicial (launcher) e o serviço de teclado (protegido por `BIND_INPUT_METHOD`) são exportados, além de componentes internos do AndroidX protegidos por permissões que só o sistema possui (`BIND_JOB_SERVICE`, `DUMP`). A tela inicial aceita apenas rotas de uma lista fixa. `PendingIntent` sempre imutável. |
| Código nativo | Entradas JNI validadas (limites de áudio, índices, papéis de mensagem); compilação com `-fstack-protector-strong`, `_FORTIFY_SOURCE=2`, RELRO completo, pilha não executável e símbolos ocultos. |
| Cadeia de suprimentos | Submódulos fixados em commits (whisper.cpp v1.9.5, llama.cpp b12068); verificação de dependências do Gradle (`gradle/verification-metadata.xml`, SHA-256); checksum da distribuição do Gradle; GitHub Actions fixadas por SHA de commit; permissões mínimas no workflow. |
| Injeção de prompt pela fala | A IA não tem ferramentas nem acesso a nada além do texto; a saída é verificada (tamanho, repetição, “responder em vez de revisar”) e, se suspeita, substituída pela transcrição limpa por regras. |

## Permissões do Android

| Permissão | Uso |
|---|---|
| `RECORD_AUDIO` | Ditado (somente enquanto o usuário dita) |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Download de modelos e verificação de atualizações |
| `POST_NOTIFICATIONS` | Progresso do download e avisos de atualização |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Continuar downloads grandes em segundo plano |
| `VIBRATE` | Vibração das teclas |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` | Adicionadas pela biblioteca WorkManager: manter o download ativo e reagendar a verificação de atualizações após reiniciar |

Nenhum acesso a contatos, SMS, arquivos, localização, câmera ou contas.

## Limites conhecidos

- As permissões do Android valem para o app inteiro (teclado e configurações compartilham o mesmo processo).
  A garantia de que o texto não vai para a rede é de código: o caminho do teclado não usa `SecureHttp`,
  e isso pode ser auditado neste repositório.
- Em celulares Huawei/Honor, campos de senha podem usar o “Teclado seguro” do sistema.
- A segurança do APK depende de baixá-lo do site oficial ou da página de versões do GitHub e conferir o SHA-256.
