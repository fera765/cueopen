package dev.fluwy.app.ui.mascot

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Mascote criado pelo usuário (SVG ou VectorDrawable XML), salvo em filesDir/mascots. */
class CustomMascot(val id: String, val name: String, val motion: MascotMotion, val format: String, val text: String) {
    val face: String get() = Mascots.CUSTOM_PREFIX + id
    /** Modelo de formas para animar partes; null quando o arquivo só é suportado pelo renderizador completo. */
    val model: SvgMascotParser.Model? by lazy { runCatching { SvgMascotParser.parse(text) }.getOrNull() }
}

object MascotLibrary {
    private val _items = MutableStateFlow<List<CustomMascot>>(emptyList())
    val items: StateFlow<List<CustomMascot>> = _items
    @Volatile private var loaded = false
    private lateinit var dir: File

    fun get(ctx: Context): MascotLibrary {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                dir = File(ctx.applicationContext.filesDir, "mascots").apply { mkdirs() }
                _items.value = readIndex()
                loaded = true
            }
        }
        return this
    }

    fun find(id: String): CustomMascot? = _items.value.firstOrNull { it.id == id }

    /**
     * Valida e salva. Aceita SVG (renderizado pelo AndroidSVG, com partes animadas pelo parser próprio)
     * e VectorDrawable XML. Falha com mensagem amigável quando o arquivo não serve.
     */
    fun import(ctx: Context, name: String, text: String, motion: MascotMotion): Result<CustomMascot> = runCatching {
        get(ctx)
        val trimmed = text.trim().removePrefix("﻿")
        if (trimmed.length > SvgMascotParser.MAX_BYTES) throw SvgMascotParser.InvalidMascot("Arquivo grande demais (máx. 1 MB).")
        if (Regex("(?i)<script|javascript:|<foreignObject").containsMatchIn(trimmed))
            throw SvgMascotParser.InvalidMascot("O SVG contém script/foreignObject, que não é permitido.")
        val isVector = Regex("<vector[\\s>]").containsMatchIn(trimmed)
        val parsed = runCatching { SvgMascotParser.parse(trimmed) }
        if (isVector) parsed.getOrThrow()
        else {
            // O AndroidSVG cobre o que o parser simples não cobre (texto, filtros, máscaras…).
            val full = runCatching { com.caverock.androidsvg.SVG.getFromString(trimmed) }
            if (full.isFailure && parsed.isFailure) throw parsed.exceptionOrNull()!!
        }
        val m = CustomMascot(UUID.randomUUID().toString().take(12), name.trim().ifBlank { "Meu mascote" }.take(40), motion,
            if (isVector) "xml" else "svg", trimmed)
        File(dir, m.id + "." + m.format).writeText(trimmed)
        _items.value = _items.value + m
        writeIndex()
        m
    }

    fun delete(id: String) {
        val m = find(id) ?: return
        runCatching { File(dir, m.id + "." + m.format).delete() }
        _items.value = _items.value.filterNot { it.id == id }
        writeIndex()
    }

    private fun readIndex(): List<CustomMascot> = runCatching {
        val f = File(dir, "index.json"); if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.optString("id"); val format = o.optString("format", "svg")
            val file = File(dir, "$id.$format")
            if (id.isBlank() || !file.exists()) null
            else CustomMascot(id, o.optString("name", "Meu mascote"), MascotMotion.of(o.optString("motion")), format, file.readText())
        }
    }.getOrDefault(emptyList())

    private fun writeIndex() {
        val arr = JSONArray()
        _items.value.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("motion", it.motion.name).put("format", it.format)) }
        runCatching {
            val tmp = File(dir, "index.json.tmp"); tmp.writeText(arr.toString())
            tmp.renameTo(File(dir, "index.json"))
        }
    }
}
