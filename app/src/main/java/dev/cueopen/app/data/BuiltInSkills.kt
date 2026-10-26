package dev.cueopen.app.data

/** Skills equivalentes aos cards "Get started" do Cue (slides, website, wide research...). */
object BuiltInSkills {
    val slides = Skill(
        id = "skill-slides", builtIn = true, icon = "presentation",
        name = "Slides",
        description = "Cria apresentações em HTML (uma página por slide) prontas para apresentar ou exportar em PDF.",
        instructions = """
            Quando o usuário pedir uma apresentação:
            1. Pesquise o tema (browser) se precisar de fatos atuais.
            2. Crie o diretório slides/ no workspace com index.html autocontido (HTML+CSS, sem CDN),
               1280x720 por slide, navegação por setas, fonte legível, um tema visual coeso.
            3. Use no máximo 6 bullets por slide e inclua um slide de título e um de conclusão.
            4. Abra o resultado no browser (file://) para conferir e entregue o caminho do arquivo.
        """.trimIndent(),
    )
    val website = Skill(
        id = "skill-website", builtIn = true, icon = "globe",
        name = "Website",
        description = "Constrói sites e landing pages e serve localmente no sandbox para pré-visualização.",
        instructions = """
            Para sites: crie a pasta site/ no workspace com index.html, styles.css e script.js.
            Design responsivo mobile-first. Depois sirva com `python3 -m http.server 8080 -d site &`
            e abra http://127.0.0.1:8080 no browser para verificar. Corrija o que estiver quebrado.
        """.trimIndent(),
    )
    val wideResearch = Skill(
        id = "skill-wide-research", builtIn = true, icon = "search",
        name = "Wide Research",
        description = "Pesquisa ampla em muitas fontes com tabela comparativa e relatório com fontes.",
        instructions = """
            Para pesquisa ampla: liste os itens a investigar no plano, visite pelo menos uma fonte
            por item com o browser (use duckduckgo.com/html/?q=... para buscar), anote os dados em
            research/notes.md e entregue research/report.md com tabela comparativa e lista de fontes (URLs).
        """.trimIndent(),
    )
    val playbook = Skill(
        id = "skill-playbook", builtIn = true, icon = "book",
        name = "Playbook",
        description = "Transforma um processo em um passo a passo reutilizável (checklist + automação).",
        instructions = """
            Para playbooks: descreva objetivo, gatilhos, passos numerados, responsáveis e critérios de
            conclusão em playbook.md. Se algum passo puder ser automatizado, crie o script em scripts/.
        """.trimIndent(),
    )
    val mobileApp = Skill(
        id = "skill-mobile-app", builtIn = true, icon = "phone",
        name = "Mobile app",
        description = "Prototipa um app mobile como PWA instalável e testa no browser.",
        instructions = """
            Para apps mobile: crie app/ com index.html, manifest.json e service-worker.js (PWA).
            Layout 390px de largura, navegação por abas, estado em localStorage. Sirva na porta 8081 e abra no browser.
        """.trimIndent(),
    )
    val image = Skill(
        id = "skill-image", builtIn = true, icon = "image",
        name = "Criar imagem",
        description = "Gera ilustrações vetoriais (SVG) e as converte em PNG no sandbox.",
        instructions = """
            Para imagens: escreva um SVG detalhado em images/<nome>.svg. Se o usuário quiser PNG,
            instale `apk add rsvg-convert` (pacote librsvg) e converta. Mostre o resultado abrindo o arquivo no browser.
        """.trimIndent(),
    )
    val skillCreator = Skill(
        id = "skill-creator", builtIn = true, icon = "wand",
        name = "Skill creator",
        description = "Cria novas skills a partir de uma conversa, salvando-as na sua biblioteca.",
        instructions = """
            Quando o usuário quiser criar uma skill: entreviste brevemente (o que faz, quando usar, passos),
            depois chame a ferramenta create_skill com nome, descrição curta e instruções detalhadas.
        """.trimIndent(),
    )

    val contextEfficiency = Skill(
        id = "skill-context-efficiency", builtIn = true, icon = "gauge",
        name = "Context Efficiency",
        description = "Reduz tokens e latência: contexto mínimo suficiente, leitura sob demanda, compactação segura e respostas enxutas sem perder requisitos.",
        instructions = """
            Objetivo: minimizar tokens de entrada/saída e chamadas sem sacrificar correção, segurança ou requisitos do usuário.
            Regras operacionais:
            1. Antes de ler ou chamar ferramentas, formule internamente a pergunta específica que precisa responder; não explore o repositório inteiro por padrão.
            2. Prefira busca por símbolos/termos e trechos de arquivo relevantes. Leia intervalos pequenos; amplie somente se faltar contexto.
            3. Para saídas grandes, use filtros, limites e resumos estruturados. Não repita no prompt conteúdo já presente; referencie caminho, símbolo e linhas quando possível.
            4. Mantenha separados: requisitos, decisões, estado atual, evidências e pendências. Preserve nomes, caminhos, valores, erros e decisões que afetam a execução.
            5. Use microcompactação para saídas antigas de ferramentas, mas nunca remova a única evidência necessária para uma decisão nem quebre pares tool_call/tool.
            6. Antes de compactar, preserve a solicitação original, critérios de aceitação, decisões, alterações feitas, testes/resultados e próximos passos. Se o resumo falhar, informe a perda de fidelidade; não declare que o histórico foi preservado integralmente.
            7. Não alegue economia percentual sem medir tokens reais. Diferencie estimativas locais de usage retornado pelo provedor.
            8. Mantenha respostas finais concisas e completas; não descarte detalhes que o usuário explicitamente pediu.
            9. Não carregue skills completas, arquivos, notas ou knowledge sem relevância para a tarefa. Use divulgação progressiva.
            10. Para alterações de código, leia primeiro o trecho mínimo necessário, implemente a menor mudança coerente e execute testes direcionados antes de ampliar.
        """.trimIndent(),
    )
    val delegateReading = Skill(
        id = "skill-delegate-reading", builtIn = true, icon = "file-search",
        name = "Delegate Reading",
        description = "Delegação de leitura e pesquisa: divide inspeção de arquivos/fontes em subtarefas delimitadas e retorna apenas achados verificáveis.",
        instructions = """
            Use delegação de leitura quando houver vários arquivos, fontes independentes ou uma investigação ampla que possa ocorrer em paralelo. Não delegue uma leitura curta nem crie agentes apenas para parecer eficiente.
            Para cada subtarefa, especifique: pergunta exata, escopo/arquivos permitidos, termos/símbolos a procurar, limites de leitura, formato de saída e o que não investigar.
            Peça ao leitor delegado que:
            - busque primeiro por símbolos, cabeçalhos e ocorrências exatas;
            - leia apenas os intervalos necessários e expanda se houver dependências;
            - retorne um resumo curto com conclusões, evidências (caminho e linhas/URLs), incertezas e perguntas não respondidas;
            - não copie arquivos completos, logs inteiros nem conteúdo que não sustente a conclusão;
            - não altere arquivos nem faça commits quando a tarefa é somente leitura.
            Integre resultados removendo duplicatas e resolvendo contradições com leitura direta da fonte. Trate resultados delegados como pistas, não como verdade automática; verifique evidências importantes antes de modificar código.
            Se não houver ferramenta de delegação disponível, aplique o mesmo protocolo sequencialmente e não afirme que um subagente foi executado.
            Nunca delegue credenciais, segredos ou dados pessoais desnecessários. Escopo mínimo e saída limitada são obrigatórios.
        """.trimIndent(),
    )
    val all = listOf(slides, website, wideResearch, playbook, mobileApp, image, skillCreator, contextEfficiency, delegateReading)
}
