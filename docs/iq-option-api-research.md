# IQ Option: comparação com a API comunitária no GitHub

O conector do aplicativo implementa seu próprio cliente Kotlin HTTP/WebSocket. Ele não incorpora o pacote Python `iqoptionapi` e a IQ Option não apresenta, nas fontes públicas consultadas, uma API de desenvolvedor documentada para esses métodos. Os formatos de mensagens devem ser tratados como protocolo observado por terceiros, sujeito a mudanças.

## Estado do repositório comunitário

O endpoint público do GitHub para o repositório [`Lu-Yi-Hsun/iqoptionapi`](https://github.com/Lu-Yi-Hsun/iqoptionapi) apontou como commit mais recente `7dd95a81c079e8af7a3728aa2aa194154f29ba3f`, datado de 2026-07-01 14:51:19 UTC: [commit no GitHub](https://github.com/Lu-Yi-Hsun/iqoptionapi/commit/7dd95a81c079e8af7a3728aa2aa194154f29ba3f) e [resposta da API pública de commits](https://api.github.com/repos/Lu-Yi-Hsun/iqoptionapi/commits?per_page=1). A mensagem é “close the npt-life web”. O diff desse commit altera apenas o `README.md` (remove ligações a um site de terceiros); não documenta mudança no protocolo de saldo ou na troca de conta. Portanto, esse commit não fornece uma correção para o defeito observado.

A documentação publicada pelo mesmo projeto diz “last update: 2020/3/13”, versão 6.8.9, e demonstra login/conexão e leitura de candles; veja [documentação do iqoptionapi](https://lu-yi-hsun.github.io/iqoptionapi/). O texto sugere monitorar conexão e reconectar após perda, mas não estabelece semântica oficial de ACK ou ordenação de eventos para troca de carteira.

## Comparação e consequência técnica

No código do aplicativo, a troca envia `api_profile_changebalance` com `balance_id`, e a seleção visual e de ordens depende de `profile` ou `balance-changed` contendo um ID explicitamente reconhecível. Como o wrapper comunitário mais recente não alterou nem confirmou esse fluxo, a correção deve ser conservadora: aceitar apenas confirmação explícita do ID alvo na geração de conexão correspondente, ignorar eventos antigos de outra carteira para a seleção ativa e permanecer bloqueado/indeterminado se não houver ACK. Os testes são de reducer/fake broker e não afirmam compatibilidade com conta real.

Fontes consultadas:

- [Repositório comunitário no GitHub](https://github.com/Lu-Yi-Hsun/iqoptionapi)
- [Commit mais recente observado e diff](https://github.com/Lu-Yi-Hsun/iqoptionapi/commit/7dd95a81c079e8af7a3728aa2aa194154f29ba3f)
- [Documentação publicada pelo projeto](https://lu-yi-hsun.github.io/iqoptionapi/)
