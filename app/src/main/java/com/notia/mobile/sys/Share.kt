package com.notia.mobile.sys

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.OpenableColumns
import android.webkit.WebView
import android.webkit.WebViewClient
import com.notia.mobile.books.BookException
import com.notia.mobile.books.openTextBook
import com.notia.mobile.core.Book
import com.notia.mobile.core.Note
import com.notia.mobile.core.Store
import com.notia.mobile.core.esc
import com.notia.mobile.core.newId
import com.notia.mobile.doc.DocxWriter
import com.notia.mobile.doc.HtmlModel
import com.notia.mobile.doc.PdfWriter
import java.io.ByteArrayOutputStream
import java.io.File

/** Serves files from cache/share to the apps the user picks in the share sheet. Not exported. */
class ShareProvider : ContentProvider() {
    private fun dir() = File(context!!.cacheDir, "share")
    private fun resolve(uri: Uri): File {
        val f = File(dir(), uri.lastPathSegment ?: "").canonicalFile
        if (!f.path.startsWith(dir().canonicalPath + File.separator) || !f.isFile) throw java.io.FileNotFoundException()
        return f
    }
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = resolve(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

object Exporter {
    const val AUTH = "com.notia.mobile.share"
    const val MIME_PDF = "application/pdf"
    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private fun safeName(t: String, ext: String): String {
        val b = t.replace(Regex("[^\\p{L}\\p{N} _.-]"), "").trim().take(40).ifEmpty { "Note" }
        return "$b.$ext"
    }

    /** Re-encodes images the writers cannot embed (gif, webp, bmp) as PNG. */
    private val conv: (String, ByteArray) -> ByteArray? = { _, b ->
        BitmapFactory.decodeByteArray(b, 0, b.size)?.let { bmp ->
            val bg = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(bg).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            ByteArrayOutputStream().also { bg.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }

    fun docxBytes(n: Note) = DocxWriter.build(n.title, HtmlModel.parse(n.html, conv))
    fun pdfBytes(n: Note) = PdfWriter(n.title, HtmlModel.parse(n.html, conv)).build()

    fun write(ctx: Context, name: String, bytes: ByteArray): File {
        val d = File(ctx.cacheDir, "share").apply { mkdirs() }
        d.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        return File(d, name).also { it.writeBytes(bytes) }
    }

    fun fileFor(ctx: Context, n: Note, pdf: Boolean): File =
        write(ctx, safeName(n.title, if (pdf) "pdf" else "docx"), if (pdf) pdfBytes(n) else docxBytes(n))

    fun shareFile(a: Activity, f: File, mime: String) {
        val uri = Uri.parse("content://$AUTH/" + Uri.encode(f.name))
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(f.name, uri)
        a.startActivity(Intent.createChooser(i, f.name))
    }

    fun shareText(a: Activity, n: Note) {
        val t = (if (n.title.isNotBlank()) n.title + "\n\n" else "") + n.text
        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, n.title).putExtra(Intent.EXTRA_TEXT, t), n.title.ifEmpty { "Note" }))
    }

    /** Fallback when the native PDF writer fails: system print dialog ("Save as PDF"). */
    fun printNote(a: Activity, n: Note) {
        val wv = WebView(a)
        wv.settings.javaScriptEnabled = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String?) {
                val pm = a.getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print(n.title.ifEmpty { "Note" }, v.createPrintDocumentAdapter(n.title.ifEmpty { "Note" }), PrintAttributes.Builder().build())
            }
        }
        wv.loadDataWithBaseURL(null, "<html><head><meta charset=\"utf-8\"><style>body{font-family:sans-serif;font-size:12pt}img{max-width:100%}table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px}</style></head><body><h1>${esc(n.title)}</h1>${n.html}</body></html>", "text/html", "utf-8", null)
    }
}

/** Adds books to the library: copies the file, checks it looks right, extracts a cover and page count. */
object Library {
    const val MAX_BOOKS = 10
    private const val MAX_BYTES = 60L * 1024 * 1024
    val EXT = setOf("pdf", "epub", "mobi", "azw", "azw3", "prc", "docx", "txt", "html", "htm")

    class Fail(val code: String) : Exception(code)

    private fun kindOf(ext: String, mime: String): String? = when {
        ext == "pdf" || mime == "application/pdf" -> "pdf"
        ext == "epub" || mime == "application/epub+zip" -> "epub"
        ext in setOf("mobi", "azw", "azw3", "prc") || mime.contains("mobipocket") -> "mobi"
        ext == "docx" || mime.contains("wordprocessingml") -> "docx"
        ext == "txt" || mime == "text/plain" -> "txt"
        ext == "html" || ext == "htm" || mime == "text/html" -> "html"
        else -> null
    }

    private fun titleOf(name: String) = name.substringBeforeLast('.', name).replace('_', ' ').trim().ifEmpty { "Book" }.take(120)

    /** Display name and size of a picked or shared file (size -1 when unknown). */
    private fun meta(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Book"; var size = -1L
        try { ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).let { if (it >= 0) name = c.getString(it) ?: name }
            c.getColumnIndex(OpenableColumns.SIZE).let { if (it >= 0 && !c.isNull(it)) size = c.getLong(it) }
        } } } catch (e: Exception) {}
        return name to size
    }

    /** The library book this file was already imported as (same title and size), or null. */
    fun existing(ctx: Context, uri: Uri): Book? {
        val (name, size) = meta(ctx, uri)
        if (size <= 0) return null
        val t = titleOf(name)
        return Store.books.firstOrNull { it.title == t && Store.bookFile(it).length() == size }
    }

    /** Blocking; call from a worker thread. Throws Fail(code): limit, unsupported, big, empty, invalid. */
    fun import(ctx: Context, uri: Uri): Book {
        if (Store.books.size >= MAX_BOOKS) throw Fail("limit")
        val (name, size) = meta(ctx, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val kind = kindOf(ext, ctx.contentResolver.getType(uri) ?: "") ?: throw Fail("unsupported")
        if (size > MAX_BYTES) throw Fail("big")
        val id = newId()
        val fileName = "${System.currentTimeMillis()}.$kind"
        val f = File(Store.dir, fileName)
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { ins -> f.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var total = 0L
                while (true) { val r = ins.read(buf); if (r < 0) break; total += r; if (total > MAX_BYTES) throw Fail("big"); o.write(buf, 0, r) }
            } }
            if (f.length() == 0L) throw Fail("empty")
            val head = f.inputStream().use { it.readNBytesCompat(1100) }
            val ok = when (kind) {
                "pdf" -> String(head, Charsets.ISO_8859_1).contains("%PDF")
                "epub", "docx" -> head.size > 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                "mobi" -> head.size > 68 && String(head, 60, 8, Charsets.ISO_8859_1).let { it == "BOOKMOBI" || it == "TEXtREAd" }
                else -> true
            }
            if (!ok) throw Fail("invalid")
            val b = Book(id, titleOf(name), kind, fileName)
            prepare(b)
            return b
        } catch (e: Exception) { f.delete(); throw if (e is Fail) e else Fail("invalid") }
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val b = ByteArray(n); var got = 0
        while (got < n) { val r = read(b, got, n - got); if (r < 0) break; got += r }
        return b.copyOf(got)
    }

    /** Extracts page count (PDF) and cover. Best effort. */
    fun prepare(b: Book) {
        val f = Store.bookFile(b)
        try {
            if (b.kind == "pdf") {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    val r = PdfRenderer(pfd)
                    try {
                        b.total = r.pageCount
                        if (r.pageCount > 0) r.openPage(0).use { p ->
                            val w = 300; val h = Math.max(1, (w.toFloat() * p.height / p.width).toInt())
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp.eraseColor(Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            saveCover(b, bmp)
                        }
                    } finally { r.close() }
                }
            } else if (b.kind == "epub" || b.kind == "mobi") {
                openTextBook(b.kind, f).use { tb -> tb.cover()?.let { bytes -> decodeScaled(bytes, 300)?.let { saveCover(b, it) } } }
            }
        } catch (e: BookException) {
        } catch (e: Throwable) {}
    }

    private fun PdfRenderer.Page.use(block: (PdfRenderer.Page) -> Unit) { try { block(this) } finally { close() } }

    fun decodeScaled(bytes: ByteArray, w: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1; while (o.outWidth / (s * 2) >= w) s *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        return if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, Math.max(1, bmp.height * w / bmp.width), true) else bmp
    }

    private fun saveCover(b: Book, bmp: Bitmap) {
        File(Store.dir, "covers/${b.id}.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    fun delete(b: Book) {
        Store.bookFile(b).delete(); File(Store.dir, "covers/${b.id}.jpg").delete()
    }
}
