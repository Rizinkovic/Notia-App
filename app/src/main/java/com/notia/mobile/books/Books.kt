package com.notia.mobile.books

import com.notia.mobile.core.esc
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.Closeable
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Raised for books that cannot be opened. code: drm, kf8, compression, empty, invalid, memory. */
class BookException(val code: String, msg: String) : Exception(msg)

class Res(val mime: String, val data: ByteArray)

/** One table-of-contents entry. path is the zip path of the section ("" when unknown), frag the anchor id. */
class TocItem(val title: String, val path: String, val frag: String, val level: Int)

/** A book converted to one scrollable HTML page plus its inner resources (images). */
abstract class TextBook : Closeable {
    /** Inner HTML for the reader. Resource URLs look like /z/<path> (zip) or /m/<n> (mobi). */
    abstract fun contentHtml(): String
    abstract fun resource(path: String): Res?
    open fun cover(): ByteArray? = null
    /** Native table of contents; empty when the format has none (the reader then falls back to headings). */
    open fun toc(): List<TocItem> = emptyList()
    override fun close() {}
}

internal fun mimeOf(b: ByteArray): String {
    if (b.size < 4) return ""
    val b0 = b[0].toInt() and 0xFF; val b1 = b[1].toInt() and 0xFF
    return when {
        b0 == 0xFF && b1 == 0xD8 -> "image/jpeg"
        b0 == 0x89 && b1 == 0x50 -> "image/png"
        b0 == 0x47 && b1 == 0x49 -> "image/gif"
        b0 == 0x52 && b1 == 0x49 -> "image/webp"
        else -> ""
    }
}

internal fun mimeByName(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
    "svg" -> "image/svg+xml"; "bmp" -> "image/bmp"; else -> "application/octet-stream"
}

/** UTF-8 when valid, otherwise Windows-1252. */
fun decodeText(b: ByteArray): String {
    var off = 0
    if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) off = 3
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b, off, b.size - off)).toString()
    } catch (e: CharacterCodingException) { String(b, off, b.size - off, charset("windows-1252")) }
}

private val VOID_TAGS = "area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr|image|path|rect|circle|line|polyline|polygon|ellipse|stop|use"
private val SELF_CLOSE = Regex("<(?!(?:$VOID_TAGS)\\b)([a-zA-Z][a-zA-Z0-9:]*)(\\b[^>]*?)\\s*/>", RegexOption.IGNORE_CASE)

class PlainBook(private val file: File, private val html: Boolean) : TextBook() {
    override fun contentHtml(): String {
        val t = decodeText(file.readBytes())
        if (t.isBlank()) throw BookException("empty", "The book appears to be empty.")
        return if (html) t else "<div class=\"txt\">" + esc(t) + "</div>"
    }
    override fun resource(path: String): Res? = null
}

private fun secureBuilder() = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    isExpandEntityReferences = false
}.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }

internal fun parseXml(bytes: ByteArray) = secureBuilder().parse(InputSource(java.io.ByteArrayInputStream(bytes)))

internal fun urlDecode(s: String): String = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }

/** Resolves href against the file it was found in, inside a zip. Query and fragment are dropped. */
fun resolvePath(fromFile: String, href: String): String {
    val h = href.substringBefore('#').substringBefore('?')
    val dir = if ('/' in fromFile) fromFile.substringBeforeLast('/') + "/" else ""
    val full = if (h.startsWith("/")) h.substring(1) else dir + h
    val parts = ArrayList<String>()
    for (p in full.split('/')) when (p) { "", "." -> {}; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); else -> parts.add(p) }
    return urlDecode(parts.joinToString("/"))
}

private fun encPath(p: String) = p.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

private val RX_IMG_TAG = Regex("<(img|image)\\b[^>]*>", RegexOption.IGNORE_CASE)
private val RX_SRC = Regex("\\b(src|xlink:href|href)(\\s*=\\s*)(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
private val RX_BODY = Regex("<body\\b[^>]*>([\\s\\S]*)</body\\s*>", RegexOption.IGNORE_CASE)

/** Keeps zip lookups case-insensitive, like many e-reader tools. */
private class Zip(val z: ZipFile) {
    val low = HashMap<String, String>()
    init { for (e in z.entries()) low[e.name.lowercase()] = e.name }
    fun bytes(path: String): ByteArray? {
        val e = z.getEntry(path) ?: low[path.lowercase()]?.let { z.getEntry(it) } ?: return null
        return z.getInputStream(e).use { it.readBytes() }
    }
}

class EpubBook(file: File) : TextBook() {
    private val zip: Zip
    private var cover: ByteArray? = null
    private val html: String
    private var tocItems: List<TocItem> = emptyList()

    init {
        val zf = try { ZipFile(file) } catch (e: Exception) { throw BookException("invalid", "Not a valid EPUB.") }
        zip = Zip(zf)
        try {
            val cont = zip.bytes("META-INF/container.xml") ?: throw BookException("invalid", "Not a valid EPUB (container.xml missing).")
            val root = parseXml(cont).getElementsByTagNameNS("*", "rootfile").item(0) as? Element
                ?: throw BookException("invalid", "Not a valid EPUB.")
            val opfPath = root.getAttribute("full-path")
            val opfBytes = zip.bytes(opfPath) ?: throw BookException("invalid", "Not a valid EPUB (package file missing).")
            val opf = parseXml(opfBytes)
            val manifest = HashMap<String, Element>()
            val items = opf.getElementsByTagNameNS("*", "item")
            for (i in 0 until items.length) (items.item(i) as Element).let { manifest[it.getAttribute("id")] = it }
            val base = if ('/' in opfPath) opfPath.substringBeforeLast('/') + "/" else ""
            cover = findCover(opf, manifest, opfPath)
            tocItems = try { readToc(opf, manifest, opfPath) } catch (e: Exception) { emptyList() }
            val sb = StringBuilder()
            val refs = opf.getElementsByTagNameNS("*", "itemref")
            for (i in 0 until refs.length) {
                val item = manifest[(refs.item(i) as Element).getAttribute("idref")] ?: continue
                val path = resolvePath(opfPath, item.getAttribute("href"))
                val b = zip.bytes(path) ?: continue
                var t = decodeText(b)
                t = RX_BODY.find(t)?.groupValues?.get(1) ?: t.replace(Regex("<\\?xml[^>]*>|<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "")
                t = SELF_CLOSE.replace(t) { m -> "<${m.groupValues[1]}${m.groupValues[2]}></${m.groupValues[1]}>" }
                t = RX_IMG_TAG.replace(t) { m ->
                    RX_SRC.replace(m.value) { a ->
                        val name = a.groupValues[1].lowercase()
                        val v = a.groupValues[4].ifEmpty { a.groupValues[5] }
                        if (name == "href" && !m.value.startsWith("<image", true)) a.value
                        else if (v.startsWith("data:") || v.startsWith("http")) a.value
                        else "${a.groupValues[1]}${a.groupValues[2]}\"/z/${encPath(resolvePath(path, v))}\""
                    }
                }
                sb.append("<section data-p=\"").append(esc(path)).append("\">").append(t).append("</section>")
            }
            html = sb.toString()
            if (html.replace(Regex("<[^>]*>"), "").isBlank() && !html.contains("<img", true)) throw BookException("empty", "The EPUB has no readable content.")
        } catch (e: BookException) { zf.close(); throw e }
        catch (e: OutOfMemoryError) { zf.close(); throw BookException("memory", "Out of memory.") }
        catch (e: Exception) { zf.close(); throw BookException("invalid", e.message ?: "Invalid EPUB.") }
    }

    private fun findCover(opf: org.w3c.dom.Document, man: Map<String, Element>, opfPath: String): ByteArray? {
        var href: String? = null
        val metas = opf.getElementsByTagNameNS("*", "meta")
        for (i in 0 until metas.length) (metas.item(i) as Element).let {
            if (it.getAttribute("name") == "cover") man[it.getAttribute("content")]?.let { m -> if (href == null) href = m.getAttribute("href") }
        }
        if (href == null) for (e in man.values) if (href == null && e.getAttribute("properties").contains("cover-image")) href = e.getAttribute("href")
        if (href == null) for (e in man.values) {
            if (href == null && e.getAttribute("media-type").startsWith("image/") &&
                Regex("cover", RegexOption.IGNORE_CASE).containsMatchIn(e.getAttribute("id") + e.getAttribute("href"))) href = e.getAttribute("href")
        }
        val h = href ?: return null
        return zip.bytes(resolvePath(opfPath, h))
    }

    /** EPUB 3 navigation document first, then the EPUB 2 NCX. */
    private fun readToc(opf: org.w3c.dom.Document, man: Map<String, Element>, opfPath: String): List<TocItem> {
        val out = ArrayList<TocItem>()
        fun target(base: String, href: String): Pair<String, String> =
            resolvePath(base, href) to urlDecode(href.substringAfter('#', ""))
        val nav = man.values.firstOrNull { it.getAttribute("properties").split(' ').contains("nav") }
        if (nav != null) {
            val path = resolvePath(opfPath, nav.getAttribute("href"))
            val doc = parseXml(zip.bytes(path) ?: return out)
            val navs = doc.getElementsByTagNameNS("*", "nav")
            var toc: Element? = null
            for (i in 0 until navs.length) (navs.item(i) as Element).let { n ->
                if (toc == null && (n.getAttribute("epub:type") == "toc" || n.getAttributeNS("http://www.idpf.org/2007/ops", "type") == "toc")) toc = n
            }
            fun walk(n: Node, depth: Int) {
                var c = n.firstChild
                while (c != null) {
                    if (c is Element) when (c.localName) {
                        "ol", "ul" -> walk(c, depth + 1)
                        "li" -> {
                            val a = (0 until c.childNodes.length).map { c.childNodes.item(it) }.firstOrNull { it is Element && (it.localName == "a" || it.localName == "span") } as? Element
                            if (a != null && a.localName == "a") {
                                val (p, f) = target(path, a.getAttribute("href"))
                                val t = a.textContent.replace(Regex("\\s+"), " ").trim()
                                if (t.isNotEmpty()) out.add(TocItem(t, p, f, Math.max(0, depth - 1)))
                            }
                            walk(c, depth)
                        }
                    }
                    c = c.nextSibling
                }
            }
            toc?.let { walk(it, 0) }
            if (out.isNotEmpty()) return out
        }
        val ncx = man.values.firstOrNull { it.getAttribute("media-type") == "application/x-dtbncx+xml" } ?: return out
        val path = resolvePath(opfPath, ncx.getAttribute("href"))
        val doc = parseXml(zip.bytes(path) ?: return out)
        fun walkNcx(n: Node, depth: Int) {
            var c = n.firstChild
            while (c != null) {
                if (c is Element && c.localName == "navPoint") {
                    val label = (c.getElementsByTagNameNS("*", "text").item(0)?.textContent ?: "").replace(Regex("\\s+"), " ").trim()
                    val src = (c.getElementsByTagNameNS("*", "content").item(0) as? Element)?.getAttribute("src") ?: ""
                    if (label.isNotEmpty() && src.isNotEmpty()) { val (p, f) = target(path, src); out.add(TocItem(label, p, f, depth)) }
                    walkNcx(c, depth + 1)
                }
                c = c.nextSibling
            }
        }
        (doc.getElementsByTagNameNS("*", "navMap").item(0))?.let { walkNcx(it, 0) }
        return out
    }

    override fun contentHtml() = html
    override fun resource(path: String): Res? {
        val b = zip.bytes(path) ?: return null
        return Res(mimeByName(path), b)
    }
    override fun cover() = cover
    override fun toc() = tocItems
    override fun close() { try { zip.z.close() } catch (e: Exception) {} }
}

/** MOBI / AZW / PRC without DRM (PalmDOC + MOBI header). KF8-only AZW3 is not supported. */
class MobiBook(private val u8: ByteArray) : TextBook() {
    private val html: String
    private var firstImg = 0
    private var nrec = 0
    private val off = ArrayList<Int>()
    private var coverBytes: ByteArray? = null

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o) shl 16) or u16(b, o + 2)
    private fun rec(i: Int): ByteArray {
        val s = off[i]; val e = off[i + 1]
        return if (e > s && e <= u8.size) u8.copyOfRange(s, e) else ByteArray(0)
    }

    init {
        try {
            if (u8.size < 90) throw BookException("invalid", "File too small.")
            nrec = u16(u8, 76)
            if (nrec < 2 || 78 + nrec * 8 > u8.size) throw BookException("invalid", "Invalid MOBI.")
            for (i in 0 until nrec) off.add(u32(u8, 78 + i * 8))
            off.add(u8.size)
            val r0 = rec(0)
            if (r0.size < 16) throw BookException("invalid", "Invalid MOBI.")
            val comp = u16(r0, 0); val textLen = u32(r0, 4); val count = u16(r0, 8); val enc = u16(r0, 12)
            if (enc != 0) throw BookException("drm", "This book is protected by DRM and cannot be opened.")
            if (comp != 1 && comp != 2) throw BookException("compression", "Unsupported MOBI compression.")
            val isMobi = r0.size > 20 && String(r0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
            val hl = if (isMobi) u32(r0, 20) else 0
            val ver = if (isMobi && r0.size > 40) u32(r0, 36) else 0
            val codepage = if (isMobi && r0.size > 32) u32(r0, 28) else 1252
            if (ver == 8) throw BookException("kf8", "KF8-only AZW3.")
            firstImg = if (isMobi && hl >= 108 && r0.size > 112) u32(r0, 108) else 0
            val flags = if (isMobi && hl >= 228 && r0.size > 244) u16(r0, 242) else 0
            val bos = java.io.ByteArrayOutputStream()
            var k = 1
            while (k <= count && k < nrec) {
                val r = rec(k)
                var sz = r.size
                if (flags != 0) sz -= trailSize(r, sz, flags)
                if (sz < 0) sz = 0
                val part = r.copyOfRange(0, sz)
                bos.write(if (comp == 2) palm(part) else part)
                k++
            }
            var all = bos.toByteArray()
            if (textLen > 0 && all.size > textLen) all = all.copyOf(textLen)
            var t = if (codepage == 65001) String(all, Charsets.UTF_8) else String(all, charset("windows-1252"))
            t = Regex("<img\\b[^>]*?recindex=[\"']?0*(\\d+)[\"']?[^>]*>", RegexOption.IGNORE_CASE).replace(t) { m ->
                val n = m.groupValues[1].toIntOrNull() ?: 0
                if (image(n - 1) != null) "<img src=\"/m/${n - 1}\">" else ""
            }.replace(Regex("<mbp:pagebreak[^>]*>", RegexOption.IGNORE_CASE), "<hr>")
                .replace(Regex("</?mbp:[^>]*>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<a\\b[^>]*?filepos=[^>]*>", RegexOption.IGNORE_CASE), "<a>")
            html = t
            if (html.replace(Regex("<[^>]*>"), "").isBlank()) throw BookException("empty", "The book appears to be empty.")
            // cover: EXTH record 201, else a large early image
            var cov: ByteArray? = null
            if (isMobi && r0.size > 132 && (u32(r0, 128) and 0x40) != 0) {
                val ex = 16 + hl
                if (ex + 12 <= r0.size && String(r0, ex, 4, Charsets.ISO_8859_1) == "EXTH") {
                    val cnt = u32(r0, ex + 8); var q = ex + 12
                    for (e in 0 until cnt) {
                        if (q + 8 > r0.size) break
                        val ty = u32(r0, q); val ln = u32(r0, q + 4)
                        if (ty == 201 && ln >= 12 && q + 12 <= r0.size) cov = image(u32(r0, q + 8))
                        if (ln < 8) break
                        q += ln
                    }
                }
            }
            if (cov == null && firstImg > 0) {
                var z = firstImg
                while (z < nrec && z < firstImg + 8 && cov == null) { val rz = rec(z); if (mimeOf(rz).isNotEmpty() && rz.size > 4000) cov = rz; z++ }
            }
            coverBytes = cov
        } catch (e: BookException) { throw e }
        catch (e: OutOfMemoryError) { throw BookException("memory", "Out of memory.") }
        catch (e: Exception) { throw BookException("invalid", e.message ?: "Invalid MOBI.") }
    }

    private fun image(idx: Int): ByteArray? {
        val ri = firstImg + idx
        if (firstImg == 0 || idx < 0 || ri <= 0 || ri >= nrec) return null
        val r = rec(ri)
        return if (mimeOf(r).isNotEmpty()) r else null
    }

    override fun contentHtml() = html
    override fun resource(path: String): Res? {
        val n = path.toIntOrNull() ?: return null
        val b = image(n) ?: return null
        return Res(mimeOf(b), b)
    }
    override fun cover() = coverBytes

    companion object {
        fun palm(inp: ByteArray): ByteArray {
            var out = ByteArray(inp.size * 8 + 64); var o = 0; var i = 0; val n = inp.size
            fun put(b: Int) { if (o >= out.size) out = out.copyOf(out.size * 2); out[o++] = b.toByte() }
            while (i < n) {
                var c = inp[i++].toInt() and 0xFF
                if (c == 0 || (c in 9..0x7F)) put(c)
                else if (c in 1..8) { while (c-- > 0 && i < n) put(inp[i++].toInt() and 0xFF) }
                else if (c >= 0xC0) { put(0x20); put(c xor 0x80) }
                else {
                    val d = (c shl 8) or (if (i < n) inp[i++].toInt() and 0xFF else 0)
                    val dist = (d shr 3) and 0x7FF; var len = (d and 7) + 3
                    if (dist == 0) continue
                    while (len-- > 0) put(if (o - dist >= 0) out[o - dist].toInt() and 0xFF else 0)
                }
            }
            return out.copyOf(o)
        }

        fun trailSize(r: ByteArray, size: Int, flags: Int): Int {
            var num = 0; var f = flags shr 1
            while (f != 0) {
                if (f and 1 != 0) {
                    var bp = 0; var v = 0; var sz = size - num
                    if (sz > 0) while (true) {
                        val b = r[sz - 1].toInt() and 0xFF
                        v = v or ((b and 0x7F) shl bp); bp += 7; sz--
                        if ((b and 0x80) != 0 || bp >= 28 || sz == 0) break
                    }
                    num += v
                }
                f = f shr 1
            }
            if (flags and 1 != 0) { val idx = size - num - 1; if (idx >= 0) num += ((r[idx].toInt() and 0xFF) and 3) + 1 }
            return num
        }
    }
}

/** DOCX to simple HTML: headings, bold/italic/underline, lists, tables, images, links. */
class DocxBook(file: File) : TextBook() {
    private val zip: Zip
    private val html: String

    init {
        val zf = try { ZipFile(file) } catch (e: Exception) { throw BookException("invalid", "Not a valid DOCX.") }
        zip = Zip(zf)
        try {
            val docBytes = zip.bytes("word/document.xml") ?: throw BookException("invalid", "Not a valid DOCX.")
            val rels = HashMap<String, String>()
            zip.bytes("word/_rels/document.xml.rels")?.let { rb ->
                val ns = parseXml(rb).getElementsByTagNameNS("*", "Relationship")
                for (i in 0 until ns.length) (ns.item(i) as Element).let { rels[it.getAttribute("Id")] = it.getAttribute("Target") }
            }
            val ordered = HashMap<String, Boolean>()
            zip.bytes("word/numbering.xml")?.let { nb ->
                val d = parseXml(nb)
                val absFmt = HashMap<String, Boolean>()
                val abs = d.getElementsByTagNameNS("*", "abstractNum")
                for (i in 0 until abs.length) {
                    val a = abs.item(i) as Element
                    val lvl = a.getElementsByTagNameNS("*", "numFmt").item(0) as? Element
                    absFmt[a.getAttribute("w:abstractNumId")] = (lvl?.getAttribute("w:val") ?: "bullet") != "bullet"
                }
                val nums = d.getElementsByTagNameNS("*", "num")
                for (i in 0 until nums.length) {
                    val n = nums.item(i) as Element
                    val id = n.getAttribute("w:numId")
                    val ab = (n.getElementsByTagNameNS("*", "abstractNumId").item(0) as? Element)?.getAttribute("w:val") ?: ""
                    ordered[id] = absFmt[ab] ?: false
                }
            }
            val doc = parseXml(docBytes)
            val body = doc.getElementsByTagNameNS("*", "body").item(0) as? Element ?: throw BookException("invalid", "Not a valid DOCX.")
            val sb = StringBuilder()
            var openList: String? = null
            fun closeList() { if (openList != null) { sb.append("</").append(openList).append(">"); openList = null } }
            fun kids(n: Node, name: String): List<Element> { val r = ArrayList<Element>(); var c = n.firstChild; while (c != null) { if (c is Element && c.localName == name) r.add(c); c = c.nextSibling }; return r }
            fun on(e: Element?): Boolean { if (e == null) return false; val v = e.getAttribute("w:val"); return v != "0" && v != "false" && v != "none" }

            fun runsHtml(p: Element): String {
                val o = StringBuilder()
                fun run(r: Element, link: String?) {
                    val rpr = kids(r, "rPr").firstOrNull()
                    val b = rpr?.let { kids(it, "b").firstOrNull() }; val i = rpr?.let { kids(it, "i").firstOrNull() }; val u = rpr?.let { kids(it, "u").firstOrNull() }
                    val inner = StringBuilder()
                    var c = r.firstChild
                    while (c != null) {
                        if (c is Element) when (c.localName) {
                            "t" -> inner.append(esc(c.textContent))
                            "tab" -> inner.append("&emsp;")
                            "br", "cr" -> inner.append("<br>")
                            "drawing" -> {
                                val blips = c.getElementsByTagNameNS("*", "blip")
                                if (blips.length > 0) {
                                    val id = (blips.item(0) as Element).getAttribute("r:embed")
                                    rels[id]?.let { t -> inner.append("<img src=\"/z/").append(encPath(resolvePath("word/document.xml", t))).append("\">") }
                                }
                            }
                        }
                        c = c.nextSibling
                    }
                    var s = inner.toString()
                    if (s.isEmpty()) return
                    if (on(b)) s = "<b>$s</b>"; if (on(i)) s = "<i>$s</i>"; if (on(u)) s = "<u>$s</u>"
                    o.append(if (link != null) "<a href=\"${esc(link)}\">$s</a>" else s)
                }
                var c = p.firstChild
                while (c != null) {
                    if (c is Element) {
                        if (c.localName == "r") run(c, null)
                        else if (c.localName == "hyperlink") {
                            val t = rels[c.getAttribute("r:id")]
                            for (r in kids(c, "r")) run(r, t)
                        }
                    }
                    c = c.nextSibling
                }
                return o.toString()
            }

            fun block(e: Element) {
                when (e.localName) {
                    "p" -> {
                        val ppr = kids(e, "pPr").firstOrNull()
                        val style = ppr?.let { kids(it, "pStyle").firstOrNull() }?.getAttribute("w:val") ?: ""
                        val numPr = ppr?.let { kids(it, "numPr").firstOrNull() }
                        val content = runsHtml(e)
                        if (numPr != null) {
                            val numId = kids(numPr, "numId").firstOrNull()?.getAttribute("w:val") ?: ""
                            val tag = if (ordered[numId] == true) "ol" else "ul"
                            if (openList != tag) { closeList(); sb.append("<").append(tag).append(">"); openList = tag }
                            sb.append("<li>").append(content).append("</li>")
                        } else {
                            closeList()
                            val h = Regex("^(?:Heading|Titre)\\s?(\\d)$", RegexOption.IGNORE_CASE).find(style)?.groupValues?.get(1)?.toIntOrNull()
                            val tag = if (style.equals("Title", true)) "h1" else if (h != null) "h${Math.min(h, 6)}" else "p"
                            if (content.isEmpty()) sb.append("<p>&nbsp;</p>") else sb.append("<$tag>").append(content).append("</$tag>")
                        }
                    }
                    "tbl" -> {
                        closeList()
                        sb.append("<table>")
                        for (tr in kids(e, "tr")) {
                            sb.append("<tr>")
                            for (tc in kids(tr, "tc")) {
                                sb.append("<td>")
                                for (pp in kids(tc, "p")) sb.append(runsHtml(pp)).append("<br>")
                                sb.append("</td>")
                            }
                            sb.append("</tr>")
                        }
                        sb.append("</table>")
                    }
                }
            }
            var c = body.firstChild
            while (c != null) { if (c is Element) block(c); c = c.nextSibling }
            closeList()
            html = sb.toString()
            if (html.replace(Regex("<[^>]*>"), "").isBlank() && !html.contains("<img")) throw BookException("empty", "The document appears to be empty.")
        } catch (e: BookException) { zf.close(); throw e }
        catch (e: OutOfMemoryError) { zf.close(); throw BookException("memory", "Out of memory.") }
        catch (e: Exception) { zf.close(); throw BookException("invalid", e.message ?: "Invalid DOCX.") }
    }

    override fun contentHtml() = html
    override fun resource(path: String): Res? { val b = zip.bytes(path) ?: return null; return Res(mimeByName(path), b) }
    override fun close() { try { zip.z.close() } catch (e: Exception) {} }
}

fun openTextBook(kind: String, file: File): TextBook = when (kind) {
    "epub" -> EpubBook(file)
    "mobi" -> MobiBook(file.readBytes())
    "docx" -> DocxBook(file)
    "html" -> PlainBook(file, true)
    else -> PlainBook(file, false)
}
