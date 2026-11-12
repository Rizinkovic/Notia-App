package com.notia.mobile.doc

import com.notia.mobile.core.esc
import com.notia.mobile.core.fmtNum
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds a .docx (WordprocessingML) from the block model. No external libraries. */
object DocxWriter {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val X = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val NS = "xmlns:w=\"$W\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
    private val BAD_XML = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uFFFE\\uFFFF]")

    private fun x(s: String) = esc(BAD_XML.replace(s, ""))

    fun build(title: String, blocks: List<Block>): ByteArray {
        val rels = ArrayList<String>()
        val media = LinkedHashMap<String, ByteArray>()
        var rid = 0
        var mid = 0

        fun addRel(type: String, target: String, external: Boolean = false): String {
            val id = "rId" + (++rid)
            rels.add("<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type\" Target=\"${x(target)}\"${if (external) " TargetMode=\"External\"" else ""}/>")
            return id
        }

        fun runXml(t: String, f: Fmt, linkId: String?): String {
            val rpr = (if (f.code) "<w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\"/>" else "") +
                (if (f.b) "<w:b/>" else "") + (if (f.i) "<w:i/>" else "") +
                (if (f.color != null) "<w:color w:val=\"${f.color}\"/>" else "") +
                (if (f.size > 0) "<w:sz w:val=\"${f.size}\"/>" else "") +
                (if (f.u) "<w:u w:val=\"single\"/>" else "") +
                (if (f.bg != null) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${f.bg}\"/>" else "")
            val parts = t.split('\n')
            val inner = parts.indices.joinToString("") { k -> (if (k > 0) "<w:br/>" else "") + "<w:t xml:space=\"preserve\">${x(parts[k])}</w:t>" }
            val r = "<w:r><w:rPr>$rpr</w:rPr>$inner</w:r>"
            return if (linkId != null) "<w:hyperlink r:id=\"$linkId\" w:history=\"1\">$r</w:hyperlink>" else r
        }

        fun plainPara(t: String, f: Fmt = Fmt()) = "<w:p>${runXml(t, f, null)}</w:p>"

        fun imageXml(im: ImageRun): String {
            val n = ++mid
            val name = "img$n.${im.ext}"
            media["word/media/$name"] = im.bytes
            val id = addRel("image", "media/$name")
            val cx = if (im.w > 0) Math.min(im.w.toLong() * 9525L, 5400000L) else 4000000L
            val cy = if (im.w > 0) Math.round(cx.toDouble() * im.h / im.w) else 3000000L
            return "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$n\" name=\"Image $n\"/>" +
                "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                "<pic:blipFill><a:blip r:embed=\"$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
                "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
        }

        fun tableXml(rows: List<List<String>>): String {
            val cols = Math.max(1, rows.maxOfOrNull { it.size } ?: 1)
            val bd = listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>" }
            val grid = "<w:gridCol w:w=\"${9000 / cols}\"/>".repeat(cols)
            val trs = rows.joinToString("") { r ->
                val c = r.toMutableList(); while (c.size < cols) c.add("<w:p/>")
                "<w:tr>" + c.joinToString("") { "<w:tc><w:tcPr><w:tcW w:w=\"${5000 / cols}\" w:type=\"pct\"/></w:tcPr>$it</w:tc>" } + "</w:tr>"
            }
            return "<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>$bd</w:tblBorders></w:tblPr><w:tblGrid>$grid</w:tblGrid>$trs</w:tbl><w:p/>"
        }

        lateinit var blockXml: (Block) -> String

        fun paraXml(p: Para): String {
            val ppr = if (p.prefix != null) "<w:ind w:left=\"${360 * Math.max(1, p.listDepth)}\" w:hanging=\"260\"/>" else if (p.quote) "<w:ind w:left=\"720\"/>" else ""
            val sb = StringBuilder()
            if (p.prefix != null) sb.append(runXml(p.prefix, Fmt(), null))
            for (r in p.runs) when (r) {
                is TextRun -> sb.append(runXml(r.text, r.fmt, r.fmt.link?.let { addRel("hyperlink", it, true) }))
                is LineBreak -> sb.append("<w:r><w:br/></w:r>")
                is ImageRun -> sb.append(imageXml(r))
            }
            return "<w:p><w:pPr>$ppr</w:pPr>$sb</w:p>"
        }

        blockXml = { b ->
            when (b) {
                is Para -> paraXml(b)
                is Rule -> "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"888888\"/></w:pBdr></w:pPr></w:p>"
                is TableBlock -> tableXml(b.rows.map { row -> row.map { cell -> cell.joinToString("") { c -> blockXml(c) }.ifEmpty { "<w:p/>" } } })
                is ChartBlock -> plainPara("Chart: " + b.title, Fmt(b = true)) +
                    tableXml(b.pts.map { listOf(plainPara(it.label), plainPara(fmtNum(it.value))) }.ifEmpty { listOf(listOf("<w:p/>", "<w:p/>")) })
            }
        }

        val body = StringBuilder()
        body.append(plainPara(title.ifEmpty { "Untitled" }, Fmt(b = true, size = 44)))
        for (b in blocks) body.append(blockXml(b))

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
            fun put(name: String, data: String) = put(name, data.toByteArray(Charsets.UTF_8))
            put("[Content_Types].xml", "$X<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>")
            put("_rels/.rels", "$X<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
            put("word/_rels/document.xml.rels", "$X<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>${rels.joinToString("")}</Relationships>")
            put("word/styles.xml", "$X<w:styles xmlns:w=\"$W\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults></w:styles>")
            put("word/document.xml", "$X<w:document $NS><w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>")
            for ((n, d) in media) put(n, d)
        }
        return bos.toByteArray()
    }
}
