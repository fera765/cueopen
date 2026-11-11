package dev.cueopen.app.data

/** Skills equivalentes aos cards "Get started" do Cue (slides, website, wide research...). */
object BuiltInSkills {
    val slides = Skill(
        id = "skill-slides", builtIn = true, icon = "presentation",
        name = "Slides",
        description = "Cria apresentações em HTML (uma página por slide) prontas para apresentar ou exportar em PDF.",
        instructions = """
            Quando o usuário pedir uma apresentação:
            1. Se o tema depender de fatos atuais, pesquise antes no browser e anote as fontes.
            2. Crie a pasta slides/ no workspace com um index.html autocontido (HTML+CSS, sem CDN):
               um slide por tela em 1280x720, navegação pelas setas, fonte legível e um tema visual coeso.
            3. Uma ideia por slide, com no máximo 6 bullets; inclua um slide de título e um de conclusão.
            4. Abra o resultado no browser (file://), confira se todos os slides aparecem bem e entregue o caminho do arquivo com show_file.
        """.trimIndent(),
    )
    val website = Skill(
        id = "skill-website", builtIn = true, icon = "globe",
        name = "Website",
        description = "Constrói sites e landing pages e serve localmente no sandbox para pré-visualização.",
        instructions = """
            Para sites: crie a pasta site/ no workspace com index.html, styles.css e script.js, em design
            responsivo mobile-first. Depois sirva com `python3 -m http.server 8080 -d site &`, abra
            http://127.0.0.1:8080 no browser, confira layout, links e console, e corrija o que estiver quebrado antes de entregar.
        """.trimIndent(),
    )
    val wideResearch = Skill(
        id = "skill-wide-research", builtIn = true, icon = "search",
        name = "Wide Research",
        description = "Pesquisa ampla em muitas fontes com tabela comparativa e relatório com fontes.",
        instructions = """
            Para pesquisa ampla: liste no plano os itens a investigar e visite pelo menos uma fonte por item
            com o browser (busque em duckduckgo.com/html/?q=...). Anote em research/notes.md cada dado junto com a URL de origem
            e entregue research/report.md com tabela comparativa, conclusões e lista de fontes; registre apenas o que as fontes dizem.
        """.trimIndent(),
    )
    val playbook = Skill(
        id = "skill-playbook", builtIn = true, icon = "book",
        name = "Playbook",
        description = "Transforma um processo em um passo a passo reutilizável (checklist + automação).",
        instructions = """
            Para playbooks: escreva em playbook.md o objetivo, os gatilhos, os passos numerados, os responsáveis e os critérios de
            conclusão. Se algum passo puder ser automatizado, crie o script em scripts/ e cite-o no passo correspondente.
        """.trimIndent(),
    )
    val mobileApp = Skill(
        id = "skill-mobile-app", builtIn = true, icon = "phone",
        name = "Mobile app",
        description = "Prototipa um app mobile como PWA instalável e testa no browser.",
        instructions = """
            Para apps mobile: crie app/ com index.html, manifest.json e service-worker.js (PWA instalável), com layout de
            390px de largura, navegação por abas e estado em localStorage. Sirva na porta 8081, abra no browser e teste o fluxo principal.
        """.trimIndent(),
    )
    val image = Skill(
        id = "skill-image", builtIn = true, icon = "image",
        name = "Criar imagem",
        description = "Gera ilustrações vetoriais (SVG) e as converte em PNG no sandbox.",
        instructions = """
            Para imagens: escreva um SVG detalhado em images/<nome>.svg. Se o usuário quiser PNG, instale o conversor com
            `apk add rsvg-convert` (pacote librsvg) e converta. Mostre o resultado ao usuário com show_file.
        """.trimIndent(),
    )
    val skillCreator = Skill(
        id = "skill-creator", builtIn = true, icon = "wand",
        name = "Skill creator",
        description = "Cria novas skills a partir de uma conversa, salvando-as na sua biblioteca.",
        instructions = """
            Quando o usuário quiser criar uma skill: faça uma entrevista breve (o que ela faz, quando usar, quais os passos)
            e então chame create_skill com nome, descrição curta (quando usar) e instruções detalhadas, escritas como passo a passo.
        """.trimIndent(),
    )

    val contextEfficiency = Skill(
        id = "skill-context-efficiency", builtIn = true, icon = "gauge",
        name = "Context Efficiency",
        description = "Reduz tokens e latência: contexto mínimo suficiente, leitura sob demanda, compactação segura e respostas enxutas sem perder requisitos.",
        instructions = """
            Objetivo: reduzir tokens de entrada e saída e o número de chamadas, sem abrir mão de correção, segurança ou requisitos do usuário.
            Como trabalhar:
            1. Antes de ler ou chamar ferramentas, formule a pergunta específica que precisa responder; explorar o repositório inteiro não é o ponto de partida.
            2. Prefira buscar por símbolos e termos e ler os trechos relevantes. Leia intervalos pequenos e amplie apenas se faltar contexto.
            3. Para saídas grandes, use filtros, limites e resumos estruturados. Em vez de repetir conteúdo que já está no contexto, referencie caminho, símbolo e linhas.
            4. Mantenha separados requisitos, decisões, estado atual, evidências e pendências. Preserve nomes, caminhos, valores, erros e decisões que afetam a execução.
            5. Use microcompactação nas saídas antigas de ferramentas, mas mantenha a única evidência que sustenta uma decisão e os pares tool_call/tool intactos.
            6. Antes de compactar, preserve a solicitação original, os critérios de aceitação, as decisões, as alterações feitas, os testes e resultados e os próximos passos. Se o resumo falhar, informe a perda de fidelidade em vez de declarar que o histórico foi preservado na íntegra.
            7. Cite economia percentual apenas quando tiver medido tokens reais, e diferencie estimativas locais do usage retornado pelo provedor.
            8. Mantenha as respostas finais concisas e completas: o que o usuário pediu explicitamente fica.
            9. Carregue skills completas, arquivos, notas e knowledge somente quando forem relevantes para a tarefa (divulgação progressiva).
            10. Em alterações de código, leia primeiro o trecho mínimo necessário, faça a menor mudança coerente e rode testes direcionados antes de ampliar.
        """.trimIndent(),
    )
    val delegateReading = Skill(
        id = "skill-delegate-reading", builtIn = true, icon = "file-search",
        name = "Delegate Reading",
        description = "Delegação de leitura e pesquisa: divide inspeção de arquivos/fontes em subtarefas delimitadas e retorna apenas achados verificáveis.",
        instructions = """
            Delegue leitura quando houver vários arquivos, fontes independentes ou uma investigação ampla que possa correr em paralelo. Uma leitura curta você mesmo faz: criar agentes só para parecer eficiente gasta mais do que economiza.
            Para cada subtarefa, especifique: a pergunta exata, o escopo e os arquivos permitidos, os termos ou símbolos a procurar, os limites de leitura, o formato de saída e o que fica fora da investigação.
            Peça ao leitor delegado que:
            - busque primeiro por símbolos, cabeçalhos e ocorrências exatas;
            - leia apenas os intervalos necessários, ampliando quando houver dependências;
            - devolva um resumo curto com conclusões, evidências (caminho e linhas ou URLs), incertezas e perguntas sem resposta;
            - deixe de fora arquivos completos, logs inteiros e conteúdo que não sustente a conclusão;
            - não altere arquivos nem faça commits quando a tarefa for somente de leitura.
            Ao integrar os resultados, remova duplicatas e resolva contradições lendo a fonte diretamente. Resultados delegados são pistas, não verdade automática: confira as evidências importantes antes de modificar código.
            Se não houver ferramenta de delegação disponível, aplique o mesmo protocolo em sequência e não afirme que um subagente foi executado.
            Credenciais, segredos e dados pessoais desnecessários não entram nas subtarefas. Trabalhe sempre com escopo mínimo e saída limitada.
        """.trimIndent(),
    )
    val all = listOf(slides, website, wideResearch, playbook, mobileApp, image, skillCreator, contextEfficiency, delegateReading)
}
