# CueOpen — Plano de conclusão da carteira Solana

**Regra de trabalho:** não compilar o APK nem disparar o workflow de build enquanto as quatro tarefas abaixo não estiverem concluídas, revisadas e testadas. Cada tarefa só passa para concluída quando todos os critérios de aceite correspondentes forem satisfeitos; não marcar como feita apenas porque o código foi iniciado.

**Estado atual:** fundação inicial integrada em `main`. Ainda não é uma carteira completa. Não depositar fundos reais.

## Tarefa 1 — Geração de chaves, criptografia local e recuperação

- [ ] Escolher e integrar bibliotecas criptográficas mantidas e compatíveis com Solana/BIP-39; evitar implementar criptografia ou derivação de chave manualmente.
- [ ] Implementar geração segura de entropia e frase de recuperação interoperável com carteiras Solana estabelecidas, documentando o caminho de derivação usado.
- [ ] Implementar restauração a partir da frase de recuperação e validar que a chave pública restaurada corresponde à carteira original.
- [ ] Integrar o material secreto ao Android Keystore e ao armazenamento local criptografado; tratar falhas, corrupção, troca de aparelho e chave do Keystore ausente sem sobrescrever dados existentes.
- [ ] Criar fluxo de backup com confirmação de que o usuário anotou e verificou a frase; nunca registrar, enviar ou incluir frase/chave privada em LLM, chat, logs, analytics ou relatórios de falha.
- [ ] Adicionar testes automatizados para geração, derivação, criptografia/descriptografia, adulteração, restauração e casos de erro.
- **Critério de conclusão:** testes de interoperabilidade passam; restauração reproduz o endereço esperado; segredo permanece fora de logs/LLM; não é possível habilitar uso financeiro sem confirmar backup.

## Tarefa 2 — RPC, saldo, assinatura e ciclo de vida das transações

- [ ] Completar cliente RPC configurável e validado para saldo, taxas, blockhash, simulação, envio e confirmação de transações; tratar timeouts, respostas inválidas, erros de rede e limites do endpoint.
- [ ] Implementar assinatura exclusivamente local com biblioteca Solana auditável; nenhuma API de rede recebe chave privada ou frase de recuperação.
- [ ] Implementar transferências SOL com validação de endereço, saldo, taxas, precisão em lamports, simulação e confirmação explícita antes da assinatura, salvo política autônoma restrita e previamente autorizada.
- [ ] Implementar suporte a swaps apenas por integração com programa/provedor identificado e validado; verificar mint/token program, slippage, rota, taxas, simulação e destinatário. Não assinar transações arbitrárias construídas pelo modelo.
- [ ] Implementar idempotência persistente, reconciliação por assinatura e tratamento de blockhash expirado; não reenviar cegamente uma operação que pode já ter sido confirmada.
- [ ] Registrar histórico não secreto e estados distintos: preparado, aguardando aprovação, enviado, confirmado/finalizado e falhou.
- [ ] Criar testes para precisão de valores, endereço inválido, taxa insuficiente, simulação com erro, timeout, transação duplicada e confirmação/reconciliação.
- **Critério de conclusão:** todas as operações permitidas são validadas deterministicamente antes da assinatura, têm estado rastreável e passam por testes em devnet/testes simulados; não usar fundos reais para validar a implementação inicial.

## Tarefa 3 — Interface completa e controles de carteira

- [ ] Criar tela de carteira com estado bloqueado/desbloqueado, endereço público, saldo, rede selecionada, atualização de saldo e estados de erro/carregamento.
- [ ] Criar fluxos de criar carteira, revelar/verificar backup com privacidade, restaurar carteira e bloquear/desbloquear; impedir captura acidental da frase por logs, screenshots de diagnóstico ou conteúdo enviado ao modelo.
- [ ] Criar fluxo de receber com endereço copiável e QR, com indicação clara da rede.
- [ ] Criar fluxo de envio com destinatário, valor, taxa estimada, simulação/resumo e tela final de confirmação; deixar claro que transações em blockchain normalmente não podem ser desfeitas.
- [ ] Criar histórico de transações e detalhes de status/assinatura com link de explorador correto para a rede selecionada.
- [ ] Criar configurações de permissões por agente, limites por transação e por período, destinatários autorizados, execução autônoma e revogação imediata; padrão sempre desativado.
- [ ] Tratar acessibilidade, rotação/recriação da tela, ausência de rede, mensagens localizadas e estados vazios/erro.
- **Critério de conclusão:** todos os fluxos funcionam de ponta a ponta com devnet ou dados simulados; permissões são claras, revogáveis e persistidas com segurança; nenhum botão sugere uma ação financeira que não esteja implementada.

## Tarefa 4 — Isolamento dos agentes, políticas de segurança e validação final

- [ ] Integrar ferramentas de carteira somente ao agente explicitamente autorizado; o chat normal e agentes sem permissão não devem receber schemas nem conseguir invocar as ferramentas.
- [ ] Fazer a autorização novamente no serviço local de carteira/assinador, não apenas na UI ou no prompt; verificar identidade do agente, ação, rede, ativo, montante, destino e limites a cada operação.
- [ ] Implementar controle cumulativo persistente de limite diário/período, concorrência segura, allowlist de destinatários e proteção contra repetição; fail closed se política ou estado não puder ser lido.
- [ ] Manter frase/chave fora do contexto do LLM e de todos os argumentos/resultados das ferramentas; o modelo só pode propor intenções tipadas e nunca entregar transações arbitrárias para assinatura.
- [ ] Testar tentativa de chamada direta por agente não autorizado, política desativada, limite excedido, destinatário proibido, prompt injection, repetição, falha de armazenamento e falha de RPC.
- [ ] Revisar segurança de backup, Android Keystore, logs/crash reports, dependências, permissões Android e tratamento de dados; corrigir os achados bloqueadores.
- [ ] Atualizar documentação de arquitetura, modelo de ameaças, recuperação, limites conhecidos e procedimento de resposta a incidente.
- [ ] Confirmar testes de unidade/instrumentação e validação de carteira restaurada em segundo dispositivo quando disponível. Só então liberar a compilação final do APK e revisar o artefato e seu checksum.
- **Critério de conclusão:** nenhuma ferramenta de carteira acessível fora do agente autorizado; políticas e limites impostos pelo código local; testes de segurança passam; todas as tarefas 1–3 estão concluídas e a revisão final não possui bloqueadores.

---

## Regra para atualizar este arquivo

Ao concluir uma tarefa, marcar sua caixa como `[x]`, anotar o commit que contém a implementação e registrar evidências dos testes. Não marcar tarefas parcialmente concluídas. A compilação final só pode ser reativada depois de todas as quatro tarefas estarem concluídas e revisadas.

## Registro de progresso (não altera o estado das tarefas)

- 2026-10-26 — Criados testes unitários para o validador de endereços Solana, cobrindo endereço conhecido do System Program, comprimento incorreto, alfabeto inválido e entrada vazia. Commit: `0eb956822d8b16572c63c70d8ef363b9e7ce2d07`. Os testes estão escritos, mas **não foram executados**.


- 2026-10-26 — Adicionada validação estrita de endereço Solana: além de Base58, exige que o texto decodifique para exatamente 32 bytes; `WalletTransferIntent` agora rejeita destinatários malformados antes de qualquer fluxo de transação. Commits: `750813fb7254d6704b7604812e17b32781fb2070`, `defbc6e0a83f8461025140fefffe0afc25b94241`. **Validação automatizada ainda pendente; não compilado/testado**.


- 2026-10-26 — Adicionado `SolanaWalletRepository` e sessão temporária de configuração: criação/restauração fica em memória até o usuário confirmar a frase; a persistência local grava a seed no cofre e o endereço público/metadado de backup em preferências; falha ao salvar metadados tenta reverter o cofre. Commit: `3bec15e8b346cc439ef7ac98ae286d61824786cd`. **Ainda sem integração à interface e sem testes/build**. Revisão pendente: evitar cópias imutáveis da frase durante normalização da confirmação e cobrir recuperação/rollback com testes.


- 2026-10-26 — Cofre local reforçado: gravação criptografada passa por arquivo temporário sincronizado antes da publicação; falhas limpam temporários; leitura valida tamanho e zera buffers temporários; exclusão falha explicitamente se não conseguir remover o arquivo. Commit: `47f740f088b03f8dffdecad08dcd7c62a8ceb8fc`. **Não compilado nem testado** (conforme regra de adiar build/testes até as quatro tarefas estarem implementadas).

- 2026-10-26 — Workflow de build alterado para execução manual; pushes/PRs não compilam o APK durante a implementação. Commit: `e2b89128ea1bed06224a2aed1e6b072d935d9cea`.
- 2026-10-26 — Política de transferência passou a receber o gasto acumulado do dia e negar limites excedidos; execução autônoma exige allowlist explícita. Commit: `8ccab2d0c623d2b400c738d504f4f8f2cfea5b8e`.
- 2026-10-26 — Dependências BIP-39 e Solana adicionadas, com derivação local e restauração para `m/44'/501'/0'/0'`. Commits: `6eec0b10cd56f43b4702e7e3ff16ebe7dba644fe`, `da58edc65c27c3ddd739bf3939f5040e15481d61`, `c14f8e58029f735f9190b945c03084ef8b180018`.
- 2026-10-26 — Testes unitários adicionados para limites de política e fluxo geração/restauração. Commits: `4f15fc3dd1430c98d6315279f26841788dac6a41`, `255f0c8d0845cca4fe043d6b424d0a6dcb184583`.
- 2026-10-26 — Ajustado o teste de frase inválida para aceitar a exceção de validação definida pela biblioteca BIP-39. Commit: `07f2881600080e9fd44b26f592221617a22fbf86`.
- **Validação pendente:** por decisão do projeto, nenhum teste ou build foi executado ainda. Não marcar a Tarefa 1 como concluída até validar compatibilidade com vetor independente de carteira Solana e executar os testes depois que as quatro tarefas estiverem implementadas.

- 2026-10-26 — A confirmação do backup deixou de normalizar a frase por meio de Strings imutáveis: comparação agora usa buffers de caracteres temporários, normaliza caixa/espaços e limpa os buffers após a comparação. Extraído para `RecoveryPhraseConfirmation.kt`, com testes unitários de normalização e divergência de palavras/quantidade. Commits: `976e11f792422f685997b8987d596a90dfc35f75`, `6b7f716b099257873d395053da06378eb82a3bce`. **Testes ainda não executados; Tarefa 1 continua pendente de interoperabilidade independente, testes do cofre e integração completa.**

- 2026-10-26 — Após confirmação e persistência bem-sucedidas, a sessão de configuração agora zera imediatamente o material secreto em memória, sem esperar que a UI lembre de fechar a sessão. Commit: `56ff6dbfafe52ded2f554aa57ff34dede6d10a7c`. **Ainda não testado/compilado.**

- 2026-10-26 — Adicionado vetor de interoperabilidade independente para BIP-39 + SLIP-0010 no caminho `m/44'/501'/0'/0'`: a frase pública de teste `abandon × 11 + about` deve derivar o endereço `HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk`. Referências cruzadas: documentação/código público de restauração Solana e vetores publicados por implementações independentes. Commit: `a86ec4bc2d9fe91e1595c94d86031e85bb16fe7b`. **Teste adicionado, ainda não executado; não é evidência de que passou.**

- 2026-10-26 — Documentado o caminho de derivação, as diferenças de compatibilidade entre carteiras, o papel da frase de recuperação, as limitações do Android Keystore e as regras de não divulgação de segredos em `docs/wallet-recovery.md`. Commit: `173870ec47393297cfe7e91e8eaf938a629099a0`. O documento deixa explícito que o vetor de interoperabilidade foi adicionado, mas ainda não executado, e que a carteira não está pronta para fundos reais.

- 2026-10-26 — Corrigida a limpeza de buffers na restauração: a cópia da frase de entrada agora é zerada também quando a derivação falha; buffers temporários de derivação são limpos mesmo se HMAC lançar exceção. Commit: `5a689cb65d00ee0cad4c4869210fccd12531e452`.
- 2026-10-26 — Normalizados os testes de derivação para zerar arrays com `0.toChar()`, evitando escapes ambíguos no código-fonte. Commit: `4ef56443eda1193f93632f347c4931b4d0fbd023`. Testes ainda não executados.
- 2026-10-26 — Adicionado caminho de reparo de metadados perdidos: exige frase válida, descriptografa a seed existente, compara sem desvio antecipado e só regrava endereço/backup se a frase corresponder à seed local. Não substitui o arquivo secreto; buffers são limpos inclusive em erro de derivação. Commits: `dc078ba6fa7af037201091c766bbb111fdcbcf80`, `3208f632d9841b273e71c796620bf4c109b3c915`. Ainda sem testes de integração nem execução.

- 2026-10-26 — Ampliei `WalletAgentPolicyTest.kt` com casos de destinatário fora da allowlist, agente sem permissão de transferência, gasto acumulado extremo (`Long.MAX_VALUE`) e uso exato do limite diário restante. Commit: `bf3e3cfa277c23caf50b5b9ad12113cae4e82abb`. **Testes apenas escritos; não foram executados.** A política ainda precisa ser aplicada pelo componente de assinatura/transação e ter o gasto diário persistido de forma atômica.

- 2026-10-26 — `SolanaRpcClient.getBalanceLamports` passou a reutilizar o validador estrito de chave pública (Base58 decodificado em exatamente 32 bytes), em vez de aceitar apenas formato/alfabeto plausível. Isso rejeita entradas malformadas antes da chamada RPC. Commit: `5ee388fee810c8ab5080456262f2c2c12ca6a6b9`. **Não compilado nem testado**; cliente continua somente leitura e não implementa assinatura/envio/confirmação.
