# Compatibilidade de modelos e avaliação de sinais

## OpenCode Go

O endpoint informado para modelos que usam OpenAI Chat Completions é `https://opencode.ai/inference/go/openai/v1`; o protocolo de chat fica em `/chat/completions`. O catálogo Go, porém, é servido na rota irmã `https://opencode.ai/inference/go/v1/models`, e não necessariamente em `{baseUrl}/models`. A API documenta autenticação Bearer para modelos pagos e informa que modelos gratuitos de chat podem dispensá-la. O catálogo agrega modelos de famílias de API diferentes: alguns usam Chat Completions, outros Responses, Anthropic Messages ou Gemini. Portanto, descobrir o ID no catálogo não prova que ele funcione com o endpoint OpenAI Chat Completions ou com chamadas de ferramentas.

O app usa Chat Completions nesse campo e, por isso, oculta as famílias incompatíveis que a tabela oficial identifica (Responses, Anthropic Messages e Gemini). A própria lista Go não informa se cada modelo suporta function calling; isso continua dependendo do modelo/provedor.

Fontes oficiais: [OpenCode Go — endpoints e catálogo](https://opencode.ai/v2/docs/console/go/) e [OpenCode Inference API — famílias de endpoint e autenticação](https://opencode.ai/v2/docs/console/api/inference/).

## TypeSafe Jev e OpenRouter

Jev/System One não é uma API de geração de texto `chat/completions`. O formato nativo recebe `model`, `state` e perguntas nomeadas em `questions`; respostas usam o nome de cada pergunta e um tipo de decisão. Para perguntas `noul`, Jev retorna uma probabilidade entre 0 e 1. A API TypeSafe publica `POST /v1/systemone` e `GET /v1/models`. No OpenRouter, a rota documentada é `POST https://openrouter.ai/api/v1/systemone`, autenticada com a chave OpenRouter; Jev aceita ali perguntas tipadas e retorna as decisões sem convertê-las em prosa.

Fontes oficiais: [TypeSafe System One API](https://docs.typesafe.ai/api/system-one), [TypeSafe models e aliases](https://docs.typesafe.ai/models) e [OpenRouter — enviar requisição System One](https://openrouter.ai/docs/api/api-reference/systemone/submit-a-system-one-request).

## LLMs comuns como avaliadores

Quando o avaliador selecionado é um modelo comum de chat, o app pede JSON com IDs, acurácia, confiança e justificativa curta. A saída só é aceita se contiver um resultado numérico válido para cada sinal, sem duplicatas ou campos fora de faixa, e o app exige dados de evidência e confiança mínima de 70% antes de criar uma autorização local. Essa adaptação torna o formato verificável por código; ela não reproduz treinamento, calibração ou probabilidades internas do Jev. A confiança declarada por um LLM comum também não passa a ser calibrada por causa do schema. Falhas de rede, evidência ausente, resposta incompleta/malformada ou baixa confiança não liberam aprovação.

Fontes de referência: [TypeSafe System One API](https://docs.typesafe.ai/api/system-one) e [OpenRouter — guia Jev](https://openrouter.ai/docs/guides/community/jev), que descreve Jev como um modelo de decisões tipadas, não um substituto direto para geração de texto.
