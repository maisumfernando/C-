# Teclado do Fernando Godinho

Teclado completo para Android em que você **fala** e recebe um **texto revisado em português impecável**.
O Whisper transcreve a voz e um modelo de linguagem revisa o texto, os dois rodando **no próprio celular**:
nada do que você diz ou digita vai para servidor nenhum.

**Site e download:** https://maisumfernando.github.io/C-/

## Destaques

- **Segure o espaço, fale, solte:** o texto entra no campo já revisado.
- **Revisão na norma-padrão:** sem "né", "tipo", repetições ou autocorreções faladas; crase, pontuação e
  termos em inglês corretos. Estilos: norma-padrão, natural, mensagem ou sem IA.
- **Códigos e senhas por voz:** "quatro, oito, dois…" vira `482` em campos de código; senhas soletradas
  aparecem ocultas, sem passar pela IA, e só entram após confirmação.
- **Ferramentas de IA:** revisar, corrigir, mais formal, mais amigável, mais curto, em tópicos, virar e-mail,
  traduzir, ou um comando falado.
- **Teclado completo:** acentos no toque longo, ç, sugestões e autocorreção em português, emojis,
  área de transferência, colar inteligente, modo uma mão, temas claro e escuro.
- **Modelos que se atualizam**, sempre conferidos por SHA-256.
- Funciona na maioria dos Androids (8.0+, 64 bits), inclusive Huawei/Honor com EMUI.

## Segurança e privacidade

Veja [SECURITY.md](SECURITY.md): rede limitada ao Hugging Face e ao site do projeto, modelos verificados,
proteção especial em campos de senha e anônimos, nada guardado além do necessário, sem telemetria.

## Para desenvolvedores

- Memória do projeto, arquitetura, como compilar e publicar: [CLAUDE.md](CLAUDE.md)
- Histórico das sessões: [docs/DIARIO.md](docs/DIARIO.md) · Novidades: [CHANGELOG.md](CHANGELOG.md)

```bash
git clone --recursive https://github.com/maisumfernando/C-
./gradlew :app:assembleRelease
```

## Licença

Apache 2.0 — veja [LICENSE](LICENSE) e [NOTICE](NOTICE) (componentes e dados de terceiros).
