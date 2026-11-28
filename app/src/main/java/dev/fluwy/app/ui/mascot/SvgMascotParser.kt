package dev.fluwy.app.ui.mascot

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Lê um mascote importado (SVG ou VectorDrawable XML do Android) para um modelo simples de formas,
 * sem dependências de Android/Compose — testável na JVM.
 *
 * Partes animadas por convenção de nome (atributo id, class, inkscape:label ou android:name, em PT ou EN):
 *   eye/olho → pisca · mouth/boca → fala · arm/braco/hand/mao → acena · wing/asa → bate
 *   tail/cauda/rabo → balança · ear/orelha → tique · antenna/antena → oscila · body/corpo → respira
 *   spin/gira → gira · glow/brilho → pulsa a opacidade
 * Cor `currentColor` (SVG) ou `?colorAccent` (XML) é pintada com a cor escolhida para o Agent.
 */
object SvgMascotParser {
    const val MAX_BYTES = 1_000_000
    private const val MAX_SHAPES = 3_000

    enum class Role { NONE, EYE, MOUTH, ARM, WING, TAIL, EAR, ANTENNA, BODY, SPIN, GLOW }

    sealed class Paint {
        data class Solid(val argb: Long) : Paint()
        data object Current : Paint()
        data class Linear(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val stops: List<Pair<Float, Long>>, val userSpace: Boolean) : Paint()
        data class Radial(val cx: Float, val cy: Float, val r: Float, val stops: List<Pair<Float, Long>>, val userSpace: Boolean) : Paint()
    }

    /** Matriz afim [a b c d e f]: x' = a·x + c·y + e ; y' = b·x + d·y + f. */
    data class Affine(val a: Float = 1f, val b: Float = 0f, val c: Float = 0f, val d: Float = 1f, val e: Float = 0f, val f: Float = 0f) {
        operator fun times(o: Affine) = Affine(
            a * o.a + c * o.b, b * o.a + d * o.b,
            a * o.c + c * o.d, b * o.c + d * o.d,
            a * o.e + c * o.f + e, b * o.e + d * o.f + f,
        )
        val isIdentity get() = this == IDENTITY
        companion object { val IDENTITY = Affine() }
    }

    data class Shape(
        val pathData: String,
        val transform: Affine,
        val fill: Paint?,
        val stroke: Paint?,
        val strokeWidth: Float,
        val opacity: Float,
        val fillOpacity: Float,
        val strokeOpacity: Float,
        val evenOdd: Boolean,
        val roundCap: Boolean,
        val role: Role,
        /** Agrupa partes que se movem juntas (ex.: todos os elementos dentro de <g id="braco_esq">). */
        val partKey: String,
    )

    data class Model(val minX: Float, val minY: Float, val width: Float, val height: Float, val shapes: List<Shape>) {
        val hasAnimatedParts: Boolean get() = shapes.any { it.role != Role.NONE }
        val usesAgentColor: Boolean get() = shapes.any { it.fill == Paint.Current || it.stroke == Paint.Current }
    }

    class InvalidMascot(message: String) : Exception(message)

    fun parse(text: String): Model {
        if (text.length > MAX_BYTES) throw InvalidMascot("Arquivo grande demais (máx. 1 MB).")
        // Sem DTD/entidades: evita XXE e "billion laughs". Mascotes não precisam disso.
        if (Regex("(?i)<!DOCTYPE|<!ENTITY").containsMatchIn(text)) throw InvalidMascot("O arquivo usa DOCTYPE/ENTITY, que não é aceito por segurança.")
        val doc = runCatching {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            f.isExpandEntityReferences = false
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            f.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        }.getOrElse { throw InvalidMascot("Não consegui ler o XML: ${it.message ?: "formato inválido"}") }
        val root = doc.documentElement ?: throw InvalidMascot("Arquivo vazio.")
        val model = when (local(root.tagName)) {
            "svg" -> SvgReader(root).read()
            "vector" -> VectorReader(root).read()
            else -> throw InvalidMascot("Esperava um <svg> ou um <vector> (VectorDrawable), encontrei <${root.tagName}>.")
        }
        if (model.shapes.isEmpty()) throw InvalidMascot("Nenhuma forma desenhável encontrada (path, circle, rect…).")
        if (model.width <= 0f || model.height <= 0f) throw InvalidMascot("viewBox/tamanho inválido.")
        return model
    }

    // ------------------------------------------------------------------ SVG

    private class Style(
        val fill: Paint? = Paint.Solid(0xFF000000),
        val stroke: Paint? = null,
        val strokeWidth: Float = 1f,
        val opacity: Float = 1f,
        val fillOpacity: Float = 1f,
        val strokeOpacity: Float = 1f,
        val evenOdd: Boolean = false,
        val roundCap: Boolean = false,
        val role: Role = Role.NONE,
        val partKey: String = "",
    )

    private class SvgReader(val root: Element) {
        val gradients = HashMap<String, Element>()
        val shapes = ArrayList<Shape>()

        fun read(): Model {
            collectGradients(root)
            val vb = attr(root, "viewBox")?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }
            val (minX, minY, w, h) = if (vb != null && vb.size == 4) vb
                else listOf(0f, 0f, length(attr(root, "width")) ?: 100f, length(attr(root, "height")) ?: 100f)
            walk(root, Affine.IDENTITY, Style())
            return Model(minX, minY, w, h, shapes)
        }

        private fun collectGradients(e: Element) {
            children(e).forEach { c ->
                val tag = local(c.tagName)
                if ((tag == "linearGradient" || tag == "radialGradient") && attr(c, "id") != null) gradients[attr(c, "id")!!] = c
                collectGradients(c)
            }
        }

        private fun walk(e: Element, parent: Affine, inherited: Style) {
            for (c in children(e)) {
                if (shapes.size >= MAX_SHAPES) throw InvalidMascot("Formas demais (máx. $MAX_SHAPES).")
                val tag = local(c.tagName)
                if (tag in setOf("defs", "linearGradient", "radialGradient", "clipPath", "mask", "metadata", "title", "desc", "style", "script", "symbol", "pattern", "filter")) continue
                val props = props(c)
                if (props["display"] == "none" || props["visibility"] == "hidden") continue
                val m = parent * (attr(c, "transform")?.let { parseTransform(it) } ?: Affine.IDENTITY)
                val style = styleOf(c, props, inherited)
                when (tag) {
                    "g", "a", "switch" -> walk(c, m, style)
                    "svg" -> walk(c, m * nestedSvgOffset(c), style)
                    else -> geometry(c, tag)?.let { d ->
                        shapes += Shape(d, m, style.fill, style.stroke, style.strokeWidth, style.opacity, style.fillOpacity,
                            style.strokeOpacity, style.evenOdd, style.roundCap, style.role, style.partKey)
                    }
                }
            }
        }

        private fun nestedSvgOffset(e: Element) = Affine(e = length(attr(e, "x")) ?: 0f, f = length(attr(e, "y")) ?: 0f)

        private fun styleOf(e: Element, p: Map<String, String>, inh: Style): Style {
            val role = roleOf(e)
            return Style(
                // "none" precisa virar null (sem herdar o preenchimento do pai)
                fill = if ("fill" in p) paint(p.getValue("fill"), inh.fill) else inh.fill,
                stroke = if ("stroke" in p) paint(p.getValue("stroke"), inh.stroke) else inh.stroke,
                strokeWidth = p["stroke-width"]?.let { length(it) } ?: inh.strokeWidth,
                opacity = inh.opacity * (p["opacity"]?.toFloatOrNull() ?: 1f),
                fillOpacity = p["fill-opacity"]?.toFloatOrNull() ?: inh.fillOpacity,
                strokeOpacity = p["stroke-opacity"]?.toFloatOrNull() ?: inh.strokeOpacity,
                evenOdd = p["fill-rule"]?.let { it == "evenodd" } ?: inh.evenOdd,
                roundCap = p["stroke-linecap"]?.let { it == "round" } ?: inh.roundCap,
                role = if (role != Role.NONE) role else inh.role,
                partKey = if (role != Role.NONE) (attr(e, "id") ?: "${role.name}@${System.identityHashCode(e)}") else inh.partKey,
            )
        }

        private fun props(e: Element): Map<String, String> {
            val out = HashMap<String, String>()
            for (k in listOf("fill", "stroke", "stroke-width", "opacity", "fill-opacity", "stroke-opacity", "fill-rule", "stroke-linecap", "display", "visibility", "stop-color", "stop-opacity"))
                attr(e, k)?.let { out[k] = it.trim() }
            attr(e, "style")?.split(';')?.forEach { decl ->
                val i = decl.indexOf(':'); if (i > 0) out[decl.substring(0, i).trim()] = decl.substring(i + 1).trim()
            }
            return out
        }

        private fun paint(v: String, inherit: Paint?): Paint? {
            val s = v.trim()
            if (s == "none" || s == "transparent") return null
            if (s == "inherit") return inherit
            if (s.equals("currentColor", true)) return Paint.Current
            if (s.startsWith("url(")) {
                val id = s.substringAfter("#").substringBefore(")").trim().trim('"', '\'')
                return gradient(id) ?: s.substringAfter(")").trim().takeIf { it.isNotEmpty() }?.let { paint(it, inherit) }
            }
            return parseColor(s, cssAlphaLast = true)?.let { Paint.Solid(it) } ?: inherit
        }

        private fun gradient(id: String, depth: Int = 0): Paint? {
            val g = gradients[id] ?: return null
            val stops = stopsOf(g, depth)
            if (stops.isEmpty()) return null
            if (stops.size == 1) return Paint.Solid(stops[0].second)
            val user = attr(g, "gradientUnits") == "userSpaceOnUse"
            fun num(name: String, def: Float) = attr(g, name)?.let { if (it.endsWith("%")) it.dropLast(1).toFloatOrNull()?.div(100f) else length(it) } ?: def
            return if (local(g.tagName) == "radialGradient")
                Paint.Radial(num("cx", .5f), num("cy", .5f), num("r", .5f), stops, user)
            else Paint.Linear(num("x1", 0f), num("y1", 0f), num("x2", 1f), num("y2", 0f), stops, user)
        }

        private fun stopsOf(g: Element, depth: Int): List<Pair<Float, Long>> {
            val own = children(g).filter { local(it.tagName) == "stop" }.mapNotNull { s ->
                val p = props(s)
                val off = attr(s, "offset")?.let { if (it.endsWith("%")) it.dropLast(1).toFloatOrNull()?.div(100f) else it.toFloatOrNull() } ?: 0f
                val col = parseColor(p["stop-color"] ?: "#000", cssAlphaLast = true) ?: return@mapNotNull null
                val a = (p["stop-opacity"]?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f)
                off.coerceIn(0f, 1f) to withAlpha(col, a)
            }
            if (own.isNotEmpty() || depth > 4) return own
            val href = (attr(g, "xlink:href") ?: attr(g, "href"))?.removePrefix("#") ?: return own
            return gradients[href]?.let { stopsOf(it, depth + 1) } ?: own
        }

        private fun geometry(e: Element, tag: String): String? {
            fun n(name: String) = length(attr(e, name)) ?: 0f
            return when (tag) {
                "path" -> attr(e, "d")?.takeIf { it.isNotBlank() }
                "rect" -> {
                    val x = n("x"); val y = n("y"); val w = n("width"); val h = n("height")
                    if (w <= 0f || h <= 0f) return null
                    var rx = length(attr(e, "rx")) ?: length(attr(e, "ry")) ?: 0f
                    var ry = length(attr(e, "ry")) ?: rx
                    rx = rx.coerceAtMost(w / 2); ry = ry.coerceAtMost(h / 2)
                    if (rx <= 0f) "M$x,${y}h${w}v${h}h${-w}Z"
                    else "M${x + rx},${y}h${w - 2 * rx}a$rx,$ry 0 0 1 $rx,${ry}v${h - 2 * ry}a$rx,$ry 0 0 1 ${-rx},${ry}h${-(w - 2 * rx)}a$rx,$ry 0 0 1 ${-rx},${-ry}v${-(h - 2 * ry)}a$rx,$ry 0 0 1 $rx,${-ry}Z"
                }
                "circle" -> ellipse(n("cx"), n("cy"), n("r"), n("r"))
                "ellipse" -> ellipse(n("cx"), n("cy"), n("rx"), n("ry"))
                "line" -> "M${n("x1")},${n("y1")}L${n("x2")},${n("y2")}"
                "polygon", "polyline" -> {
                    val nums = attr(e, "points")?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() } ?: return null
                    if (nums.size < 4) return null
                    buildString {
                        nums.chunked(2).filter { it.size == 2 }.forEachIndexed { i, (x, y) -> append(if (i == 0) "M" else "L").append(x).append(',').append(y) }
                        if (tag == "polygon") append('Z')
                    }
                }
                else -> null
            }
        }
    }

    // ------------------------------------------------------------------ VectorDrawable

    private class VectorReader(val root: Element) {
        val shapes = ArrayList<Shape>()

        fun read(): Model {
            val w = attr(root, "android:viewportWidth")?.toFloatOrNull() ?: length(attr(root, "android:width")) ?: 24f
            val h = attr(root, "android:viewportHeight")?.toFloatOrNull() ?: length(attr(root, "android:height")) ?: 24f
            walk(root, Affine.IDENTITY, Role.NONE, "")
            return Model(0f, 0f, w, h, shapes)
        }

        private fun walk(e: Element, parent: Affine, role: Role, key: String) {
            for (c in children(e)) {
                if (shapes.size >= MAX_SHAPES) throw InvalidMascot("Formas demais (máx. $MAX_SHAPES).")
                val own = roleOf(c)
                val r = if (own != Role.NONE) own else role
                val k = if (own != Role.NONE) (attr(c, "android:name") ?: "${own.name}@${System.identityHashCode(c)}") else key
                when (local(c.tagName)) {
                    "group" -> walk(c, parent * groupMatrix(c), r, k)
                    "path" -> {
                        val d = attr(c, "android:pathData")?.takeIf { it.isNotBlank() } ?: continue
                        val fill = attr(c, "android:fillColor")?.let { vdPaint(it) }
                        val stroke = attr(c, "android:strokeColor")?.let { vdPaint(it) }
                        if (fill == null && stroke == null) continue
                        shapes += Shape(
                            d, parent, fill, stroke,
                            attr(c, "android:strokeWidth")?.toFloatOrNull() ?: 0f, 1f,
                            attr(c, "android:fillAlpha")?.toFloatOrNull() ?: 1f,
                            attr(c, "android:strokeAlpha")?.toFloatOrNull() ?: 1f,
                            attr(c, "android:fillType") == "evenOdd",
                            attr(c, "android:strokeLineCap") == "round", r, k,
                        )
                    }
                }
            }
        }

        private fun vdPaint(v: String): Paint? {
            val s = v.trim()
            if (s.startsWith("?") || s.startsWith("@color/") || s.equals("currentColor", true)) return Paint.Current
            return parseColor(s, cssAlphaLast = false)?.takeIf { (it ushr 24) != 0L }?.let { Paint.Solid(it) }
        }

        private fun groupMatrix(g: Element): Affine {
            fun f(n: String, d: Float) = attr(g, "android:$n")?.toFloatOrNull() ?: d
            val px = f("pivotX", 0f); val py = f("pivotY", 0f)
            val rot = f("rotation", 0f) * PI.toFloat() / 180f
            val sx = f("scaleX", 1f); val sy = f("scaleY", 1f)
            // ordem do VectorDrawable: translate · pivot · rotate · scale · -pivot
            return Affine(e = f("translateX", 0f) + px, f = f("translateY", 0f) + py) *
                Affine(cos(rot), sin(rot), -sin(rot), cos(rot)) * Affine(sx, 0f, 0f, sy) * Affine(e = -px, f = -py)
        }
    }

    // ------------------------------------------------------------------ utilidades

    private val roleWords = listOf(
        Role.EYE to listOf("eye", "olho", "pupil", "pupila"),
        Role.MOUTH to listOf("mouth", "boca"),
        Role.WING to listOf("wing", "asa"),
        Role.ARM to listOf("arm", "braco", "braço", "hand", "mao", "mão"),
        Role.TAIL to listOf("tail", "cauda", "rabo"),
        Role.EAR to listOf("ear", "orelha"),
        Role.ANTENNA to listOf("antenna", "antena"),
        Role.SPIN to listOf("spin", "gira", "rotor", "helice", "hélice"),
        Role.GLOW to listOf("glow", "brilho", "light", "luz"),
        Role.BODY to listOf("body", "corpo"),
    )

    internal fun roleFromName(name: String?): Role {
        if (name.isNullOrBlank()) return Role.NONE
        val tokens = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        for ((role, words) in roleWords) if (tokens.any { tok -> words.any { w -> tok == w || tok == w + "s" || tok.startsWith(w) && tok.length <= w.length + 2 } }) return role
        return Role.NONE
    }

    private fun roleOf(e: Element): Role {
        for (k in listOf("id", "class", "inkscape:label", "android:name", "data-role")) {
            val r = roleFromName(attr(e, k)); if (r != Role.NONE) return r
        }
        return Role.NONE
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): String? =
        if (rx <= 0f || ry <= 0f) null
        else "M${cx - rx},${cy}a$rx,$ry 0 1,0 ${2 * rx},0a$rx,$ry 0 1,0 ${-2 * rx},0Z"

    internal fun parseTransform(s: String): Affine {
        var m = Affine.IDENTITY
        Regex("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)").findAll(s).forEach { r ->
            val a = r.groupValues[2].trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            val t = when (r.groupValues[1]) {
                "matrix" -> if (a.size == 6) Affine(a[0], a[1], a[2], a[3], a[4], a[5]) else Affine.IDENTITY
                "translate" -> Affine(e = a.getOrElse(0) { 0f }, f = a.getOrElse(1) { 0f })
                "scale" -> Affine(a.getOrElse(0) { 1f }, 0f, 0f, a.getOrElse(1) { a.getOrElse(0) { 1f } })
                "rotate" -> {
                    val rad = a.getOrElse(0) { 0f } * PI.toFloat() / 180f
                    val rot = Affine(cos(rad), sin(rad), -sin(rad), cos(rad))
                    if (a.size >= 3) Affine(e = a[1], f = a[2]) * rot * Affine(e = -a[1], f = -a[2]) else rot
                }
                "skewX" -> Affine(c = tan(a.getOrElse(0) { 0f } * PI.toFloat() / 180f))
                "skewY" -> Affine(b = tan(a.getOrElse(0) { 0f } * PI.toFloat() / 180f))
                else -> Affine.IDENTITY
            }
            m = m * t
        }
        return m
    }

    private val named = mapOf(
        "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
        "yellow" to 0xFFFF00, "orange" to 0xFFA500, "purple" to 0x800080, "pink" to 0xFFC0CB, "gray" to 0x808080,
        "grey" to 0x808080, "brown" to 0xA52A2A, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "lime" to 0x00FF00,
        "navy" to 0x000080, "teal" to 0x008080, "gold" to 0xFFD700, "silver" to 0xC0C0C0, "coral" to 0xFF7F50,
        "salmon" to 0xFA8072, "violet" to 0xEE82EE, "indigo" to 0x4B0082, "crimson" to 0xDC143C, "tomato" to 0xFF6347,
    )

    /** Cor → ARGB (Long). SVG/CSS usa #RRGGBBAA; VectorDrawable usa #AARRGGBB. */
    internal fun parseColor(s: String, cssAlphaLast: Boolean): Long? {
        val v = s.trim().lowercase()
        if (v.startsWith("#")) {
            val hex = v.substring(1)
            fun h(i: Int, n: Int) = hex.substring(i, i + n).toLongOrNull(16)
            return when (hex.length) {
                3 -> { val r = h(0, 1) ?: return null; val g = h(1, 1) ?: return null; val b = h(2, 1) ?: return null
                    0xFF000000L or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17) }
                4 -> { val x = (0..3).map { h(it, 1) ?: return null }.map { it * 17 }
                    if (cssAlphaLast) (x[3] shl 24) or (x[0] shl 16) or (x[1] shl 8) or x[2] else (x[0] shl 24) or (x[1] shl 16) or (x[2] shl 8) or x[3] }
                6 -> (h(0, 6) ?: return null) or 0xFF000000L
                8 -> { val x = h(0, 8) ?: return null
                    if (cssAlphaLast) ((x and 0xFF) shl 24) or (x ushr 8) else x }
                else -> null
            }
        }
        Regex("rgba?\\(([^)]*)\\)").matchEntire(v)?.let { m ->
            val p = m.groupValues[1].split(Regex("[\\s,/]+")).filter { it.isNotEmpty() }
            if (p.size < 3) return null
            fun ch(x: String) = (if (x.endsWith("%")) (x.dropLast(1).toFloatOrNull() ?: 0f) * 2.55f else x.toFloatOrNull() ?: 0f).toInt().coerceIn(0, 255).toLong()
            val a = p.getOrNull(3)?.let { if (it.endsWith("%")) (it.dropLast(1).toFloatOrNull() ?: 100f) / 100f else it.toFloatOrNull() ?: 1f } ?: 1f
            return ((a.coerceIn(0f, 1f) * 255).toLong() shl 24) or (ch(p[0]) shl 16) or (ch(p[1]) shl 8) or ch(p[2])
        }
        return named[v]?.toLong()?.or(0xFF000000L)
    }

    private fun withAlpha(argb: Long, a: Float): Long {
        val base = ((argb ushr 24) and 0xFF) / 255f
        return (((base * a).coerceIn(0f, 1f) * 255).toLong() shl 24) or (argb and 0xFFFFFF)
    }

    internal fun length(v: String?): Float? = v?.trim()?.replace(Regex("(px|pt|dp|dip|sp)$"), "")?.toFloatOrNull()

    private fun attr(e: Element, name: String): String? = e.getAttribute(name).takeIf { e.hasAttribute(name) }

    private fun local(tag: String) = tag.substringAfter(':')

    private fun children(e: Element): List<Element> {
        val list = ArrayList<Element>()
        val nodes = e.childNodes
        for (i in 0 until nodes.length) { val n = nodes.item(i); if (n.nodeType == Node.ELEMENT_NODE) list += n as Element }
        return list
    }
}
