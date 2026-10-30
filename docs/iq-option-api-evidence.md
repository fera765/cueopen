# IQ Option: métodos encontrados e limites da evidência

## O que o projeto usa

O conector do repositório implementa Kotlin próprio sobre login HTTP e WebSocket; não depende do SDK `iqoptionapi`. As mensagens expostas (`get-initialization-data`, `get-candles`, `binary-options.open-option`, `get-instruments` Forex, `place-order-temp` Forex, `get-positions` Forex e `close-position` Forex) são formatos reconstruídos a partir de clientes comunitários, não uma API de desenvolvedor publicada pela IQ Option.

## Abertura e fechamento de opções

A documentação comunitária de [`iqoptionapi`](https://lu-yi-hsun.github.io/iqoptionapi_pro/binary%20option/binary%20option/) descreve a abertura com `buy(...)`, usando `call`/`put`, e expõe `sell_option(order_ids)` para solicitar a venda de opções abertas. A implementação do wrapper pode ser vista em [`stable_api.py`](https://github.com/Lu-Yi-Hsun/iqoptionapi/blob/master/iqoptionapi/stable_api.py).

Uma implementação comunitária independente, [`LuKks/iqoption`](https://github.com/LuKks/iqoption), mostra o comando WebSocket `sell-options` com `options_ids: [option.id]`; seu exemplo bruto usa versão `3.0`. A própria página ressalva que o WebSocket não é documentado nem garantido. O conector não implementa nem expõe fechamento antecipado de opções binárias: abrir uma operação não prova que exista um endpoint de venda estável. O resultado final de opções é obtido por eventos recebidos do servidor durante a sessão.

## Candles e Forex

A mesma documentação comunitária descreve `get_candles(asset, interval, count, endtime)` e a abertura de opções binárias. Para Forex, a documentação comunitária de [`iqoptionapi` — Foreign Exchange Futures](https://iqoptionapi.github.io/iqoptionapi/en/fef/fef/) descreve ordens de margem com `buy_order` e fechamento por `close_position`. O conector restringe esse fluxo a instrumentos Forex, exige stop loss/take profit na abertura e verifica que a posição consta como aberta antes de pedir o fechamento. O ACK do pedido não confirma o P/L final; o conector só exibe o P/L atual de posições abertas.

## Fontes oficiais consultadas

A página oficial de [termos da IQ Option](https://iqoption.com/en/terms) apresenta avisos de risco e condições de uso, mas não documenta API pública, SDK, nomes de mensagens WebSocket nem payloads de abertura/fechamento. A documentação oficial pública consultada não permitiu confirmar suporte estável para automação por terceiros.

## Limite dos testes

Os testes deste repositório verificam composição/parsing de mensagens e fluxos em memória com portas falsas. Não fazem login, não conectam à conta IQ Option e não enviam operações à corretora. A compatibilidade efetiva e a aceitação de ordens exigiriam autorização e verificação posterior numa conta apropriada; não se deve inferir isso dos testes locais.
