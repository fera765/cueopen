package dev.cueopen.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import coil.compose.AsyncImage
import dev.cueopen.app.CueApp
import dev.cueopen.app.connectors.ActivepiecesHub
import dev.cueopen.app.connectors.HubApi
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

/**
 * Catálogo de conectores do Activepieces (verificados no CI), com login feito aqui no app
 * (OAuth, chave de API, usuário/senha ou campos próprios).
 */
@Composable
fun HubCatalogScreen(nav: NavController) {
    val c = Cue.c
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var pieces by remember { mutableStateOf(HubApi.cachedPieces()) }
    var loading by remember { mutableStateOf(pieces.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<HubApi.PieceSummary?>(null) }

    LaunchedEffect(query) {
        delay(if (query.isBlank()) 0 else 350)
        loading = pieces.isEmpty(); error = null
        runCatching { HubApi.pieces(query) }.onSuccess { pieces = it }.onFailure { error = it.message }
        loading = false
    }

    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Adicionar conector", { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!ActivepiecesHub.installed()) item {
                Text(if (ActivepiecesHub.sandboxReady()) "Os conectores ainda não foram instalados: ao conectar o primeiro app, o app instala sozinho (~50 MB)." else "Instale o Linux (Alpine) do app em Configurações › Sandbox para usar os conectores.",
                    fontSize = 12.sp, color = c.ink3)
            }
            item {
                CueField(query, { query = it }, "Buscar entre ${if (pieces.size > 50 && query.isBlank()) "${pieces.size}+" else "os"} conectores")
            }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) } }
            error?.let { e -> item { Text(e, color = c.err, fontSize = 13.sp) } }
            items(pieces, key = { it.name }) { p ->
                CueCard(onClick = { picked = p }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(p.logoUrl, p.displayName, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.surface2))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName, fontWeight = FontWeight.SemiBold)
                            Text(p.description, fontSize = 12.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${p.actions} ações · " + authLabel(p.authTypes), fontSize = 11.sp, color = c.ink3)
                        }
                    }
                }
            }
        }
    }
    picked?.let { p ->
        ConnectPieceDialog(p, onDone = { msg ->
            picked = null
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            // O agente passa a ver a conta nova na hora (inventário atualizado).
            scope.launch { runCatching { dev.cueopen.app.connectors.HubConnectors.syncConnector(CueApp.instance.store) } }
        }, onDismiss = { picked = null })
    }
}

private fun authLabel(types: List<String>) = when {
    types.isEmpty() -> "sem login"
    types.any { it == "OAUTH2" } -> "login com a conta"
    types.any { it == "SECRET_TEXT" } -> "chave de API"
    types.any { it == "BASIC_AUTH" } -> "usuário e senha"
    else -> "dados de acesso"
}

/** Formulário de conexão gerado a partir do "auth" do próprio conector (nada predefinido no app). */
@Composable
private fun ConnectPieceDialog(p: HubApi.PieceSummary, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Cue.c
    val scope = rememberCoroutineScope()
    var meta by remember { mutableStateOf<JsonObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var authIdx by remember { mutableStateOf(0) }
    val values = remember { mutableStateMapOf<String, String>() }
    var name by remember { mutableStateOf(p.displayName) }
    var cloudClient by remember { mutableStateOf<String?>(null) }
    var ownRedirect by remember { mutableStateOf("") }
    var oauth by remember { mutableStateOf<Pair<String, String>?>(null) }  // (url, redirect)
    var verifier by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(p.name) {
        runCatching { HubApi.piece(p.name) }.onSuccess { meta = it }.onFailure { err = it.message }
        cloudClient = runCatching { HubApi.cloudClientId(p.name) }.getOrNull()
    }
    val auths = HubApi.authList(meta?.get("auth"))
    val auth = auths.getOrNull(authIdx)
    val type = auth?.s("type").orEmpty()
    val useCloud = type == "OAUTH2" && cloudClient != null
    LaunchedEffect(type, useCloud) { if (type == "OAUTH2" && !useCloud) ownRedirect = runCatching { HubApi.ownRedirectUrl() }.getOrDefault("") }

    fun props(): JsonObject = (auth?.get("props") as? JsonObject) ?: JsonObject(emptyMap())
    fun typedProps(): JsonObject = buildJsonObject {
        props().forEach { (k, v) ->
            val t = (v as? JsonObject)?.s("type").orEmpty()
            val raw = values["p:$k"] ?: return@forEach
            when (t) {
                "CHECKBOX" -> put(k, raw == "true")
                "NUMBER" -> raw.toDoubleOrNull()?.let { put(k, it) } ?: put(k, raw)
                "MARKDOWN" -> Unit
                else -> if (raw.isNotBlank()) put(k, raw)
            }
        }
    }
    fun scopeText(): String {
        var sc = (auth?.get("scope") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(" ").orEmpty()
        props().keys.forEach { k -> sc = sc.replace("{$k}", values["p:$k"].orEmpty()) }
        return sc
    }
    fun finish(t: String, value: JsonObject) {
        busy = true; err = null
        scope.launch {
            runCatching { HubApi.createConnection(p.name, name, t, value) }
                .onSuccess { onDone("${p.displayName} conectado. O agente já pode usar.") }
                .onFailure { err = it.message }
            busy = false
        }
    }
    fun onCode(code: String) {
        oauth = null
        val m = auth ?: return
        val pr = typedProps().mapValues { it.value }
        if (useCloud) finish("CLOUD_OAUTH2", buildJsonObject {
            put("type", "CLOUD_OAUTH2"); put("client_id", cloudClient!!); put("code", code); put("scope", scopeText())
            verifier?.let { put("code_challenge", it) }; m.s("authorizationMethod").takeIf { it.isNotBlank() }?.let { put("authorization_method", it) }
            if (pr.isNotEmpty()) put("props", JsonObject(pr))
        }) else finish("OAUTH2", buildJsonObject {
            put("type", "OAUTH2"); put("client_id", values["client_id"].orEmpty()); put("client_secret", values["client_secret"].orEmpty())
            put("code", code); put("scope", scopeText()); put("redirect_url", ownRedirect); put("grant_type", "authorization_code")
            verifier?.let { put("code_challenge", it) }; m.s("authorizationMethod").takeIf { it.isNotBlank() }?.let { put("authorization_method", it) }
            if (pr.isNotEmpty()) put("props", JsonObject(pr))
        })
    }

    AlertDialog(onDismissRequest = onDismiss, title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(p.logoUrl, null, Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(10.dp)); Text("Conectar ${p.displayName}")
        }
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                meta == null && err == null -> {
                    CircularProgressIndicator(color = c.accent)
                    Text("Preparando o conector (na primeira vez ele é baixado, leva alguns segundos)…", fontSize = 12.sp, color = c.ink3)
                }
                meta != null && auths.isEmpty() -> Text("Este conector não precisa de login: toque em Ativar e o agente já pode usar as ${p.actions} ações dele.", fontSize = 13.sp, color = c.ink2)
                meta != null -> {
                    if (auths.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        auths.forEachIndexed { i, a -> FilterChip(i == authIdx, { authIdx = i; values.clear() }, { Text(a.s("displayName").ifBlank { a.s("type") }, fontSize = 11.sp) }) }
                    }
                    auth?.s("description")?.takeIf { it.isNotBlank() }?.let { Text(it.take(600), fontSize = 12.sp, color = c.ink2) }
                    CueField(name, { name = it }, "Nome da conexão")
                    when (type) {
                        "SECRET_TEXT" -> CueField(values["secret"].orEmpty(), { values["secret"] = it }, auth?.s("displayName")?.ifBlank { "Chave" } ?: "Chave", password = true)
                        "BASIC_AUTH" -> {
                            CueField(values["user"].orEmpty(), { values["user"] = it }, ((auth?.get("username") as? JsonObject)?.s("displayName")).orEmpty().ifBlank { "Usuário" })
                            CueField(values["pass"].orEmpty(), { values["pass"] = it }, ((auth?.get("password") as? JsonObject)?.s("displayName")).orEmpty().ifBlank { "Senha" }, password = true)
                        }
                        "OAUTH2" -> if (!useCloud) {
                            Text("Este app pede um app OAuth próprio. Cadastre este endereço de retorno no serviço e cole o client ID e o secret:", fontSize = 12.sp, color = c.ink2)
                            Text(ownRedirect, fontSize = 12.sp, color = c.accent)
                            CueField(values["client_id"].orEmpty(), { values["client_id"] = it }, "Client ID")
                            CueField(values["client_secret"].orEmpty(), { values["client_secret"] = it }, "Client secret", password = true)
                        } else Text("Você vai entrar com a sua conta ${p.displayName} numa janela segura do próprio serviço.", fontSize = 12.sp, color = c.ink2)
                    }
                    // Campos próprios do conector (CUSTOM_AUTH e extras do OAuth2), gerados do que ele declara.
                    props().forEach { (k, v) ->
                        val o = v as? JsonObject ?: return@forEach
                        val label = o.s("displayName").ifBlank { k } + if (o.s("required") == "true") " *" else ""
                        when (o.s("type")) {
                            "MARKDOWN" -> Text(o.s("description").ifBlank { o.s("displayName") }.take(500), fontSize = 11.sp, color = c.ink3)
                            "CHECKBOX" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f), fontSize = 13.sp)
                                Switch(values["p:$k"] == "true", { values["p:$k"] = it.toString() })
                            }
                            "STATIC_DROPDOWN", "STATIC_MULTI_SELECT_DROPDOWN" -> {
                                val opts = ((o["options"] as? JsonObject)?.get("options") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                                Text(label, fontSize = 12.sp, color = c.ink2)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    opts.take(30).forEach { op -> FilterChip(values["p:$k"] == op.s("value"), { values["p:$k"] = op.s("value") }, { Text(op.s("label").ifBlank { op.s("value") }, fontSize = 11.sp) }) }
                                }
                            }
                            else -> CueField(values["p:$k"] ?: o.s("defaultValue"), { values["p:$k"] = it }, label, password = o.s("type") == "SECRET_TEXT")
                        }
                        o.s("description").takeIf { it.isNotBlank() && o.s("type") != "MARKDOWN" }?.let { Text(it.take(240), fontSize = 11.sp, color = c.ink3) }
                    }
                }
            }
            err?.let { Text(it, color = c.err, fontSize = 12.sp) }
        }
    }, confirmButton = {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
        else if (meta != null && auths.isEmpty()) TextButton({ finish("NO_AUTH", buildJsonObject { put("type", "NO_AUTH") }) }) { Text("Ativar") }
        else if (meta != null && auths.isNotEmpty()) TextButton({
            err = null
            val missing = props().entries.filter { (k, v) -> (v as? JsonObject)?.s("required") == "true" && (v as? JsonObject)?.s("type") !in setOf("MARKDOWN", "CHECKBOX") && values["p:$k"].isNullOrBlank() && (v as JsonObject).s("defaultValue").isBlank() }
            if (missing.isNotEmpty()) { err = "Preencha: " + missing.joinToString { (k, v) -> (v as JsonObject).s("displayName").ifBlank { k } }; return@TextButton }
            when (type) {
                "SECRET_TEXT" -> finish("SECRET_TEXT", buildJsonObject { put("type", "SECRET_TEXT"); put("secret_text", values["secret"].orEmpty()) })
                "BASIC_AUTH" -> finish("BASIC_AUTH", buildJsonObject { put("type", "BASIC_AUTH"); put("username", values["user"].orEmpty()); put("password", values["pass"].orEmpty()) })
                "CUSTOM_AUTH" -> finish("CUSTOM_AUTH", buildJsonObject { put("type", "CUSTOM_AUTH"); put("props", typedProps()) })
                "OAUTH2" -> {
                    val clientId = if (useCloud) cloudClient!! else values["client_id"].orEmpty()
                    if (clientId.isBlank()) { err = "Informe o client ID."; return@TextButton }
                    val redirect = if (useCloud) HubApi.CLOUD_REDIRECT else ownRedirect
                    busy = true
                    scope.launch {
                        runCatching { HubApi.authorizationUrl(p.name, clientId, redirect, typedProps().mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }) }
                            .onSuccess { verifier = it.codeVerifier; oauth = it.url to redirect }
                            .onFailure { err = it.message }
                        busy = false
                    }
                }
                else -> err = "Tipo de login \"$type\" ainda não é suportado no app."
            }
        }) { Text(if (type == "OAUTH2") "Entrar com ${p.displayName}" else "Conectar") }
        else TextButton(onDismiss) { Text("Fechar") }
    }, dismissButton = { if (meta != null) TextButton(onDismiss) { Text("Cancelar") } })

    oauth?.let { (url, redirect) -> OAuthWebDialog(url, redirect, onCode = { onCode(it) }, onError = { e -> oauth = null; err = e }, onDismiss = { oauth = null }) }
}


/**
 * Login OAuth numa janela do app: abre a página de autorização do serviço e, quando ele devolve para o
 * endereço de retorno, pega o "code" — sem depender de popup/JavaScript do painel.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OAuthWebDialog(url: String, redirect: String, onCode: (String) -> Unit, onError: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Cue.c
    var done by remember { mutableStateOf(false) }
    fun check(u: String?): Boolean {
        if (done || u == null || !u.startsWith(redirect.substringBefore('?'))) return false
        val uri = Uri.parse(u)
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
        done = true
        if (!code.isNullOrBlank()) onCode(Uri.decode(code)) else onError("O serviço não autorizou" + (error?.let { ": $it" } ?: "."))
        return true
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = c.bg) {
            Column {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Login seguro do serviço", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onDismiss) { Text("Cancelar") }
                }
                AndroidView(factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Sem a marca "; wv": alguns provedores (ex.: Google) recusam login dentro de WebView identificado.
                        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = check(request.url.toString())
                            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { if (check(u)) view.stopLoading() }
                        }
                        loadUrl(url)
                    }
                }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
