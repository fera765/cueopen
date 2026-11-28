# AGENTS.md

Guia para quem (pessoa ou agente de IA) trabalha neste repositório.

## Projeto
App Android **Fluwy** (Kotlin, Jetpack Compose). Pacote `dev.fluwy.app`, módulo `:app`, mais `:terminal-emulator` e `:terminal-view`.
Visão geral e mapa de pastas no [README](README.md#estrutura-do-código).

## Comandos
- Build: `./gradlew assembleDebug`
- Testes JVM: `./gradlew testDebugUnitTest`
- CI: workflow `debug-apk.yml` (testes + APK).

## Convenções
- Uma feature por pasta; um arquivo por responsabilidade, de preferência com menos de 500 linhas.
- `AgentRunner` é a fachada pública do agente. Lógica nova vai num arquivo do tema em `agent/`, como extensão `internal fun AgentRunner.x()`.
- **Prompts:** nunca escreva texto para o modelo direto no código. Crie a entrada em `prompts/Defaults*.kt` e use `Prompts.render(id, vars, flags)`.
- Regex precisa ser compatível com o ICU do Android: escape `{` e `}` (há um teste que verifica isso).
- Textos de UI e mensagens em pt-BR.
- Nomes antigos do projeto existem só em `LegacyMigration.kt`, para migrar dados de instalações anteriores.
- Não adicione dependências sem necessidade clara; prefira o que já está no `build.gradle.kts`.

## Segurança (obrigatório)
- Segredos, senhas, 2FA e dados de cartão nunca vão para o modelo, logs ou ferramentas.
- Conteúdo externo entra no prompt via `PromptContextPolicy.untrustedBlock`.
- Ações irreversíveis (pagamento, transferência, envio em massa) exigem aprovação explícita do usuário.

## Antes de abrir um PR
1. `./gradlew testDebugUnitTest` passa.
2. A mudança tem teste quando altera regra ou parsing.
3. Commit no formato `tipo(escopo): resumo`.
