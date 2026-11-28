package dev.fluwy.app.agent

import dev.fluwy.app.browser.BrowserHost
import dev.fluwy.app.data.AgentProfile
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.KnowledgeItem
import dev.fluwy.app.data.LlmMessage
import dev.fluwy.app.data.PlanPhase
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.Section
import dev.fluwy.app.data.SectionTask
import dev.fluwy.app.data.TaskStatus
import dev.fluwy.app.data.newId
import dev.fluwy.app.data.SessionStatus
import dev.fluwy.app.data.Skill
import dev.fluwy.app.data.StepStatus
import dev.fluwy.app.data.Store
import dev.fluwy.app.files.AtomicTextFile
import dev.fluwy.app.llm.OpenRouter
import dev.fluwy.app.llm.LlmTruncationPolicy
import dev.fluwy.app.llm.ToolCall
import dev.fluwy.app.llm.bool
import dev.fluwy.app.llm.int
import dev.fluwy.app.llm.str
import dev.fluwy.app.llm.strArray
import dev.fluwy.app.llm.tool
import dev.fluwy.app.util.runCatchingCancellable
import dev.fluwy.app.sandbox.Sandbox
import dev.fluwy.app.trading.TradeCoordinator
import dev.fluwy.app.uiconnector.iqoption.IqTradeWaitDirective
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.fluwy.app.wallet.WalletAgentPolicyStore
import dev.fluwy.app.wallet.SolanaWalletRepository
import dev.fluwy.app.wallet.SolanaRpcClient
import dev.fluwy.app.wallet.SolanaNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale
import dev.fluwy.app.agent.AgentRunner.AgentTask
import dev.fluwy.app.agent.AgentRunner.CustomDefParserFence
import dev.fluwy.app.agent.AgentRunner.Delegation
import dev.fluwy.app.agent.AgentRunner.Delivered
import dev.fluwy.app.agent.AgentRunner.FileChange
import dev.fluwy.app.agent.AgentRunner.QueuedMessage
import dev.fluwy.app.agent.AgentRunner.SectionPermissions
import dev.fluwy.app.agent.AgentRunner.SquadTask
import dev.fluwy.app.agent.AgentRunner.ToolResult
import dev.fluwy.app.agent.AgentRunner.WorkerMerge
import dev.fluwy.app.agent.AgentRunner.WorkerOutcome
import dev.fluwy.app.agent.AgentRunner.LoopGuards.ACTION_INTENT
import dev.fluwy.app.agent.AgentRunner.LoopGuards.BROWSER_WAIT_FOREGROUND_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.CONTENT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.DELEGATION_STARTED
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_AUTO_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_EDIT_FAILS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PARALLEL_MEMBERS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PLAN_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MEMBER_SLOW_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_MAX_WAIT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_RETRY_HINT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.POST_COMPACTION_READS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.PURE_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STALE_SNAPSHOT_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STRICT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.hasActionIntent
import dev.fluwy.app.agent.AgentRunner.LoopGuards.memoizeConnectorBuilderCall
import dev.fluwy.app.agent.AgentRunner.LoopGuards.repeatToolThreshold
import dev.fluwy.app.agent.AgentRunner.LoopGuards.similar

/** Terminal, arquivos, páginas e web. Devolve null quando a ferramenta não é desta família. */
internal suspend fun AgentRunner.workspaceTools(inv: ToolInvocation): String? = with(inv) {
    when (call.name) {
        "shell" -> {
            // Guardrail determinístico: recusa invocações diretas de scripts inexistentes,
            // em vez de mandar bash executar um caminho alucinado e produzir ENOENT.
            val command = SecretVault.resolve(sessionId, s("command")).trim()
            val directScript = Regex("""(?:^|[;&|]\\s*)(?:bash|sh|python3?|node)\\s+(['"]?)(/[^\\s'";&|]+)""")
                .find(command)?.groupValues?.get(2)
            val missingScript = directScript?.let { path ->
                val f = sandbox.hostFile(path, sessionId)
                if (!f.exists() || !f.isFile) path else null
            }
            val badCd = Regex("""(?:^|[;&|]\\s*)cd\\s+(['"]?)(/[^\\s'";&|]+)""")
                .find(command)?.groupValues?.get(2)?.let { path ->
                    val f = sandbox.hostFile(path, sessionId)
                    if (!f.exists() || !f.isDirectory) path else null
                }
            val browserGuard = BrowserRedirect.guard(command)
            if (browserGuard != null) {
                browserGuard
            } else if (ShellCommandPolicy.isWholeSystemSearch(command)) {
                "GUARDRAIL: não vasculhe o sistema inteiro (/, /vendor, /system…): isso leva horas e não encontra os arquivos do agente. " +
                    "Tudo o que importa fica em /root/workspace" + (if (store.session(sessionId)?.sectionId != null) " (compartilhado por todos os agentes da seção)" else "") +
                    ". Use file_list ou `find /root/workspace -maxdepth 3`. Se o arquivo não estiver lá, ele não existe: diga o que falta e encerre a tarefa."
            } else if (missingScript != null) {
                "GUARDRAIL: o script '$missingScript' não existe neste workspace. Não execute novamente. Use file_list/ls para descobrir o caminho real ou file_write para criar o arquivo antes de executá-lo."
            } else if (badCd != null) {
                "GUARDRAIL: o diretório '$badCd' não existe neste workspace. Liste o diretório pai e escolha um caminho existente."
            } else {
            // tudo roda DENTRO do terminal da tarefa (visível ao vivo no Computador)
            // Servidor em primeiro plano prende o terminal e quebra a captura: roda em segundo plano.
            val autoBackground = !b("background") && ShellCommandPolicy.isLongRunningServer(command)
            val r = terminals.get(sessionId, cwd).runAgentCommand(command, (i("timeout") ?: 180).toLong(), b("background") || autoBackground)
            (if (autoBackground) "(Servidor detectado: iniciado em SEGUNDO PLANO para não travar o terminal. Teste com `curl -s http://127.0.0.1:PORTA/` e não inicie outro na mesma porta.)\n" else "") +
                ShellOutput.formatExecutionResult(r.output, r.exitCode, r.timedOut)
            }
        }
        "file_write" -> {
            val path = s("path").trim()
            val root = sandbox.workspaceFor(sessionId)
            val f = AgentFilePathPolicy.taskWorkspace(root, path)
            when {
                path.isBlank() -> "ERRO: informe path."
                f == null -> "ERRO: caminho fora do workspace da tarefa. Use um caminho relativo (ex.: output/index.html) ou /root/workspace/output/index.html; caminhos Android e absolutos externos não são aceitos."
                f.isDirectory -> "ERRO: $path é um diretório. Use um caminho de arquivo, ex.: $path/index.html"
                else -> {
                    var p = f.parentFile
                    while (p != null && !p.exists()) p = p.parentFile
                    if (p != null && p.isFile) "ERRO: ${p.name} existe como arquivo e não pode ser usado como pasta."
                    else {
                        f.parentFile?.mkdirs()
                        val target = AgentFilePathPolicy.taskWorkspace(root, path)
                        if (target == null) "ERRO: o destino deixou de estar confinado ao workspace; nada foi gravado."
                        else {
                            val append = a["append"]?.jsonPrimitive?.booleanOrNull == true
                            val content = SecretVault.resolve(sessionId, s("content"))
                            val beforeWrite = beforeChange(target)
                            if (append && target.exists()) target.appendText(content) else target.writeText(content)
                            recordChange(sessionId, target, beforeWrite, path)
                            noteFileSeen(sessionId, target)
                            val syntaxWarn = SyntaxCheck.problem(target.name, runCatching { target.readText() }.getOrNull())
                                ?.let { "\n⚠ O arquivo foi salvo, mas está com sintaxe inválida: $it. Corrija antes de usá-lo." }.orEmpty()
                            val lines = target.useLines { it.count() }
                            val preview = runCatching { target.useLines { seq -> seq.take(10).joinToString("\n") } }.getOrDefault("")
                            "OK: ${if (append) "conteúdo acrescentado" else "arquivo salvo"} (${target.relativeTo(root.canonicalFile).path}) — ${target.length()} bytes, $lines linhas.$syntaxWarn\n[Prévia das primeiras 10 linhas]\n$preview"
                        }
                    }
                }
            }
        }
        "file_edit" -> {
            val path = s("path").trim()
            if (path.isBlank()) "ERRO: informe path."
            else {
                val workspace = sandbox.workspaceFor(sessionId).canonicalFile
                val target = AgentFilePathPolicy.taskWorkspace(workspace, path)
                when {
                    target == null -> "ERRO: file_edit só aceita caminhos dentro do workspace da tarefa; caminhos Android e absolutos externos são recusados."
                    !target.isFile -> "ERRO: arquivo existente não encontrado: $path. Use file_list/file_read para conferir uma vez antes de editar."
                    else -> {
                        val oldText = SecretVault.resolve(sessionId, s("old_text"))
                        val newText = SecretVault.resolve(sessionId, s("new_text"))
                        val key = fileKey(sessionId, target)
                        val rel = target.relativeTo(workspace).path
                        // Duas falhas seguidas no mesmo arquivo: só volta a editar depois de reler (corta o mini-loop).
                        if ((editFails[key] ?: 0) >= MAX_EDIT_FAILS)
                            ("RECUSADO: $MAX_EDIT_FAILS edições seguidas falharam em $rel, então tentar de novo de memória não vai funcionar. " +
                                "Releia o trecho com file_read (o arquivo pode ter sido alterado por outro agente) e monte old_text copiando o texto lido. " +
                                "Para acrescentar conteúdo no fim do arquivo, use file_write com append=true em vez de file_edit.")
                        else if (oldText == newText) {
                            editFails.merge(key, 1, Int::plus)
                            "ERRO: old_text e new_text são iguais, então não há nada a alterar em $rel. Se o arquivo já está como você quer, siga adiante; " +
                                "se queria mudar algo, new_text precisa conter o texto novo."
                        } else {
                        val beforeEdit = beforeChange(target)
                        val result = AtomicTextFile.replace(target, oldText, newText, b("allow_multiple"))
                        // Como no SWE-agent: uma edição que deixa um arquivo estruturado inválido não passa.
                        val broke = if (result.success) SyntaxCheck.brokenBy(target.name, beforeEdit.second, runCatching { target.readText() }.getOrNull()) else null
                        if (broke != null && beforeEdit.second != null) {
                            runCatching { target.writeText(beforeEdit.second!!) }
                            return ("ERRO: a edição deixaria $rel com sintaxe inválida ($broke). Nada foi alterado. Corrija new_text (aspas, vírgulas, chaves) e tente de novo.")
                        }
                        if (result.success) recordChange(sessionId, target, beforeEdit, rel)
                        if (!result.success) {
                            val fails = editFails.merge(key, 1, Int::plus) ?: 1
                            val changed = fileSeen[key]?.let { seen -> target.lastModified() > seen + 500 } == true
                            "ERRO: ${result.error ?: "substituição recusada"}" +
                                (if (changed) "\nO arquivo foi modificado depois da sua última leitura (outro agente, um comando ou uma gravação sua escreveu nele): o texto que você tem de memória está desatualizado. Releia com file_read antes de editar."
                                    else if (fileSeen[key] == null) "\nVocê ainda não leu este arquivo nesta tarefa: leia o trecho com file_read e copie old_text do texto lido." else "") +
                                (if (fails >= MAX_EDIT_FAILS) "\nEsta foi a ${fails}ª falha seguida neste arquivo: a próxima edição só será aceita depois de um file_read dele." else "") +
                                runCatching { EditHint.nearest(target.readText(), oldText) }.getOrDefault("").let { if (it.isBlank()) "" else "\n$it" }
                        }
                        else {
                            editFails.remove(key); noteFileSeen(sessionId, target)
                            val mode = if (result.strategy == "exact") "literal" else "whitespace"
                            "OK: ${target.relativeTo(workspace).path}: ${result.replacements} ocorrência(s) substituída(s) ($mode)."
                        }
                        }
                    }
                }
            }
        }
        "file_read" -> {
            val path = s("path").trim()
            // Além do workspace: leitura em ~/ e /root/ do sandbox desta conversa (skills instalam apps em ~/pasta).
            val target = projectRelative(sandbox.workspaceFor(sessionId), path, AgentFilePathPolicy.readableWorkspace(
                sandbox.workspaceFor(sessionId), sandbox.connectorsDir, path,
            ) ?: sandboxHomeFile(sessionId, path))
            if (target == null) "ERRO: caminho inválido (recebido: \"${path.take(160)}\"). Use caminho relativo ao workspace ou /root/workspace/...; para conector, informe o ID completo em /root/connectors/<id>/workspace/.... Não tente caminhos absolutos Android ou caminhos alternativos fora dessas raízes."
            else {
                noteFileSeen(sessionId, target)  // leitura feita: libera novas edições e marca a versão vista
                val archiveRefusal = if (ContextArchive.isArchivePath(path) && target.exists() && !b("stale_ok"))
                    archiveReads.check(sessionId, taskKey(sessionId), target.name, target.length()) else null
                val staleMin = ctxMgr.staleBrowserSnapshotMinutes(path)
                // Continuação de uma saída recém-cortada (mesmo passo de trabalho) é leitura atual, não releitura.
                if (archiveRefusal != null) archiveRefusal
                else if (staleMin != null && staleMin >= STALE_SNAPSHOT_MINUTES && !b("stale_ok"))
                    "RECUSADO: este arquivo é um snapshot ANTIGO do browser (capturado há $staleMin min) e a página pode ter mudado. " +
                        "Para o estado atual, use browser_read (ou browser_navigate, se precisar reabrir). " +
                        "Só releia este snapshot se o usuário ou o coordenador pediu exatamente esse conteúdo antigo — nesse caso repita com stale_ok=true."
                else readCache(sessionId, target, i("offset") ?: 1, i("limit") ?: 250) ?: (if (staleMin != null && staleMin >= STALE_SNAPSHOT_MINUTES) "[Snapshot antigo do browser, capturado há $staleMin min — pode não refletir a página atual]\n" else "") +
                    readFileChunk(target, path, i("offset") ?: 1, i("limit") ?: 250)
            }
        }
        "file_search" -> {
            val root = sandbox.workspaceFor(sessionId)
            val p = s("path").trim()
            val start = if (p.isBlank()) root else projectRelative(root, p, AgentFilePathPolicy.readableWorkspace(root, sandbox.connectorsDir, p, allowRoot = true))
            if ((start == null || !start.exists()) && ContextArchive.isArchivePath(p)) ContextArchive.MISSING
            else if (start == null || !start.exists()) "ERRO: caminho não existe: $p. Omita path para buscar no workspace inteiro."
            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FileSearch.search(root, start, s("pattern"), b("regex"), (i("max") ?: 50).coerceIn(5, 200)) }
        }
        "page_create" -> {
            val by = store.agent(store.session(sessionId)?.agentId)?.name ?: "Agente"
            fun arr(k: String) = (a[k] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.orEmpty()
            // Uma página por conversa/seção: se já existe, nunca cria outra.
            val dup = store.pagesIn(pageSpace(sessionId)).minByOrNull { it.createdAt }
            if (dup != null && store.session(sessionId)?.sectionId != null)
                "RECUSADO: esta seção trabalha numa única página compartilhada: \"${dup.title}\" (${dup.id}, ${dup.kind}). Todos os agentes atualizam essa mesma página: " +
                    "leia com page_read(\"${dup.id}\") e mude só a sua parte com page_update(\"${dup.id}\", ops=[...]) — por exemplo uma seção com o seu nome (replace_section/append_markdown) ou os seus cartões."
            else if (dup != null) "RECUSADO: esta conversa já tem a sua única página: \"${dup.title}\" (${dup.id}, ${dup.kind}). Leia com page_read e altere com page_update(page_id=\"${dup.id}\"). " +
                "Para outro formato (ex.: o usuário pediu um kanban) use a op set_kind nessa mesma página; para outro tema, rename + o novo conteúdo."
            else dev.fluwy.app.pages.PageOps.create(pageSpace(sessionId), s("title"), s("kind"), by, s("markdown"), s("html"), arr("columns"), arr("headers")).fold({ p ->
                if (store.pagesIn(p.spaceId).size >= 60) "RECUSADO: esta conversa já tem 60 páginas; atualize uma existente." else {
                    store.upsertPage(p.copy(creatorAgentId = store.session(sessionId)?.agentId))
                    store.addItem(sessionId, ChatItem.Step(tool = "page", title = "Página criada: ${p.title}", args = p.id, result = p.kind, status = StepStatus.OK))
                    "Criada: ${p.id} (${p.kind}). O usuário já pode abrir em Pages. Atualize com page_update(page_id=\"${p.id}\", ops=[...])."
                }
            }, { "ERRO: ${it.message}" })
        }
        "page_update" -> {
            val by = store.agent(store.session(sessionId)?.agentId)?.name ?: "Agente"
            val ops = (a["ops"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                ?: runCatching { kotlinx.serialization.json.Json.parseToJsonElement(s("ops")).jsonArray.map { it.jsonObject } }.getOrNull()
            val id = s("page_id")
            if (ops.isNullOrEmpty()) "ERRO: envie ops como lista de objetos, ex.: [{\"op\":\"move_card\",\"card_id\":\"c_1\",\"column\":\"Feito\"}]."
            else if (store.page(id) == null) "ERRO: página \"$id\" não existe. Páginas desta conversa: " +
                store.pagesIn(pageSpace(sessionId)).joinToString { "${it.id} (\"${it.title}\")" }.ifBlank { "nenhuma" }
            else store.applyPageOps(id, ops, by).fold({ p ->
                val last = store.session(sessionId)?.items?.lastOrNull() as? ChatItem.Step
                if (last?.tool == "page" && last.args == p.id) store.replaceItem(sessionId, last.copy(title = "Página atualizada: ${p.title}", result = "versão ${p.version}"))
                else store.addItem(sessionId, ChatItem.Step(tool = "page", title = "Página atualizada: ${p.title}", args = p.id, result = "versão ${p.version}", status = StepStatus.OK))
                "OK: ${p.title} agora na versão ${p.version} (${ops.size} operação(ões))."
            }, { "ERRO: ${it.message}" })
        }
        "page_read" -> (store.page(s("page_id")) ?: dev.fluwy.app.pages.PageOps.sameTitle(store.state.value.pages, pageSpace(sessionId), s("page_id")))
            ?.let { dev.fluwy.app.pages.PageOps.render(it) }
            ?: ("ERRO: página \"${s("page_id")}\" não existe. Páginas desta conversa: " +
                store.pagesIn(pageSpace(sessionId)).joinToString { "${it.id} (\"${it.title}\")" }.ifBlank { "nenhuma — crie com page_create" })
        "page_list" -> store.pagesIn(pageSpace(sessionId)).sortedByDescending { it.updatedAt }.joinToString("\n") { p ->
            "${p.id} · ${p.kind} · \"${p.title}\" · v${p.version} · ${p.cards.size} cartões"
        }.ifBlank { "Nenhuma página ainda. Crie com page_create." }
        "web_search" -> dev.fluwy.app.web.WebTools.search(s("query"), i("max") ?: 8).let { r ->
            // Busca direta bloqueada/vazia: abre a busca no browser na hora, sem o agente precisar tentar de novo.
            if (r.startsWith("ERRO") || r.startsWith("Nenhum resultado na busca")) browserFallback(sessionId, s("query"), r, 9_000) else r
        }
        "web_fetch" -> {
            val url = SecretVault.resolve(sessionId, s("url")).trim()
            val max = (i("max_chars") ?: 12_000).coerceIn(2_000, 40_000)
            dev.fluwy.app.web.WebTools.fetch(url, max).let { r ->
                // Erro ou página que depende de JS/login: segue automaticamente pelo browser.
                val missing = Regex("^ERRO: HTTP (404|410)\\b").containsMatchIn(r)
                val failed = (r.startsWith("ERRO") && !r.startsWith("ERRO: informe") && !missing) || r.contains("[Pouco texto extraído")
                if (failed) browserFallback(sessionId, url, r, max.coerceAtMost(20_000)) else r
            }
        }
        "file_list" -> {
            val path = s("path").trim()
            val root = sandbox.workspaceFor(sessionId)
            val d = if (path.isBlank()) root else projectRelative(root, path, AgentFilePathPolicy.readableWorkspace(
                root, sandbox.connectorsDir, path, allowRoot = true,
            ) ?: sandboxHomeFile(sessionId, path))
            fun listing(dir: java.io.File) = dir.walkTopDown().maxDepth(3).filter { it != dir && !it.path.contains("/.fluwy/") && it.name != ".fluwy" }.take(300)
                .joinToString("\n") { (if (it.isDirectory) "📁 " else "📄 ") + it.relativeTo(dir).path + if (it.isFile) " (${it.length()}b)" else "" }
                .ifBlank { "(vazio)" }
            when {
                // Caminho fora do workspace: em vez de um erro que leva a novas tentativas, mostra a raiz real.
                d == null -> "AVISO: '$path' está fora do workspace (só /root/workspace e /root/connectors/<id>/workspace são listáveis). " +
                    "Conteúdo de /root/workspace — use estes caminhos relativos:\n" + listing(root)
                !d.exists() || !d.isDirectory -> "ERRO: diretório não existe: $path. Conteúdo de /root/workspace:\n" + listing(root)
                else -> relNote(root, path, d) + listing(d)
            }
        }
        else -> null
    }
}
