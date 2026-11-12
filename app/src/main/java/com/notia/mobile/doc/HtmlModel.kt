package com.notia.mobile.doc

import com.notia.mobile.core.Pt

/**
 * Turns the HTML that the Notia editor produces into a small block model.
 * The same model feeds the DOCX writer and the PDF writer, so both exports look the same.
 */
data class Fmt(
    val b: Boolean = false, val i: Boolean = false, val u: Boolean = false, val code: Boolean = false,
    val color: String? = null, val bg: String? = null,
    /** font size in half-points (22 = 11 pt); 0 = default */
    val size: Int = 0,
    val link: String? = null,
)

sealed class Inline
class TextRun(val text: String, val fmt: Fmt) : Inline()
object LineBreak : Inline()
/** w/h in pixels, 0 when unknown. ext is "png" or "jpeg". */
class ImageRun(val bytes: ByteArray, val ext: String, val w: Int, val h: Int) : Inline()

sealed class Block
class Para(val runs: List<Inline>, val prefix: String?, val listDepth: Int, val quote: Boolean) : Block()
object Rule : Block()
class TableBlock(val rows: List<List<List<Block>>>) : Block()
class ChartBlock(val type: String?, val title: String, val pts: List<Pt>) : Block()

object HtmlModel {
    private val BLOCK = setOf("div", "p", "h1", "h2", "h3", "pre", "blockquote")
    private val VOID = setOf("br", "hr", "img", "input", "meta")
    private val TOKEN = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|([^<]+)")
    private val NUM_ENT = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
    private val RX_WS = Regex("\\s+")
    private val RX_DATA_IMG = Regex("^data:image/(png|jpe?g|gif|webp|bmp);base64,(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun dec(s: String): String = NUM_ENT.replace(
        s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
    ) { m ->
        val v = m.groupValues[1]
        val cp = try { if (v[0] == 'x' || v[0] == 'X') v.substring(1).toInt(16) else v.toInt() } catch (e: Exception) { 32 }
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else " "
    }.replace("&amp;", "&")

    fun attr(a: String, n: String): String {
        val m = Regex("\\b" + Regex.escape(n) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(a) ?: return ""
        return dec(m.groupValues[1].ifEmpty { m.groupValues[2] })
    }

    private fun styleVal(st: String, p: String): String {
        val m = Regex("(?:^|;)\\s*" + Regex.escape(p) + "\\s*:\\s*([^;]+)", RegexOption.IGNORE_CASE).find(st) ?: return ""
        return m.groupValues[1].trim()
    }

    /** "#rgb", "#rrggbb" or rgb()/rgba() to RRGGBB, or null */
    fun hex(v: String): String? {
        val t = v.trim()
        Regex("^#([0-9a-fA-F]{6})$").find(t)?.let { return it.groupValues[1].uppercase() }
        Regex("^#([0-9a-fA-F]{3})$").find(t)?.let { return it.groupValues[1].map { c -> "$c$c" }.joinToString("").uppercase() }
        Regex("rgba?\\(\\s*(\\d+)\\D+(\\d+)\\D+(\\d+)", RegexOption.IGNORE_CASE).find(t)?.let {
            return (1..3).joinToString("") { k -> Math.min(255, it.groupValues[k].toInt()).toString(16).padStart(2, '0') }.uppercase()
        }
        return null
    }

    private val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    fun b64ToBytes(b: String): ByteArray {
        val out = ByteArray(b.length * 3 / 4 + 3)
        var bits = 0; var acc = 0; var o = 0
        for (ch in b) {
            val v = if (ch == '-') 62 else if (ch == '_') 63 else B64.indexOf(ch)
            if (v < 0) continue
            acc = (acc shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; out[o++] = ((acc shr bits) and 255).toByte(); acc = acc and ((1 shl bits) - 1) }
        }
        return out.copyOf(o)
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** width/height in px for PNG and JPEG, or null */
    fun imgSize(b: ByteArray): IntArray? {
        if (b.size > 24 && u(b, 0) == 0x89 && u(b, 1) == 0x50) {
            val w = (u(b, 16) shl 24) or (u(b, 17) shl 16) or (u(b, 18) shl 8) or u(b, 19)
            val h = (u(b, 20) shl 24) or (u(b, 21) shl 16) or (u(b, 22) shl 8) or u(b, 23)
            return if (w > 0 && h > 0) intArrayOf(w, h) else null
        }
        var i = 2
        while (i + 9 < b.size) {
            if (u(b, i) != 0xFF) { i++; continue }
            val m = u(b, i + 1)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                val h = (u(b, i + 5) shl 8) or u(b, i + 6)
                val w = (u(b, i + 7) shl 8) or u(b, i + 8)
                return if (w > 0 && h > 0) intArrayOf(w, h) else null
            }
            i += 2 + Math.max(2, (u(b, i + 2) shl 8) or u(b, i + 3))
        }
        return null
    }

    /** conv turns an image the writers cannot embed (gif, webp, bmp) into PNG bytes; may return null. */
    fun parse(html: String, conv: ((String, ByteArray) -> ByteArray?)? = null): List<Block> {
        val body = ArrayList<Block>()
        var out: MutableList<Block> = body
        var runs = ArrayList<Inline>()
        var quote = false
        var pre: String? = null
        var preDepth = 0
        val fm = ArrayList<Fmt>().apply { add(Fmt()) }
        val open = ArrayList<String>()
        val lists = ArrayList<IntArray>() // [ordered 0/1, counter]
        var tbl: ArrayList<List<List<Block>>>? = null
        var row = ArrayList<List<Block>>()
        var cell = ArrayList<Block>()

        fun flush() {
            if (runs.isNotEmpty() || pre != null) out.add(Para(runs, pre, preDepth, quote))
            runs = ArrayList(); quote = false; pre = null; preDepth = 0
        }

        for (m in TOKEN.findAll(html)) {
            if (m.groups[4] != null) {
                val inPre = open.contains("pre")
                var t = dec(m.groupValues[4])
                if (!inPre) { t = RX_WS.replace(t, " "); if (t.isBlank() && runs.isEmpty()) continue }
                runs.add(TextRun(t, fm.last()))
                continue
            }
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val a = m.groupValues[3]
            if (closing) {
                val k = open.lastIndexOf(tag)
                if (k < 0) continue
                while (open.size > k) open.removeAt(open.size - 1)
                while (fm.size > k + 1) fm.removeAt(fm.size - 1)
                if (tag in BLOCK || tag == "li") flush()
                else if (tag == "ul" || tag == "ol") { flush(); if (lists.isNotEmpty()) lists.removeAt(lists.size - 1) }
                else if (tag == "td" || tag == "th") {
                    flush()
                    if (cell.isEmpty()) cell.add(Para(emptyList(), null, 0, false))
                    row.add(cell); out = body
                } else if (tag == "tr") tbl?.add(row)
                else if (tag == "table" && tbl != null) { body.add(TableBlock(tbl)); tbl = null }
                continue
            }
            if (tag in VOID) {
                if (tag == "br") runs.add(LineBreak)
                else if (tag == "hr") { flush(); out.add(Rule) }
                else if (tag == "img") {
                    val alt = attr(a, "alt"); val src = attr(a, "src")
                    if (alt.startsWith("chart|")) {
                        val parts = alt.split('|')
                        val type = if (parts.size >= 4) parts[1] else null
                        val title = if (parts.size >= 4) parts[2] else parts.getOrElse(1) { "" }
                        val data = if (parts.size >= 4) parts[3] else parts.getOrElse(2) { "" }
                        val pts = data.split(';').filter { it.isNotEmpty() }.map {
                            val k = it.lastIndexOf(':')
                            if (k < 0) Pt(it, 0.0) else Pt(it.substring(0, k), it.substring(k + 1).toDoubleOrNull() ?: 0.0)
                        }
                        flush(); out.add(ChartBlock(type, title, pts))
                    } else {
                        val im = RX_DATA_IMG.find(src)
                        if (im != null) {
                            val mime = im.groupValues[1].lowercase()
                            var bytes = b64ToBytes(im.groupValues[2])
                            var ext = if (mime == "png") "png" else if (mime.startsWith("jp")) "jpeg" else ""
                            if (ext.isEmpty()) {
                                val c = try { conv?.invoke(mime, bytes) } catch (e: Exception) { null }
                                if (c != null) { bytes = c; ext = "png" }
                            }
                            if (ext.isNotEmpty() && bytes.isNotEmpty()) {
                                val sz = imgSize(bytes)
                                runs.add(ImageRun(bytes, ext, sz?.get(0) ?: 0, sz?.get(1) ?: 0))
                            }
                        }
                    }
                }
                continue
            }
            // opening tag: compute inherited format
            var f = fm.last()
            if (tag == "b" || tag == "strong") f = f.copy(b = true)
            if (tag == "i" || tag == "em") f = f.copy(i = true)
            if (tag == "u") f = f.copy(u = true)
            if (tag == "code" || tag == "pre") f = f.copy(code = true)
            if (tag == "th") f = f.copy(b = true)
            if (tag == "h1") f = f.copy(b = true, size = 36) else if (tag == "h2") f = f.copy(b = true, size = 30) else if (tag == "h3") f = f.copy(b = true, size = 26)
            if (tag == "a") { val h = attr(a, "href"); if (h.isNotEmpty()) f = f.copy(link = h, color = "0563C1", u = true) }
            if (tag == "font") { hex(attr(a, "color"))?.let { f = f.copy(color = it) } }
            val st = attr(a, "style")
            if (st.isNotEmpty()) {
                hex(styleVal(st, "color"))?.let { f = f.copy(color = it) }
                hex(styleVal(st, "background-color").ifEmpty { styleVal(st, "background") })?.let { f = f.copy(bg = it) }
                if (Regex("bold|[6-9]00").containsMatchIn(styleVal(st, "font-weight"))) f = f.copy(b = true)
                if (styleVal(st, "font-style").contains("italic")) f = f.copy(i = true)
                if (styleVal(st, "text-decoration").contains("underline")) f = f.copy(u = true)
            }
            if (tag in BLOCK) { flush(); if (tag == "blockquote") quote = true }
            else if (tag == "ul" || tag == "ol") { flush(); lists.add(intArrayOf(if (tag == "ol") 1 else 0, 0)) }
            else if (tag == "li") {
                flush()
                val l = lists.lastOrNull()
                pre = if (l != null && l[0] == 1) { l[1]++; "${l[1]}. " } else "• "
                preDepth = Math.max(1, lists.size)
            }
            else if (tag == "table") { flush(); tbl = ArrayList() }
            else if (tag == "tr") row = ArrayList()
            else if (tag == "td" || tag == "th") { flush(); cell = ArrayList(); out = cell }
            open.add(tag); fm.add(f)
        }
        flush()
        tbl?.let { body.add(TableBlock(it)) }
        return body
    }
}
