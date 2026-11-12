package com.notia.mobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.notia.mobile.MainActivity
import com.notia.mobile.R
import com.notia.mobile.books.BookException
import com.notia.mobile.books.TextBook
import com.notia.mobile.books.TocItem
import com.notia.mobile.books.openTextBook
import com.notia.mobile.core.Book
import com.notia.mobile.core.Mark
import com.notia.mobile.core.Store
import com.notia.mobile.core.fmtTime
import com.notia.mobile.core.newId
import com.notia.mobile.sys.Reminders
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

class ReaderScreen(private val a: MainActivity, bookId: String) : Screen() {
    private val book: Book? = Store.books.firstOrNull { it.id == bookId }
    private val root = FrameLayout(a).apply { setBackgroundColor(Th.bg) }
    private val content = FrameLayout(a)
    private val stats = a.text("", 12f, Th.sub)
    private val bar = FrameLayout(a)
    private val ticker = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var pdf: PdfScroll? = null
    private var tb: TextBook? = null
    private var toc: List<TocItem> = emptyList()
    private var pct = book?.pct ?: 0.0
    private var loaded = false
    private var visible = false
    private var destroyed = false
    private val page = Pages.nonce()

    private val tick = object : Runnable {
        override fun run() {
            val b = book ?: return
            if (loaded && visible && a.resumed) { b.time += 5; b.last = System.currentTimeMillis(); Store.saveLib(); updateStats() }
            if (!destroyed) ticker.postDelayed(this, 5000)
        }
    }

    init {
        val b = book
        val col = a.vbox()
        val top = a.hbox { setPadding(a.dp(8), a.dp(8), a.dp(16), a.dp(4)) }
        top.put(a.iconBtn(R.drawable.ic_back, L("Back", "Retour"), Th.ink, 24) { a.pop() })
        top.put(a.vbox {
            put(a.text(b?.title ?: "", 15f, Th.ink, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            put(stats, MATCH, WRAP, 0f, 0, 3, 0, 0)
        }, 0, WRAP, 1f)
        col.put(top)
        col.put(content, MATCH, 0, 1f)
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        val pill = a.hbox {
            background = a.rounded(Th.p.nav, 28f); elevation = a.dpf(10f); setPadding(a.dp(8), 0, a.dp(8), 0)
            put(a.iconBtn(R.drawable.ic_pen, L("Highlight", "Surligner"), 0xFFF2B33D.toInt()) { highlight() })
            put(a.iconBtn(R.drawable.ic_bookmark, L("Bookmark", "Marque-page"), 0xFFFFFFFF.toInt()) { bookmark() })
            put(a.iconBtn(R.drawable.ic_bullets, L("Contents, bookmarks and highlights", "Sommaire, marque-pages et surlignages"), 0xFFFFFFFF.toInt()) { panel(0) })
        }
        bar.addView(pill, FrameLayout.LayoutParams(WRAP, a.dp(56), Gravity.CENTER))
        root.addView(bar, FrameLayout.LayoutParams(WRAP, a.dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also { it.bottomMargin = a.dp(20) })
        updateStats()
        if (b == null) message(L("This book is no longer in the library.", "Ce livre n’est plus dans la bibliothèque."), false) else open(b)
    }

    private fun updateStats() {
        val b = book ?: return
        val pg = if (b.total > 0) "  •  p. ${Math.max(1, Math.round(pct * (b.total - 1)).toInt() + 1)}/${b.total}" else ""
        stats.text = "${Math.round(pct * 100)}%$pg  •  ${fmtTime(b.time)}"
    }

    private fun message(msg: String, remove: Boolean) {
        content.removeAllViews()
        content.addView(a.vbox {
            gravity = Gravity.CENTER; setPadding(a.dp(32), 0, a.dp(32), 0)
            put(a.text(msg, 15f, Th.sub).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) })
            if (remove && book != null) put(a.pill(L("Remove from library", "Retirer de la bibliothèque"), true, true) {
                com.notia.mobile.sys.Library.delete(book); Store.books.remove(book); Store.saveLib(); a.pop()
            }, WRAP, WRAP, 0f, 0, 20, 0, 0)
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        bar.visibility = View.GONE
    }

    private fun errorText(code: String) = when (code) {
        "drm" -> L("This book is protected by DRM and cannot be opened.", "Ce livre est protégé par DRM et ne peut pas être ouvert.")
        "kf8" -> L("This AZW3 uses a format Notia cannot read yet. Convert it to EPUB and add it again.", "Cet AZW3 utilise un format que Notia ne sait pas encore lire. Convertissez-le en EPUB puis ajoutez-le de nouveau.")
        "empty" -> L("This book appears to be empty.", "Ce livre semble vide.")
        "memory" -> L("This book is too large to open on this phone.", "Ce livre est trop volumineux pour être ouvert sur ce téléphone.")
        else -> L("This file could not be opened. It may be damaged.", "Ce fichier n’a pas pu être ouvert. Il est peut-être endommagé.")
    }

    private fun open(b: Book) {
        val file = Store.bookFile(b)
        content.addView(android.widget.ProgressBar(a).apply { isIndeterminate = true }, FrameLayout.LayoutParams(a.dp(32), a.dp(32), Gravity.CENTER))
        if (b.kind == "pdf") {
            val p = PdfScroll(a, file, pct, { f -> progress(f) }, { n -> if (b.total != n) { b.total = n; Store.saveLib() }; loaded = true; updateStats() },
                { message(L("This PDF could not be opened. It may be damaged or password-protected.", "Ce PDF n’a pas pu être ouvert. Il est peut-être endommagé ou protégé par mot de passe."), true) })
            pdf = p
            // PdfScroll must be attached to get a width before it starts loading
            content.removeAllViews(); content.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            Thread {
                try {
                    val t = openTextBook(b.kind, file)
                    val items = t.toc()
                    a.post(Runnable { if (destroyed) t.close() else { tb = t; toc = items; startWeb(b) } })
                } catch (e: BookException) { a.post(Runnable { message(errorText(e.code), true) })
                } catch (e: Throwable) { a.post(Runnable { message(errorText("invalid"), true) }) }
            }.start()
        }
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun ok(mime: String, data: ByteArray) = WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(data))

    private inner class Bridge {
        @JavascriptInterface fun loaded() { a.post(Runnable {
            this@ReaderScreen.loaded = true
            for (i in content.childCount - 1 downTo 0) if (content.getChildAt(i) is android.widget.ProgressBar) content.removeViewAt(i)
            val hl = JSONArray(); book?.hl?.forEach { m -> m.text?.let { hl.put(it) } }
            web?.evaluateJavascript("applyHl($hl)", null)
        }) }
        @JavascriptInterface fun progress(p: Double) { a.post(Runnable { this@ReaderScreen.progress(p) }) }
        @JavascriptInterface fun highlight(text: String) { a.post(Runnable { addHighlight(text) }) }
        @JavascriptInterface fun link(url: String) { a.post(Runnable { try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {} }) }
        @JavascriptInterface fun error() { a.post(Runnable { message(errorText("invalid"), true) }) }
    }

    private fun startWeb(b: Book) {
        val t = tb ?: return
        val w = WebView(a)
        web = w
        w.setBackgroundColor(Th.bg)
        w.settings.apply { javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false; setSupportZoom(false); blockNetworkLoads = false; mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW }
        w.addJavascriptInterface(Bridge(), "Notia")
        val html = Pages.load(a, "reader.html", mapOf("%NONCE%" to page, "%BG%" to cssColor(Th.bg), "%INK%" to cssColor(Th.ink), "%SUB%" to cssColor(Th.sub),
            "%ACCENT%" to cssColor(Th.accent), "%HL%" to "#FFE08A", "%LOADING%" to L("Opening…", "Ouverture…"))).toByteArray(Charsets.UTF_8)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url
                if (u.scheme != "https" || u.host != "notia.local") return blocked()
                val path = u.path ?: return blocked()
                return try {
                    when {
                        path == "/reader.html" -> ok("text/html", html)
                        path == "/content.html" -> ok("text/html", t.contentHtml().toByteArray(Charsets.UTF_8))
                        path.startsWith("/z/") || path.startsWith("/m/") -> t.resource(path.substring(3))?.let { WebResourceResponse(it.mime, null, 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(it.data)) } ?: blocked()
                        else -> blocked()
                    }
                } catch (e: Exception) { blocked() }
            }
        }
        content.addView(w, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        w.loadUrl("https://notia.local/reader.html?p=" + pct)
    }

    private fun progress(p: Double) { pct = p.coerceIn(0.0, 1.0); book?.let { it.pct = pct; Store.saveLib() }; updateStats() }

    private fun jump(p: Double) { pdf?.jump(p); web?.evaluateJavascript("jump($p)", null) }

    private fun addHighlight(text: String) {
        val b = book ?: return
        b.hl.add(Mark(newId(), pct, 0, text)); Store.saveLib(); a.toast(L("Highlight saved.", "Surlignage enregistré."))
    }

    private fun highlight() {
        if (book?.kind == "pdf") { a.toast(L("Highlighting is not available in PDFs. Use bookmarks instead.", "Le surlignage n’est pas disponible dans les PDF. Utilisez les marque-pages.")); return }
        web?.evaluateJavascript("hl()") { v -> if (v != "true") a.toast(L("Select some text first, then tap Highlight.", "Sélectionnez d’abord du texte, puis touchez Surligner.")) }
    }

    private fun bookmark() {
        val b = book ?: return
        if (b.bm.any { Math.abs(it.pct - pct) < 0.004 }) { a.toast(L("Already bookmarked.", "Déjà dans les marque-pages.")); return }
        val pg = if (b.total > 0) Math.round(pct * (b.total - 1)).toInt() + 1 else 0
        b.bm.add(Mark(newId(), pct, pg, null)); b.bm.sortBy { it.pct }; Store.saveLib(); a.toast(L("Bookmark added.", "Marque-page ajouté."))
    }

    // ---- panel: contents, bookmarks, highlights ----
    private fun panel(tab: Int) {
        val b = book ?: return
        val s = Sheet(a)
        val tabs = a.hbox(); val list = a.vbox()
        s.body.put(tabs, MATCH, WRAP, 0f, 0, 0, 0, 12); s.body.put(list)
        fun row(label: String, sub: String?, indent: Int, onTap: () -> Unit, onDelete: (() -> Unit)?) = list.put(a.hbox {
            background = a.pressable(Th.card, 14f, Th.line); setPadding(a.dp(14 + indent * 14), a.dp(10), a.dp(6), a.dp(10)); minimumHeight = a.dp(48)
            put(a.vbox {
                put(a.text(label, 15f, Th.ink, indent == 0).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (sub != null) put(a.text(sub, 12f, Th.sub), MATCH, WRAP, 0f, 0, 2, 0, 0)
            }, 0, WRAP, 1f)
            if (onDelete != null) put(a.iconBtn(R.drawable.ic_delete, L("Delete", "Supprimer"), Th.sub, 18) { onDelete() })
            isClickable = true; setOnClickListener { s.dismiss(); onTap() }
        }, MATCH, WRAP, 0f, 0, 0, 0, 6)
        fun hint(t: String) = list.put(a.text(t, 14f, Th.sub).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, MATCH, WRAP, 0f, 0, 12, 0, 12)
        fun pageOf(m: Mark) = if (b.total > 0) "p. ${if (m.page > 0) m.page else Math.round(m.pct * (b.total - 1)).toInt() + 1}" else "${Math.round(m.pct * 100)}%"

        fun render(t: Int) {
            tabs.removeAllViews(); list.removeAllViews()
            for ((i, n) in listOf(L("Contents", "Sommaire"), L("Bookmarks", "Marque-pages"), L("Highlights", "Surlignages")).withIndex())
                tabs.put(a.chip(n, i == t, 0, { render(i) }), WRAP, WRAP, 0f, 0, 0, 8, 0)
            when (t) {
                0 -> if (b.kind == "pdf") {
                    hint(L("PDF files do not carry a table of contents here. Jump to any page:", "Les PDF n’ont pas de sommaire ici. Allez à une page :"))
                    val f = a.field(L("Page (1–${b.total})", "Page (1–${b.total})")).apply { inputType = InputType.TYPE_CLASS_NUMBER }
                    list.put(f, MATCH, WRAP, 0f, 0, 0, 0, 10)
                    list.put(a.pill(L("Go", "Aller")) {
                        val n = f.text.toString().toIntOrNull()
                        if (n != null && n in 1..Math.max(1, b.total)) { s.dismiss(); jump(if (b.total > 1) (n - 1).toDouble() / (b.total - 1) else 0.0) }
                    })
                } else if (toc.isNotEmpty()) toc.forEach { it -> row(it.title, null, Math.min(it.level, 3), { goTo(it.path, it.frag) }, null) }
                else {
                    hint(L("Loading…", "Chargement…"))
                    web?.evaluateJavascript("headings()") { v ->
                        try {
                            val arr = JSONArray(JSONTokener(v).nextValue() as String)
                            if (!s.isShowing) return@evaluateJavascript
                            list.removeAllViews()
                            if (arr.length() == 0) hint(L("This book has no table of contents.", "Ce livre n’a pas de sommaire."))
                            for (i in 0 until arr.length()) arr.getJSONObject(i).let { o -> row(o.getString("t"), null, Math.min(o.getInt("l"), 3), { goTo("", o.getString("f")) }, null) }
                        } catch (e: Exception) { list.removeAllViews(); hint(L("This book has no table of contents.", "Ce livre n’a pas de sommaire.")) }
                    }
                }
                1 -> { if (b.bm.isEmpty()) hint(L("No bookmarks yet. Tap the bookmark button while reading.", "Aucun marque-page. Touchez le bouton marque-page pendant la lecture."))
                    b.bm.forEach { m -> row(pageOf(m), "${Math.round(m.pct * 100)}%", 0, { jump(m.pct) }, { b.bm.remove(m); Store.saveLib(); render(1) }) } }
                else -> { if (b.hl.isEmpty()) hint(L("No highlights yet. Select text, then tap the pen.", "Aucun surlignage. Sélectionnez du texte puis touchez le stylo."))
                    b.hl.forEach { m -> row(m.text ?: "", pageOf(m), 0, { jump(m.pct) }, { b.hl.remove(m); Store.saveLib(); render(2) }) } }
            }
        }
        render(tab)
        s.show()
    }

    private fun goTo(path: String, frag: String) { web?.evaluateJavascript("goTo(${JSONObject.quote(path)},${JSONObject.quote(frag)})", null) }

    override fun view(): View = root
    override fun onShow() { visible = true; ticker.removeCallbacks(tick); ticker.postDelayed(tick, 5000) }
    override fun onHide() { visible = false; Store.flush() }

    override fun onDestroy() {
        destroyed = true; ticker.removeCallbacks(tick)
        pdf?.close(); pdf = null
        web?.let { w -> (w.parent as? ViewGroup)?.removeView(w); w.removeJavascriptInterface("Notia"); w.destroy() }; web = null
        tb?.close(); tb = null
        Store.flush(); Store.changed()
        Reminders.scheduleReading(a)
    }
}
