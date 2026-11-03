# Escriba — teclado Android com ditado por IA, 100% no aparelho

Escriba é um teclado completo para Android em que você **fala** e recebe um **texto impecável em português**.
A voz é transcrita pelo **Whisper** e, em seguida, um **modelo de linguagem local** reescreve a transcrição:
tira “né”, “tipo”, “é…”, repetições e autocorreções (“na segunda, não, na terça”), corrige gramática,
pontuação, crase e grafia de termos estrangeiros, sem mudar o que você disse.

Nada sai do celular: não há servidor, conta, anúncio ou telemetria. A internet é usada só para baixar os
modelos uma única vez (do Hugging Face, em versões fixas e conferidas por SHA-256).

## Instalação (para quem não programa)

1. Abra a página **Releases** deste repositório no celular e baixe o arquivo `Escriba-….apk` mais recente.
2. Abra o arquivo e permita a instalação de apps desta fonte, se o Android pedir.
3. Abra o app **Escriba** e siga os 4 passos: ativar o teclado, selecioná-lo, liberar o microfone e baixar
   o pacote de modelos recomendado (use Wi-Fi; o download continua em segundo plano).

Cada alteração enviada ao GitHub gera automaticamente um novo APK (aba *Actions* → *Build APK*).

## O que tem

**Ditado**
- Toque no microfone da barra do teclado para falar; **segure** para falar e solte para concluir.
- Prévia ao vivo: enquanto você fala, as frases já ditadas são transcritas nas pausas, então o texto fica
  pronto quase no instante em que você termina.
- Revisão por IA com estilos: *Norma-padrão* (padrão), *Natural*, *Mensagem* ou *Sem IA*.
- Botão para alternar entre o texto revisado e o original depois de inserir.
- Vocabulário pessoal (nomes, empresas, jargões) usado pelo Whisper, pela IA e pelas sugestões.
- Multilíngue, com prioridade para o português do Brasil.

**Teclado**
- QWERTY brasileiro com `ç`, acentos no toque longo (á ã â à, é ê, ó õ ô…), números no toque longo da
  primeira fileira, símbolos em duas páginas, teclado numérico e de telefone, emojis e área de transferência.
- Sugestões e correção automática em português (“nao” → “não”, “voce” → “você”), com desfazer pelo apagar.
- Maiúsculas automáticas, ponto com espaço duplo, deslizar no espaço para mover o cursor, tecla Enter
  contextual (enviar, buscar, ir…), temas claro e escuro, altura ajustável, vibração e som.
- Botão ✦ para revisar com IA qualquer texto digitado ou selecionado.

## Modelos

| Função | Modelo | Tamanho | Observação |
|---|---|---|---|
| Voz | Whisper Large v3 Turbo (q5_0) | 574 MB | Máxima precisão, recomendado para topo de linha |
| Voz | Whisper Small (q8_0) | 264 MB | Equilibrado |
| Voz | Whisper Base (q8_0) | 82 MB | Ultrarrápido |
| Texto | Gemma 4 E2B (Q4_0) | 2,8 GB | Recomendado, ótimo português |
| Texto | Qwen 3.5 4B (Q4_K_M) | 2,7 GB | Máxima qualidade |
| Texto | Qwen 3.5 2B (Q4_K_M) | 1,3 GB | Leve e rápido |

O app sugere o pacote ideal conforme a memória do aparelho.

## Arquitetura

```
app/src/main/cpp/          JNI: whisper.cpp + llama.cpp compartilhando um único ggml
  jni_whisper.cpp          transcrição + VAD Silero em streaming
  jni_llm.cpp              geração com cache do prompt (KV cache / snapshot de estado)
app/src/main/java/app/escriba/
  engine/                  carregamento sob demanda, prompts de revisão, limpeza e salvaguardas
  dictation/               gravação 16 kHz, segmentação por pausas, transcrição incremental
  ime/                     serviço de teclado (Jetpack Compose), layouts, toque multi-dedo, sugestões
  models/                  catálogo, downloads retomáveis com verificação SHA-256 (WorkManager)
  ui/                      app de configuração (onboarding, modelos, ajustes, vocabulário)
third_party/               whisper.cpp v1.9.5 e llama.cpp b12068 (submódulos)
```

- O ggml é compilado com uma variante de CPU por geração de ARM (armv8.0 até armv9.2 com SME) e a melhor é
  escolhida em tempo de execução, com KleidiAI e OpenMP.
- As instruções e exemplos do revisor ficam em cache no modelo: cada pedido processa só a sua fala.
- Saídas da IA passam por verificações (tamanho, repetição, “resposta” em vez de revisão); se algo parecer
  errado, o Escriba usa a transcrição limpa por regras.

## Compilar

Requisitos: JDK 17, Android SDK 36, NDK 28.2.13676358, CMake 3.31.6.

```bash
git clone --recursive https://github.com/maisumfernando/C-
cd C-
./gradlew :app:assembleRelease      # APK em app/build/outputs/apk/release/
./gradlew :app:testReleaseUnitTest  # testes de lógica
./gradlew :app:testDebugUnitTest    # inclui capturas de tela em app/build/screenshots/
```

### Assinatura do APK
Nenhuma chave de assinatura fica no repositório. Para que cada nova versão instale por cima da anterior
(mantendo os modelos baixados), crie dois *secrets* em *Settings → Secrets and variables → Actions*:
`ESCRIBA_KEYSTORE_BASE64` (o arquivo `.jks` em base64, com o alias `escriba`) e `ESCRIBA_KEYSTORE_PASSWORD`.
Sem eles, cada build é assinada com uma chave temporária e é preciso desinstalar a versão anterior antes de
instalar uma nova.

## Licenças e créditos
whisper.cpp e llama.cpp (MIT), Whisper (MIT), Gemma 4 e Qwen 3.5 (Apache 2.0), Silero VAD (MIT),
lista de palavras derivada de FrequencyWords (CC BY-SA 4.0), dados de emoji do Unicode.
