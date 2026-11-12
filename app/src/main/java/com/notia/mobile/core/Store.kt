package com.notia.mobile.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class Note(val id: String, var title: String, html: String, var cat: String?, val created: Long, var modified: Long) {
    var html: String = html
        set(v) { field = v; text = plain(v); media = hasMedia(v) }
    var text: String = plain(html)
    var media: Boolean = hasMedia(html)
}

class Cat(val id: String, var name: String, val color: String)
class Task(val id: String, var title: String, var due: Long?, var done: Boolean, var armed: Boolean = false)
class Mark(val id: String, val pct: Double, val page: Int, val text: String?)
class Book(
    val id: String, var title: String, val kind: String, val file: String,
    var pct: Double = 0.0, var page: Int = 0, var total: Int = 0, var time: Long = 0, var last: Long = 0,
    val bm: MutableList<Mark> = ArrayList(), val hl: MutableList<Mark> = ArrayList(),
)

val CAT_COLORS = listOf("#8B6CF6", "#F2B33D", "#3DA5E0", "#EF6B9A", "#3FBF7F")

/** All app data. Mutate on the main thread only; disk writes are debounced on a worker thread. */
object Store {
    lateinit var dir: File
    val notes = ArrayList<Note>()
    val cats = ArrayList<Cat>()
    val tasks = ArrayList<Task>()
    val books = ArrayList<Book>()
    var lang = "en"
    var dark = true
    var purged = 0
    @Volatile var loaded = false
    private val listeners = ArrayList<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val dirty = LinkedHashSet<String>()
    private val deleted = LinkedHashSet<String>()
    private val flushRun = Runnable { flush() }

    fun listen(f: () -> Unit) { listeners.add(f) }
    fun unlisten(f: () -> Unit) { listeners.remove(f) }
    fun changed() { for (f in listeners.toList()) f() }

    @Synchronized fun ensureLoaded(ctx: Context) {
        if (loaded) return
        dir = ctx.applicationContext.filesDir
        File(dir, "notes").mkdirs(); File(dir, "covers").mkdirs()
        lang = if (Locale.getDefault().language == "fr") "fr" else "en"
        val state = File(dir, "state.json")
        if (state.exists()) readAll() else {
            try { migrate(ctx) } catch (e: Exception) { /* nothing to migrate */ }
            if (cats.isEmpty()) { cats.add(Cat(newId(), if (lang == "fr") "Personnel" else "Personal", CAT_COLORS[0])); cats.add(Cat(newId(), if (lang == "fr") "Travail" else "Work", CAT_COLORS[2])) }
            dirty.addAll(listOf("state", "tasks", "lib")); notes.forEach { dirty.add("n:" + it.id) }
            flush()
        }
        loaded = true
    }

    private fun readJson(f: File): Any? = try { if (f.exists()) org.json.JSONTokener(f.readText()).nextValue() else null } catch (e: Exception) { null }

    private fun readAll() {
        (readJson(File(dir, "state.json")) as? JSONObject)?.let { s ->
            lang = s.optString("lang", lang); dark = s.optBoolean("dark", true)
            s.optJSONArray("cats")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { cats.add(Cat(it.getString("id"), it.getString("name"), it.optString("color", CAT_COLORS[0]))) } }
        }
        File(dir, "notes").listFiles()?.forEach { f ->
            (readJson(f) as? JSONObject)?.let { o ->
                try { notes.add(Note(o.getString("id"), o.optString("title"), sanitize(o.optString("html")), o.optString("cat").ifEmpty { null }, o.optLong("created"), o.optLong("modified"))) } catch (e: Exception) {}
            }
        }
        notes.sortByDescending { it.modified }
        (readJson(File(dir, "tasks.json")) as? JSONArray)?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> tasks.add(Task(o.getString("id"), o.optString("title"), if (o.has("due") && !o.isNull("due")) o.getLong("due") else null, o.optBoolean("done"), o.optBoolean("armed"))) }
        }
        (readJson(File(dir, "library.json")) as? JSONArray)?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                val file = o.optString("file")
                if (File(dir, file).exists()) books.add(bookFrom(o)) else purged++
            }
        }
    }

    private fun marks(a: JSONArray?): MutableList<Mark> {
        val l = ArrayList<Mark>()
        if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { l.add(Mark(it.optString("id", newId()), it.optDouble("pct", 0.0), it.optInt("page"), it.optString("text").ifEmpty { null })) }
        return l
    }

    private fun bookFrom(o: JSONObject) = Book(o.getString("id"), o.optString("title", "Book"), o.optString("kind", "txt"), o.getString("file"),
        o.optDouble("pct", 0.0), o.optInt("page"), o.optInt("total"), o.optLong("time"), o.optLong("last"), marks(o.optJSONArray("bm")), marks(o.optJSONArray("hl")))

    // ---- Expo (AsyncStorage) migration: notes, categories, tasks and library carry over ----
    private fun migrate(ctx: Context) {
        val dbf = ctx.getDatabasePath("RKStorage")
        if (!dbf.exists()) return
        val db = SQLiteDatabase.openDatabase(dbf.path, null, SQLiteDatabase.OPEN_READONLY)
        val kv = HashMap<String, String>()
        try {
            db.rawQuery("SELECT key, value FROM catalystLocalStorage WHERE key IN ('notia:v1','notia:tasks','notia:lib')", null).use { c ->
                while (c.moveToNext()) kv[c.getString(0)] = c.getString(1)
            }
        } finally { db.close() }
        kv["notia:v1"]?.let { v ->
            val o = JSONObject(v)
            if (o.optString("lang") == "fr" || o.optString("lang") == "en") lang = o.optString("lang")
            dark = o.optString("mode", "dark") != "light"
            o.optJSONArray("cats")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { cats.add(Cat(it.getString("id"), it.getString("name"), it.optString("color", CAT_COLORS[0]))) } }
            o.optJSONArray("notes")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    notes.add(Note(it.getString("id"), it.optString("title"), sanitize(it.optString("html")), it.optString("cat").ifEmpty { null }, it.optLong("created"), it.optLong("modified")))
                }
            }
            notes.sortByDescending { it.modified }
        }
        kv["notia:tasks"]?.let { v ->
            val a = JSONArray(v)
            for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                tasks.add(Task(o.getString("id"), o.optString("title"), if (o.has("due") && !o.isNull("due")) o.getLong("due") else null, o.optBoolean("done")))
            }
        }
        kv["notia:lib"]?.let { v ->
            val a = JSONArray(v)
            for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                val file = o.optString("uri").substringAfterLast('/')
                if (file.isEmpty() || !File(dir, file).exists()) return@let
                val id = o.getString("id")
                val cov = o.optString("cover")
                if (cov.startsWith("data:") && !o.optBoolean("cg")) try {
                    File(dir, "covers/$id.jpg").writeBytes(android.util.Base64.decode(cov.substringAfter(','), android.util.Base64.DEFAULT))
                } catch (e: Exception) {}
                books.add(Book(id, o.optString("title", "Book"), o.optString("kind", "txt"), file, o.optDouble("pct", 0.0), o.optInt("page"), o.optInt("total"), o.optLong("time"), o.optLong("last"), marks(o.optJSONArray("bm")), marks(o.optJSONArray("hl"))))
            }
        }
    }

    // ---- persistence ----
    private fun mark(k: String) { dirty.add(k); handler.removeCallbacks(flushRun); handler.postDelayed(flushRun, 400) }
    fun saveState() = mark("state")
    fun saveTasks() = mark("tasks")
    fun saveLib() = mark("lib")
    fun saveNote(n: Note) = mark("n:" + n.id)

    private fun marksJson(l: List<Mark>) = JSONArray().also { a -> l.forEach { m -> a.put(JSONObject().put("id", m.id).put("pct", m.pct).put("page", m.page).put("text", m.text ?: "")) } }

    /** Serialises dirty data now and writes it on the worker thread. Call from onPause. */
    fun flush() {
        handler.removeCallbacks(flushRun)
        val jobs = ArrayList<Pair<File, String>>(); val dels = ArrayList<File>()
        for (k in dirty) when {
            k == "state" -> jobs.add(File(dir, "state.json") to JSONObject().put("lang", lang).put("dark", dark)
                .put("cats", JSONArray().also { a -> cats.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) } }).toString())
            k == "tasks" -> jobs.add(File(dir, "tasks.json") to JSONArray().also { a -> tasks.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("due", it.due ?: JSONObject.NULL).put("done", it.done).put("armed", it.armed)) } }.toString())
            k == "lib" -> jobs.add(File(dir, "library.json") to JSONArray().also { a -> books.forEach { b ->
                a.put(JSONObject().put("id", b.id).put("title", b.title).put("kind", b.kind).put("file", b.file).put("pct", b.pct).put("page", b.page).put("total", b.total)
                    .put("time", b.time).put("last", b.last).put("bm", marksJson(b.bm)).put("hl", marksJson(b.hl))) } }.toString())
            k.startsWith("n:") -> notes.firstOrNull { it.id == k.substring(2) }?.let { n ->
                jobs.add(File(dir, "notes/${n.id}.json") to JSONObject().put("id", n.id).put("title", n.title).put("html", n.html).put("cat", n.cat ?: "").put("created", n.created).put("modified", n.modified).toString())
            }
        }
        dirty.clear()
        for (id in deleted) dels.add(File(dir, "notes/$id.json"))
        deleted.clear()
        if (jobs.isEmpty() && dels.isEmpty()) return
        io.execute {
            for ((f, s) in jobs) try {
                val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(s)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (e: Exception) {}
            for (f in dels) f.delete()
        }
    }

    // ---- notes ----
    fun note(id: String) = notes.firstOrNull { it.id == id }

    fun addNote(cat: String?): Note {
        val n = Note(newId(), "", "", cat, System.currentTimeMillis(), System.currentTimeMillis())
        notes.add(0, n); saveNote(n); return n
    }

    fun touch(n: Note) { n.modified = System.currentTimeMillis(); notes.remove(n); notes.add(0, n); saveNote(n) }

    fun removeNote(id: String) { notes.removeAll { it.id == id }; deleted.add(id); dirty.remove("n:$id"); mark("state") }

    fun addCat(name: String): Cat {
        val c = Cat(newId(), name, CAT_COLORS[cats.size % CAT_COLORS.size]); cats.add(c); saveState(); return c
    }

    fun removeCat(id: String) {
        cats.removeAll { it.id == id }
        notes.filter { it.cat == id }.forEach { it.cat = null; saveNote(it) }
        saveState()
    }

    /** Merges notes received from another device; returns how many were added or updated. */
    fun importNotes(list: List<IncomingNote>): Int {
        var n = 0
        for (inc in list) {
            var catId: String? = null
            if (inc.cat.isNotEmpty()) {
                catId = (cats.firstOrNull { it.name.equals(inc.cat, true) } ?: addCat(inc.cat)).id
            }
            val ex = note(inc.id)
            if (ex != null) {
                if (inc.modified > ex.modified) { ex.title = inc.title; ex.html = inc.html; ex.cat = catId; ex.modified = inc.modified; saveNote(ex); n++ }
            } else { val nn = Note(inc.id, inc.title, inc.html, catId, inc.created, inc.modified); notes.add(nn); saveNote(nn); n++ }
        }
        notes.sortByDescending { it.modified }
        return n
    }

    fun cover(b: Book) = File(dir, "covers/${b.id}.jpg").takeIf { it.exists() }
    fun bookFile(b: Book) = File(dir, b.file)
}

class IncomingNote(val id: String, val title: String, val html: String, val cat: String, val created: Long, val modified: Long)
