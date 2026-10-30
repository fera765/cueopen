# IQ Option: métodos encontrados e limites da evidência

## O que o projeto usa

O conector do repositório implementa Kotlin próprio sobre login HTTP e WebSocket; não depende do SDK `iqoptionapi`. As mensagens expostas (`get-initialization-data`, `get-candles`, `binary-options.open-option`, `get-instruments` Forex, `place-order-temp` Forex, `get-positions` Forex e `close-position` Forex) são formatos reconstruídos a partir de clientes comunitários, não uma API de desenvolvedor publicada pela IQ Option.

## Abertura e fechamento de opções

A documentação comunitária de [`iqoptionapi`](https://lu-yi-hsun.github.io/iqoptionapi_pro/binary%20option/binary%20option/) descreve a abertura com `buy(...)`, usando `call`/`put`, e expõe `sell_option(order_ids)` para solicitar a venda de opções abertas. A implementação do wrapper pode ser vista em [`stable_api.py`](https://github.com/Lu-Yi-Hsun/iqoptionapi/blob/master/iqoptionapi/stable_api.py).

Uma implementação comunitária independente, [`LuKks/iqoption`](https://github.com/LuKks/iqoption), mostra o comando WebSocket `sell-options` com `options_ids: [option.id]`; seu exemplo bruto usa versão `3.0`. A própria página ressalva que o WebSocket não é documentado nem garantido. O conector não implementa nem expõe fechamento antecipado de opções binárias: abrir uma operação não prova que exista um endpoint de venda estável. O resultado final de opções é obtido por eventos recebidos do servidor durante a sessão.

## Candles e Forex

A mesma documentação comunitária descreve `get_candles(asset, interval, count, endtime)` e a abertura de opções binárias. Para Forex, a documentação comunitária de [`iqoptionapi` — Foreign Exchange Futures](https://iqoptionapi.github.io/iqoptionapi/en/fef/fef/) descreve ordens de margem com `buy_order` e fechamento por `close_position`. O conector restringe esse fluxo a instrumentos Forex, exige stop loss/take profit na abertura e verifica que a posição consta como aberta antes de pedir o fechamento. O ACK do pedido não confirma o P/L final; o conector só exibe o P/L atual de posições abertas.

## Alteração de stop loss e take profit após a abertura

A documentação comunitária [`docs/fef/fef.md`](https://github.com/Lu-Yi-Hsun/iqoptionapi/blob/master/docs/fef/fef.md) descreve `change_order(...)` e permite selecionar `ID_Name="position_id"` ou `"order_id"`. O wrapper [`stable_api.py`](https://github.com/Lu-Yi-Hsun/iqoptionapi/blob/master/iqoptionapi/stable_api.py) resolve o `position_id` com `get_order(...)` quando solicitado e encaminha a alteração ao módulo [`change_tpsl.py`](https://github.com/Lu-Yi-Hsun/iqoptionapi/blob/master/iqoptionapi/ws/chanels/change_tpsl.py). Esse módulo envia `change-tpsl` versão `2.0` com `position_id`, SL/TP e seus tipos; o cliente comunitário aguarda `tpsl-changed` e considera sucesso apenas quando o status é `2000`.

O conector agora espelha esse payload numa ferramenta arriscada que só atua sobre uma posição Forex listada como aberta na carteira selecionada e respeita limites de SL/TP. O sucesso significa apenas que o servidor retornou ACK `2000`; não há confirmação independente de que os valores persistiram na posição. É um protocolo privado não oficial, não testado aqui numa operação real com a corretora; deve ser validado primeiro em DEMO e não deve ser tratado como API pública estável.

## Fontes oficiais consultadas

A página oficial de [termos da IQ Option](https://iqoption.com/en/terms) apresenta avisos de risco e condições de uso, mas não documenta API pública, SDK, nomes de mensagens WebSocket nem payloads de abertura/fechamento. A documentação oficial pública consultada não permitiu confirmar suporte estável para automação por terceiros.

## Limite dos testes

Os testes unitários deste repositório verificam composição/parsing de mensagens e fluxos em memória com portas falsas, incluindo o ACK change-tpsl. Também foi feita uma verificação de rede somente leitura com a conta de teste autorizada: login e perfil foram confirmados, com carteiras DEMO e REAL; a carteira inicial era DEMO. Consultas Forex `get-positions` direcionadas explicitamente a cada carteira retornaram zero posições.

A troca global para REAL pelo comando WebSocket não recebeu confirmação em 10 s; o endpoint comunitário HTTP `/api/profile/changebalance` retornou 404. O código, portanto, seleciona localmente apenas um `balance_id` que já consta no perfil autenticado e roteia cada consulta/operação com esse ID; a resposta ao usuário deixa claro que isso não altera a carteira padrão no servidor. Consultas somente leitura a DEMO e REAL, cada qual com seu ID de perfil, retornaram zero posições. A consulta `get-instruments` v4.0 não trouxe o evento `instruments` esperado (somente `timeSync`). Por segurança, não foi enviado nenhum comando de abertura, fechamento ou ajuste Forex: nenhum ativo aberto foi confirmado. Não havia dispositivo/emulador Android disponível; o APK foi validado somente por build e testes locais. Nenhum desses resultados comprova suporte oficial ou compatibilidade estável da corretora.
