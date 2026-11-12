package com.notia.mobile.doc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.notia.mobile.core.CHART_PALETTE
import com.notia.mobile.core.fmtNum
import java.io.ByteArrayOutputStream

/** Draws the block model onto A4 pages with vector text. Runs on any thread. */
class PdfWriter(private val title: String, private val blocks: List<Block>) {
    private val pw = 595
    private val ph = 842
    private val m = 40f
    private val cw = pw - 2 * m
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var cv: Canvas = Canvas()
    private var y = m
    private var n = 0

    private fun newPage() {
        page?.let { doc.finishPage(it) }
        n++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, n).create())
        page = p; cv = p.canvas; y = m
    }

    private fun room() = ph - m - y
    private fun ensure(h: Float) { if (h > room() && y > m + 1) newPage() }

    private fun base() = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }

    private fun col(hex: String?, def: Int): Int = try { if (hex == null) def else Color.parseColor("#$hex") } catch (e: Exception) { def }

    private fun layout(text: CharSequence, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, base(), Math.max(20, width)).setLineSpacing(0f, 1.2f).build()

    private fun styled(prefix: String?, runs: List<Inline>): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        if (prefix != null) sb.append(prefix)
        for (r in runs) when (r) {
            is TextRun -> {
                val s = sb.length; sb.append(r.text); val e = sb.length
                if (e == s) continue
                val f = r.fmt; val fl = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                val style = (if (f.b) Typeface.BOLD else 0) or (if (f.i) Typeface.ITALIC else 0)
                if (style != 0) sb.setSpan(StyleSpan(style), s, e, fl)
                if (f.u) sb.setSpan(UnderlineSpan(), s, e, fl)
                if (f.code) sb.setSpan(TypefaceSpan("monospace"), s, e, fl)
                if (f.color != null) sb.setSpan(ForegroundColorSpan(col(f.color, Color.BLACK)), s, e, fl)
                if (f.bg != null) sb.setSpan(BackgroundColorSpan(col(f.bg, Color.YELLOW)), s, e, fl)
                if (f.size > 0) sb.setSpan(AbsoluteSizeSpan(f.size / 2), s, e, fl)
            }
            is LineBreak -> sb.append('\n')
            is ImageRun -> {}
        }
        return sb
    }

    /** Draws a layout, splitting it across pages on line boundaries. */
    private fun drawLayout(l: StaticLayout, x: Float) {
        var start = 0
        val count = l.lineCount
        while (start < count) {
            var end = start
            val top = l.getLineTop(start)
            while (end < count && l.getLineBottom(end) - top <= room()) end++
            if (end == start) { if (y > m + 1) { newPage(); continue }; end = start + 1 }
            val bottom = l.getLineBottom(end - 1)
            cv.save()
            cv.translate(x, y - top)
            cv.clipRect(0f, top.toFloat(), l.width.toFloat(), bottom.toFloat())
            l.draw(cv)
            cv.restore()
            y += (bottom - top)
            start = end
            if (start < count) newPage()
        }
    }

    private fun bitmapOf(b: ByteArray, maxW: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1; while (o.outWidth / (s * 2) >= maxW * 2) s *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Throwable) { null }

    private fun drawImage(im: ImageRun, avail: Float) {
        val bmp = bitmapOf(im.bytes, 1200) ?: return
        var w = Math.min(bmp.width.toFloat() * 0.75f, avail); var h = w * bmp.height / bmp.width
        val maxH = ph - 2 * m
        if (h > maxH) { w = w * maxH / h; h = maxH }
        ensure(h + 6)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        cv.drawBitmap(bmp, null, RectF(m, y, m + w, y + h), p)
        y += h + 6
        bmp.recycle()
    }

    private fun drawPara(p: Para, x: Float, width: Float, inCell: Boolean) {
        var seg = ArrayList<Inline>()
        var first = true
        fun flushSeg() {
            val ind = if (p.prefix != null) 14f * Math.max(1, p.listDepth) else if (p.quote) 18f else 0f
            val t = styled(if (first) p.prefix else null, seg)
            if (t.isNotEmpty()) {
                val l = layout(t, (width - ind).toInt())
                if (p.quote && !inCell) { ensure(Math.min(l.height.toFloat(), 40f)); val c = Paint().apply { color = Color.rgb(180, 170, 230) }; cv.drawRect(x + 4, y, x + 7, Math.min(y + l.height, ph - m), c) }
                drawLayout(l, x + ind)
            } else if (first && seg.isEmpty()) { y += 8f }
            first = false; seg = ArrayList()
        }
        for (r in p.runs) {
            if (r is ImageRun) { if (seg.isNotEmpty()) flushSeg(); if (!inCell) drawImage(r, width) }
            else seg.add(r)
        }
        flushSeg()
        y += if (inCell) 2f else 5f
    }

    private fun cellHeight(blocks: List<Block>, w: Float): Float {
        var h = 0f
        for (b in blocks) when (b) {
            is Para -> { val t = styled(b.prefix, b.runs.filter { it !is ImageRun }); h += (if (t.isEmpty()) 12f else layout(t, w.toInt()).height.toFloat()) + 2f }
            is ChartBlock -> h += 14f
            else -> {}
        }
        return Math.max(h, 14f)
    }

    private fun drawTable(t: TableBlock) {
        if (t.rows.isEmpty()) return
        val cols = Math.max(1, t.rows.maxOf { it.size })
        val colW = cw / cols
        val line = Paint().apply { color = Color.rgb(150, 150, 150); style = Paint.Style.STROKE; strokeWidth = 0.8f }
        for (row in t.rows) {
            val inner = colW - 10
            val rowH = (row.maxOfOrNull { cellHeight(it, inner) } ?: 14f) + 8f
            ensure(Math.min(rowH, ph - 2 * m))
            val top = y
            for (c in 0 until cols) {
                val x = m + c * colW
                cv.drawRect(x, top, x + colW, top + rowH, line)
                val cell = row.getOrNull(c) ?: continue
                y = top + 4
                for (b in cell) when (b) {
                    is Para -> drawCellPara(b, x + 5, inner, top + rowH)
                    else -> {}
                }
            }
            y = top + rowH
        }
        y += 8f
    }

    private fun drawCellPara(p: Para, x: Float, w: Float, limit: Float) {
        val t = styled(p.prefix, p.runs.filter { it !is ImageRun })
        if (t.isEmpty()) { y += 12f; return }
        val l = layout(t, w.toInt())
        cv.save(); cv.translate(x, y); cv.clipRect(0f, 0f, w, Math.max(0f, limit - y)); l.draw(cv); cv.restore()
        y += l.height + 2f
    }

    private fun drawChart(c: ChartBlock) {
        val h = 200f
        ensure(h + 10)
        val x0 = m; val top = y
        val tp = base().apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        cv.drawText(c.title.take(60), x0, top + 12, tp)
        val sp = base().apply { textSize = 8f; color = Color.rgb(60, 60, 60) }
        val pts = c.pts
        val max = Math.max(pts.maxOfOrNull { it.value } ?: 1.0, 1.0)
        val area = RectF(x0 + 10, top + 30, x0 + 330, top + 170)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fun color(i: Int) = CHART_PALETTE[i % CHART_PALETTE.size] or 0xFF000000.toInt()
        if (c.type == "pie") {
            val tot = pts.sumOf { it.value }.let { if (it == 0.0) 1.0 else it }
            val oval = RectF(x0 + 10, top + 30, x0 + 150, top + 170)
            var a = -90f
            pts.forEachIndexed { i, p ->
                val sweep = (p.value / tot * 360).toFloat()
                fill.color = color(i)
                if (sweep > 0) cv.drawArc(oval, a, sweep, true, fill)
                a += sweep
                fill.color = color(i); cv.drawRect(x0 + 170, top + 36 + i * 14, x0 + 178, top + 44 + i * 14, fill)
                cv.drawText("${p.label.take(16)} (${fmtNum(p.value)})", x0 + 184, top + 43.5f + i * 14, sp)
            }
        } else if (pts.isNotEmpty()) {
            val axis = Paint().apply { color = Color.GRAY; strokeWidth = 0.8f }
            cv.drawLine(area.left, area.bottom, area.right, area.bottom, axis)
            val bw = area.width() / pts.size
            fun px(i: Int) = area.left + (i + 0.5f) * bw
            fun py(v: Double) = (area.bottom - (v / max) * area.height()).toFloat()
            if (c.type == "line") {
                fill.color = 0xFF8B6CF6.toInt(); fill.strokeWidth = 2f; fill.style = Paint.Style.STROKE
                for (i in 1 until pts.size) cv.drawLine(px(i - 1), py(pts[i - 1].value), px(i), py(pts[i].value), fill)
                fill.style = Paint.Style.FILL
                pts.forEachIndexed { i, p -> cv.drawCircle(px(i), py(p.value), 3f, fill) }
            } else pts.forEachIndexed { i, p -> fill.color = color(i); cv.drawRect(px(i) - bw * 0.35f, py(p.value), px(i) + bw * 0.35f, area.bottom, fill) }
            sp.textAlign = Paint.Align.CENTER
            pts.forEachIndexed { i, p ->
                cv.drawText(p.label.take(Math.max(3, (bw / 4.5f).toInt())), px(i), area.bottom + 11, sp)
                cv.drawText(fmtNum(p.value), px(i), py(p.value) - 3, sp)
            }
        }
        y = top + h + 6
    }

    fun build(): ByteArray {
        try {
            newPage()
            val tp = base().apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
            val tl = StaticLayout.Builder.obtain(title.ifEmpty { "Untitled" }, 0, title.ifEmpty { "Untitled" }.length, tp, cw.toInt()).build()
            drawLayout(tl, m); y += 10
            for (b in blocks) when (b) {
                is Para -> drawPara(b, m, cw, false)
                is Rule -> { ensure(14f); val p = Paint().apply { color = Color.rgb(160, 160, 160); strokeWidth = 0.8f }; cv.drawLine(m, y + 5, m + cw, y + 5, p); y += 14f }
                is TableBlock -> drawTable(b)
                is ChartBlock -> drawChart(b)
            }
            page?.let { doc.finishPage(it) }; page = null
            val bos = ByteArrayOutputStream()
            doc.writeTo(bos)
            return bos.toByteArray()
        } finally { doc.close() }
    }
}
