package com.notia.mobile.core

import java.math.BigDecimal
import java.net.URLEncoder
import java.util.Calendar

/** Pure helpers: HTML escaping and sanitising, formula evaluator, table and chart builders, dates. */

fun esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private const val DANGEROUS = "script|iframe|object|embed|style|link|meta|base|form"
private val RX_PAIR = Regex("<\\s*($DANGEROUS)\\b[^>]*>[\\s\\S]*?<\\s*/\\s*\\1\\s*>", RegexOption.IGNORE_CASE)
private val RX_OPEN = Regex("<\\s*/?\\s*($DANGEROUS)\\b[^>]*>", RegexOption.IGNORE_CASE)
private val RX_TAG = Regex("<[^>]*>")
private val RX_ON = Regex("(?<=[\\s/\"'])on\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE)
private val RX_JS_Q = Regex("(href|src|action|formaction|xlink:href)\\s*=\\s*([\"'])\\s*(javascript|vbscript):[^\"']*\\2", RegexOption.IGNORE_CASE)
private val RX_JS_U = Regex("(href|src|action|formaction|xlink:href)\\s*=\\s*(javascript|vbscript):[^\\s>]*", RegexOption.IGNORE_CASE)

/** Removes scripts, frames, inline handlers and javascript: links from untrusted note HTML. */
fun sanitize(h: String): String {
    var s = h
    // repeat until stable so nested tricks such as <scr<script>ipt> cannot survive one pass
    for (i in 0 until 3) {
        val before = s
        s = RX_PAIR.replace(s, "")
        s = RX_OPEN.replace(s, "")
        s = RX_TAG.replace(s) { RX_ON.replace(it.value, "") }
        s = RX_JS_Q.replace(s, "\$1=\$2\$2")
        s = RX_JS_U.replace(s, "\$1=\"\"")
        if (s == before) break
    }
    return s
}

private val RX_TAGS = Regex("<[^>]*>")
private val RX_WS = Regex("\\s+")
private val RX_IMG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
private val RX_MEDIA = Regex("<(img|table|hr)\\b", RegexOption.IGNORE_CASE)

fun decodeEntities(s: String): String = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

/** Plain text of an HTML note (images removed first so base64 never leaks into previews). */
fun plain(html: String): String =
    RX_WS.replace(decodeEntities(RX_TAGS.replace(RX_IMG.replace(html, " "), " ")), " ").trim()

/** True when the note body holds something even without text (image, table, line). */
fun hasMedia(html: String): Boolean = RX_MEDIA.containsMatchIn(html)

class MathError(msg: String) : Exception(msg)

/** Safe arithmetic evaluator (no eval): + - * / % ^ ( ) sqrt sin cos tan log ln abs round pi e */
fun evalMath(src: String): Double = MathParser(
    src.replace(Regex("\\s+"), "").lowercase().replace('×', '*').replace('÷', '/').replace(',', '.')
).run()

private class MathParser(val s: String) {
    var i = 0
    fun peek(): Char = if (i < s.length) s[i] else '\u0000'

    fun run(): Double {
        val v = expr()
        if (i < s.length) throw MathError("Unexpected " + s[i])
        if (v.isNaN() || v.isInfinite()) throw MathError("Not a number")
        return v
    }

    fun expr(): Double {
        var v = term()
        while (peek() == '+' || peek() == '-') { val o = s[i++]; val r = term(); v = if (o == '+') v + r else v - r }
        return v
    }

    fun term(): Double {
        var v = unary()
        while (peek() == '*' || peek() == '/' || peek() == '%') {
            val o = s[i++]; val r = unary()
            v = when (o) { '*' -> v * r; '/' -> v / r; else -> v % r }
        }
        return v
    }

    fun unary(): Double {
        if (peek() == '-') { i++; return -unary() }
        if (peek() == '+') { i++; return unary() }
        return pow()
    }

    fun pow(): Double { val b = atom(); if (peek() == '^') { i++; return Math.pow(b, unary()) }; return b }

    fun close() { if (peek() != ')') throw MathError("Missing )"); i++ }

    fun atom(): Double {
        if (peek() == '(') { i++; val v = expr(); close(); return v }
        val rest = s.substring(i)
        val n = Regex("^(\\d+\\.?\\d*|\\.\\d+)").find(rest)
        if (n != null) { i += n.value.length; return n.value.toDouble() }
        val w = Regex("^[a-z]+").find(rest)
        if (w != null) {
            i += w.value.length
            if (w.value == "pi") return Math.PI
            if (w.value == "e") return Math.E
            if (peek() != '(') throw MathError("Unknown: " + w.value)
            i++
            val v = expr(); close()
            return when (w.value) {
                "sqrt" -> Math.sqrt(v); "sin" -> Math.sin(v); "cos" -> Math.cos(v); "tan" -> Math.tan(v)
                "log" -> Math.log10(v); "ln" -> Math.log(v); "abs" -> Math.abs(v); "round" -> Math.floor(v + 0.5)
                else -> throw MathError("Unknown: " + w.value)
            }
        }
        throw MathError(if (i < s.length) "Unexpected " + s[i] else "Incomplete")
    }
}

fun fmtNum(v: Double): String {
    val r = Math.floor(v * 1e10 + 0.5) / 1e10
    return BigDecimal.valueOf(r).stripTrailingZeros().toPlainString()
}

fun tableHtml(rows: Int, cols: Int): String {
    fun td(b: Boolean) = "<td style=\"border:1px solid #888;padding:6px;min-width:48px\">" + (if (b) "<b>&nbsp;</b>" else "&nbsp;") + "</td>"
    fun tr(b: Boolean) = "<tr>" + (0 until cols).joinToString("") { td(b) } + "</tr>"
    return "<table style=\"border-collapse:collapse;width:100%\"><tbody>" + (0 until rows).joinToString("") { tr(it == 0) } + "</tbody></table><div><br></div>"
}

data class Pt(val label: String, val value: Double)

private val RX_PT = Regex("^(.*?)[:=;\\t]\\s*(-?\\d+(?:[.,]\\d+)?)\\s*$")
private val RX_LABEL_BAD = Regex("[:;|\"<>&]")

fun parsePts(t: String): List<Pt> = t.split('\n').mapNotNull { l ->
    val m = RX_PT.find(l.trim()) ?: return@mapNotNull null
    val label = m.groupValues[1].trim().ifEmpty { "-" }.replace(RX_LABEL_BAD, " ").take(14)
    Pt(label, Math.max(0.0, m.groupValues[2].replace(',', '.').toDouble()))
}

val CHART_PALETTE = intArrayOf(0x8B6CF6, 0xF2B33D, 0x3DA5E0, 0xEF6B9A, 0x3FBF7F, 0xE5484D)
private fun hex6(c: Int) = String.format("#%06X", c and 0xFFFFFF)
private fun num(d: Double): String = BigDecimal.valueOf(Math.round(d * 100) / 100.0).stripTrailingZeros().toPlainString()

/** Chart as an <img> (SVG data URI). The alt text carries type, title and data so exports can redraw it. */
fun chartImg(type: String, title: String, pts: List<Pt>): String {
    val w = 320; val h = 220; val x0 = 30.0; val x1 = 310.0; val y0 = 180.0; val hh = 130.0
    fun t(x: Double, y: Double, text: String, o: String = "") =
        "<text x=\"${num(x)}\" y=\"${num(y)}\" font-size=\"10\" fill=\"#333\" font-family=\"sans-serif\" $o>${esc(text)}</text>"
    val g = StringBuilder()
    g.append("<rect width=\"$w\" height=\"$h\" rx=\"12\" fill=\"#fff\"/>")
    g.append(t(w / 2.0, 22.0, title, "text-anchor=\"middle\" font-size=\"13\" font-weight=\"bold\""))
    val max = Math.max(pts.maxOfOrNull { it.value } ?: 1.0, 1.0)
    val bw = (x1 - x0) / Math.max(pts.size, 1)
    if (type == "pie") {
        val tot = pts.sumOf { it.value }.let { if (it == 0.0) 1.0 else it }
        val cx = 90.0; val cy = 120.0; val r = 70.0
        var a0 = -Math.PI / 2
        pts.forEachIndexed { i, p ->
            val a1 = a0 + (p.value / tot) * 2 * Math.PI
            val c = hex6(CHART_PALETTE[i % CHART_PALETTE.size])
            if (p.value / tot > 0.9999) g.append("<circle cx=\"${num(cx)}\" cy=\"${num(cy)}\" r=\"${num(r)}\" fill=\"$c\"/>")
            else if (p.value > 0) g.append("<path d=\"M${num(cx)} ${num(cy)}L${num(cx + r * Math.cos(a0))} ${num(cy + r * Math.sin(a0))}A${num(r)} ${num(r)} 0 ${if (a1 - a0 > Math.PI) 1 else 0} 1 ${num(cx + r * Math.cos(a1))} ${num(cy + r * Math.sin(a1))}Z\" fill=\"$c\"/>")
            g.append("<rect x=\"185\" y=\"${50 + i * 18}\" width=\"10\" height=\"10\" fill=\"$c\"/>")
            g.append(t(200.0, 59.0 + i * 18, "${p.label} (${fmtNum(p.value)})"))
            a0 = a1
        }
    } else {
        g.append("<line x1=\"${num(x0)}\" y1=\"${num(y0)}\" x2=\"${num(x1)}\" y2=\"${num(y0)}\" stroke=\"#999\"/>")
        fun px(i: Int) = x0 + (i + 0.5) * bw
        fun py(v: Double) = y0 - (v / max) * hh
        if (type == "bar") pts.forEachIndexed { i, p ->
            g.append("<rect x=\"${num(px(i) - bw * 0.35)}\" y=\"${num(py(p.value))}\" width=\"${num(bw * 0.7)}\" height=\"${num((p.value / max) * hh)}\" fill=\"${hex6(CHART_PALETTE[i % CHART_PALETTE.size])}\" rx=\"3\"/>")
        } else {
            g.append("<polyline fill=\"none\" stroke=\"#8B6CF6\" stroke-width=\"2.5\" points=\"${pts.indices.joinToString(" ") { "${num(px(it))},${num(py(pts[it].value))}" }}\"/>")
            pts.indices.forEach { g.append("<circle cx=\"${num(px(it))}\" cy=\"${num(py(pts[it].value))}\" r=\"3.5\" fill=\"#8B6CF6\"/>") }
        }
        pts.forEachIndexed { i, p ->
            g.append(t(px(i), y0 + 13, p.label.take(Math.max(3, Math.floor(bw / 6).toInt())), "text-anchor=\"middle\""))
            g.append(t(px(i), py(p.value) - 5, fmtNum(p.value), "text-anchor=\"middle\""))
        }
    }
    val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $w $h\" width=\"$w\" height=\"$h\">$g</svg>"
    val alt = "chart|$type|${esc(title.replace(Regex("[|;]"), " "))}|" + pts.joinToString(";") { "${it.label.replace(RX_LABEL_BAD, " ")}:${fmtNum(it.value)}" }
    val enc = URLEncoder.encode(svg, "UTF-8").replace("+", "%20")
    return "<img alt=\"$alt\" src=\"data:image/svg+xml;utf8,$enc\" style=\"max-width:100%\"/><div><br></div>"
}

private fun p2(n: Int) = n.toString().padStart(2, '0')

/** DD/MM/YY, optionally with HH:mm */
fun fmtDate(ts: Long, time: Boolean = false): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return p2(c.get(Calendar.DAY_OF_MONTH)) + "/" + p2(c.get(Calendar.MONTH) + 1) + "/" + p2(c.get(Calendar.YEAR) % 100) +
        (if (time) " " + p2(c.get(Calendar.HOUR_OF_DAY)) + ":" + p2(c.get(Calendar.MINUTE)) else "")
}

private const val ID_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"
fun newId(): String {
    val r = java.util.concurrent.ThreadLocalRandom.current()
    return java.lang.Long.toString(System.currentTimeMillis(), 36) + (1..4).map { ID_CHARS[r.nextInt(36)] }.joinToString("")
}

fun fmtTime(s: Long): String { val m = s / 60; return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }
