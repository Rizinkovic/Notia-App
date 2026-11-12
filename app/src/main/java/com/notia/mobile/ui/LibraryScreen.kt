package com.notia.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import com.notia.mobile.MainActivity
import com.notia.mobile.R
import com.notia.mobile.core.Book
import com.notia.mobile.core.Store
import com.notia.mobile.core.fmtTime
import com.notia.mobile.sys.Library
import com.notia.mobile.sys.Reminders
import java.io.File
import java.util.concurrent.Executors

fun importError(code: String) = when (code) {
    "unsupported" -> L("Unsupported file. Use PDF, EPUB, MOBI, DOCX, TXT or HTML.", "Fichier non pris en charge. Utilisez PDF, EPUB, MOBI, DOCX, TXT ou HTML.")
    "big" -> L("This file is too large (60 MB max).", "Ce fichier est trop volumineux (60 Mo max).")
    "empty" -> L("This file is empty.", "Ce fichier est vide.")
    "limit" -> L("Library is full (10 books max). Remove one first.", "Bibliothèque pleine (10 livres max). Retirez-en un d’abord.")
    else -> L("This file looks damaged or is not a valid book.", "Ce fichier semble endommagé ou n’est pas un livre valide.")
}

class LibraryScreen(private val a: MainActivity) : Screen() {
    private val root = a.vbox { setBackgroundColor(Th.bg) }
    private val list = ListView(a)
    private val stats = a.vbox()
    private val empty = a.text("", 15f, Th.sub).apply { gravity = Gravity.CENTER }
    private val covers = LruCache<String, Bitmap>(24)
    private var importing = false
    private val onData = { refresh() }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = Store.books.size
        override fun getItem(p: Int) = Store.books[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View = FrameLayout(a).apply {
            val b = Store.books[p]
            setPadding(0, a.dp(5), 0, a.dp(5))
            addView(a.hbox {
                background = a.pressable(Th.card, 20f, Th.line); setPadding(a.dp(12), a.dp(12), a.dp(14), a.dp(12))
                put(cover(b), a.dp(58), a.dp(82), 0f, 0, 0, 14, 0)
                put(a.vbox {
                    put(a.text(b.title, 16f, Th.ink, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    put(progressBar(b.pct), MATCH, a.dp(5), 0f, 0, 10, 0, 6)
                    val page = if (b.total > 0) "  •  p. ${Math.max(1, Math.round(b.pct * (b.total - 1)).toInt() + 1)}/${b.total}" else ""
                    put(a.text("${Math.round(b.pct * 100)}%  •  ${fmtTime(b.time)}$page", 12f, Th.sub))
                }, 0, WRAP, 1f)
                isClickable = true
                setOnClickListener { a.push(ReaderScreen(a, b.id)) }
                setOnLongClickListener { remove(b); true }
            }, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun progressBar(p: Double): View = FrameLayout(a).apply {
        background = a.rounded(Th.line, 3f)
        addView(View(a).apply { background = a.rounded(Th.accent, 3f) }, FrameLayout.LayoutParams(0, MATCH).also { lp -> post { lp.width = (width * p.coerceIn(0.0, 1.0)).toInt(); requestLayout() } })
    }

    private fun cover(b: Book): View {
        val f = Store.cover(b)
        val bmp = f?.let { covers.get(b.id) ?: BitmapFactory.decodeFile(it.path)?.also { d -> covers.put(b.id, d) } }
        return if (bmp != null) ImageView(a).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; clipRound(10f) }
        else FrameLayout(a).apply {
            background = a.rounded(Th.accent, 10f)
            addView(a.text(b.title.take(1).uppercase(), 24f, Th.accentInk, true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }
    }

    init {
        val head = a.hbox { setPadding(a.dp(20), a.dp(18), a.dp(20), 0) }
        head.put(a.text(L("Library", "Bibliothèque"), 30f, Th.ink, true), 0, WRAP, 1f)
        root.put(head)
        root.put(stats, MATCH, WRAP, 0f, 20, 14, 20, 8)
        list.divider = null; list.dividerHeight = 0; list.clipToPadding = false; list.selector = android.graphics.drawable.ColorDrawable(0)
        list.setPadding(a.dp(20), a.dp(4), a.dp(20), a.dp(170)); list.adapter = adapter
        val body = FrameLayout(a)
        body.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(empty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        val fab = FrameLayout(a).apply {
            background = a.rounded(Th.accent, 28f); elevation = a.dpf(8f); isClickable = true; contentDescription = L("Add book", "Ajouter un livre")
            addView(a.icon(R.drawable.ic_add, Th.accentInk, 26), FrameLayout.LayoutParams(a.dp(26), a.dp(26), Gravity.CENTER))
            setOnClickListener { addBook() }
        }
        body.addView(fab, FrameLayout.LayoutParams(a.dp(56), a.dp(56), Gravity.BOTTOM or Gravity.END).also { it.setMargins(0, 0, a.dp(24), a.dp(92)) })
        root.put(body, MATCH, 0, 1f)
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        stats.removeAllViews()
        val total = Store.books.sumOf { it.time }
        stats.put(a.hbox {
            background = a.rounded(Th.card, 20f, Th.line); setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
            put(a.vbox { put(a.text(L("Reading time", "Temps de lecture"), 12f, Th.sub)); put(a.text(fmtTime(total), 22f, Th.ink, true), MATCH, WRAP, 0f, 0, 4, 0, 0) }, 0, WRAP, 1f)
            put(a.vbox { put(a.text(L("Books", "Livres"), 12f, Th.sub)); put(a.text("${Store.books.size}/${Library.MAX_BOOKS}", 22f, Th.ink, true), MATCH, WRAP, 0f, 0, 4, 0, 0) }, 0, WRAP, 1f)
        })
        empty.text = if (Store.books.isEmpty()) L("Your library is empty.\nTap + to add a PDF, EPUB, MOBI, DOCX or text file.", "Votre bibliothèque est vide.\nAppuyez sur + pour ajouter un PDF, EPUB, MOBI, DOCX ou fichier texte.") else ""
    }

    private fun remove(b: Book) {
        a.confirm(L("Remove “${b.title}”?", "Retirer « ${b.title} » ?"), L("The book file and your reading progress are deleted from Notia.", "Le fichier du livre et votre progression sont supprimés de Notia."), L("Remove", "Retirer")) {
            Library.delete(b); Store.books.remove(b); Store.saveLib(); Reminders.scheduleReading(a); refresh()
        }
    }

    private fun addBook() {
        if (importing) return
        if (Store.books.size >= Library.MAX_BOOKS) { a.toast(L("Library is full (10 books max). Remove one first.", "Bibliothèque pleine (10 livres max). Retirez-en un d’abord.")); return }
        a.pick(arrayOf("application/pdf", "application/epub+zip", "application/x-mobipocket-ebook", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain", "text/html", "application/octet-stream")) { uri ->
            if (uri == null) return@pick
            importing = true; a.toast(L("Adding book…", "Ajout du livre…"))
            Thread {
                val res = try { Library.import(a, uri) } catch (e: Library.Fail) { e.code } catch (e: Throwable) { "invalid" }
                a.post(Runnable {
                    importing = false
                    if (res is Book) { Store.books.add(0, res); Store.saveLib(); refresh() }
                    else a.toast(importError(res as String))
                })
            }.start()
        }
    }

    override fun view(): View = root
    override fun onShow() {
        Store.listen(onData); refresh()
        if (Store.purged > 0) { a.toast(L("${Store.purged} book(s) were missing from storage and removed.", "${Store.purged} livre(s) introuvable(s) ont été retirés.")); Store.purged = 0; Store.saveLib() }
    }
    override fun onHide() { Store.unlisten(onData) }
}

/** Scrollable PDF viewer using the built-in PdfRenderer. Pages render lazily near the viewport. */
class PdfScroll(
    private val a: MainActivity, private val file: File, private val start: Double,
    private val onProgress: (Double) -> Unit, private val onReady: (Int) -> Unit, private val onError: () -> Unit,
) : ScrollView(a) {
    private class PageInfo(val w: Int, val h: Int)
    private val io = Executors.newSingleThreadExecutor()
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val views = ArrayList<ImageView>()
    private val tops = ArrayList<Int>()
    private val pending = HashSet<Int>()
    private val cache = object : LruCache<Int, Bitmap>(Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(k: Int, v: Bitmap) = v.byteCount
    }
    private var built = false
    private var closed = false

    init {
        isVerticalScrollBarEnabled = true; setBackgroundColor(Th.bg)
        addView(box, FrameLayout.LayoutParams(MATCH, WRAP))
        addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ -> if (!built && r - l > 0) { built = true; open(r - l) } }
    }

    private fun open(viewW: Int) {
        io.execute {
            try {
                val p = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(p)
                pfd = p; renderer = r
                val sizes = (0 until r.pageCount).map { i -> val pg = r.openPage(i); try { PageInfo(pg.width, pg.height) } finally { pg.close() } }
                if (sizes.isEmpty()) throw java.io.IOException("empty")
                a.post(Runnable { if (!closed) build(sizes, viewW) })
            } catch (e: Throwable) { a.post(Runnable { if (!closed) onError() }) }
        }
    }

    private fun build(sizes: List<PageInfo>, viewW: Int) {
        val pad = a.dp(8); val pw = viewW - 2 * pad
        var y = 0
        for (s in sizes) {
            val h = Math.max(1, (pw.toLong() * s.h / s.w).toInt())
            val iv = ImageView(a).apply { setBackgroundColor(Color.WHITE); scaleType = ImageView.ScaleType.FIT_XY }
            box.addView(iv, LinearLayout.LayoutParams(pw, h).also { it.setMargins(pad, pad, pad, 0) })
            tops.add(y + pad); y += h + pad; views.add(iv)
        }
        box.setPadding(0, 0, 0, a.dp(140))
        onReady(sizes.size)
        box.addOnLayoutChangeListener(object : OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int) {
                box.removeOnLayoutChangeListener(this)
                scrollTo(0, (start.coerceIn(0.0, 1.0) * room()).toInt()); show()
            }
        })
    }

    private fun room() = Math.max(1, box.height - height)
    fun fraction() = (scrollY.toDouble() / room()).coerceIn(0.0, 1.0)
    fun jump(p: Double) { scrollTo(0, (p.coerceIn(0.0, 1.0) * room()).toInt()) }

    override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) {
        super.onScrollChanged(l, t, ol, ot)
        if (views.isEmpty()) return
        onProgress(fraction()); show()
    }

    /** Renders pages within one screen of the viewport and releases far-away ones. */
    private fun show() {
        val top = scrollY - height; val bottom = scrollY + 2 * height
        for (i in views.indices) {
            val pt = tops[i]; val pb = pt + views[i].layoutParams.height
            if (pb < top || pt > bottom) { if (pb < top - 2 * height || pt > bottom + 2 * height) views[i].setImageDrawable(null); continue }
            val bmp = cache.get(i)
            if (bmp != null) { if (views[i].drawable == null) views[i].setImageBitmap(bmp) }
            else if (pending.add(i)) render(i)
        }
    }

    private fun render(i: Int) {
        val w = views[i].layoutParams.width; val h = views[i].layoutParams.height
        io.execute {
            var bmp: Bitmap? = null
            try {
                val r = renderer
                if (r != null && !closed) {
                    val pg = r.openPage(i)
                    try { bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE); pg.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } } finally { pg.close() }
                }
            } catch (e: Throwable) { bmp = null }
            a.post(Runnable { pending.remove(i); if (bmp != null && !closed) { cache.put(i, bmp); show() } })
        }
    }

    fun close() {
        closed = true
        io.execute { try { renderer?.close(); pfd?.close() } catch (e: Exception) {} }
        io.shutdown()
    }
}
