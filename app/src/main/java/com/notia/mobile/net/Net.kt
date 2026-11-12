package com.notia.mobile.net

import com.notia.mobile.core.IncomingNote
import com.notia.mobile.core.Note
import com.notia.mobile.core.Store
import com.notia.mobile.core.sanitize
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Wi-Fi transfer. Protocol (shared with the desktop app):
 *   POST http://<ip>:8765/notia/v1/notes   header X-Notia-Code: <4 digits shown on the receiver>
 *   body {"v":1,"notes":[{"id","title","html","category","created","modified"}]}  ->  200 {"ok":true,"count":N}
 * QR sync: the desktop shows http://<ip>:<port>/<32 hex>/notes ; the phone GETs it and imports the JSON.
 */
object Net {
    const val PORT = 8765
    private const val MAX_BODY = 20_000_000

    class Target(val host: String, val port: Int, val code: String)

    fun myIp(): String? {
        var fallback: String? = null
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) {
                    if (ni.name.startsWith("wlan") || ni.name.startsWith("ap")) return a.hostAddress
                    if (fallback == null) fallback = a.hostAddress
                }
            }
        } catch (e: Exception) {}
        return fallback
    }

    fun payload(notes: List<Note>): String {
        val a = JSONArray()
        for (n in notes) a.put(JSONObject().put("id", n.id).put("title", n.title).put("html", n.html)
            .put("category", Store.cats.firstOrNull { it.id == n.cat }?.name ?: JSONObject.NULL).put("created", n.created).put("modified", n.modified))
        return JSONObject().put("v", 1).put("notes", a).toString()
    }

    fun parseTargets(t: String): List<Target> = t.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { l ->
        val parts = l.split(Regex("\\s+"))
        val hp = parts[0].split(':')
        val host = hp[0]
        if (!Regex("^[\\w.\\-]+$").matches(host)) null else Target(host, hp.getOrNull(1)?.toIntOrNull() ?: PORT, parts.getOrElse(1) { "" })
    }

    fun clean(raw: String): List<IncomingNote> {
        val o = try { JSONObject(raw) } catch (e: Exception) { return emptyList() }
        if (o.optInt("v") != 1) return emptyList()
        val arr = o.optJSONArray("notes") ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<IncomingNote>()
        for (i in 0 until Math.min(arr.length(), 200)) {
            val n = arr.optJSONObject(i) ?: continue
            val id = n.optString("id").replace(Regex("[^\\w\\-]"), "").take(40)
            if (id.isEmpty()) continue
            fun ts(k: String): Long { val v = n.optLong(k, 0); return if (v > 0) v else now }
            out.add(IncomingNote(id, n.optString("title").take(200), sanitize(n.optString("html").take(5_000_000)),
                (if (n.isNull("category")) "" else n.optString("category")).take(30), ts("created"), ts("modified")))
        }
        return out
    }

    /** HTTP status, or 0 when unreachable. Blocking. */
    fun send(t: Target, body: String): Int = try {
        val c = URL("http://${t.host}:${t.port}/notia/v1/notes").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 10000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("X-Notia-Code", t.code)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        c.disconnect(); code
    } catch (e: Exception) { 0 }

    private fun privateHost(h: String): Boolean {
        if (h.endsWith(".local") || !h.contains('.')) return true
        val m = Regex("^(\\d+)\\.(\\d+)\\.\\d+\\.\\d+$").find(h) ?: return false
        val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }

    /** Validates a desktop sync QR value. Returns null when it is not one. */
    fun validSyncUrl(s: String): URL? = try {
        val u = URL(s.trim())
        if (u.protocol == "http" && Regex("^/[a-f0-9]{32}/notes$").matches(u.path) && privateHost(u.host)) u else null
    } catch (e: Exception) { null }

    fun fetchSync(u: URL): List<IncomingNote> {
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP " + c.responseCode)
            val bos = ByteArrayOutputStream(); val buf = ByteArray(8192)
            c.inputStream.use { while (true) { val r = it.read(buf); if (r < 0) break; bos.write(buf, 0, r); if (bos.size() > MAX_BODY) throw java.io.IOException("too large") } }
            return clean(bos.toString("UTF-8"))
        } finally { c.disconnect() }
    }

    /** Minimal HTTP receiver. onNotes runs on a worker thread; the caller must hop to the main thread. */
    class Server(private val code: String, private val onNotes: (List<IncomingNote>) -> Int, private val onErr: (String) -> Unit) {
        @Volatile private var running = false
        private var ss: ServerSocket? = null

        fun start(): Boolean {
            try { ss = ServerSocket(PORT, 8, InetAddress.getByName("0.0.0.0")).also { it.reuseAddress = true } } catch (e: Exception) { onErr(e.message ?: "bind"); return false }
            running = true
            Thread {
                while (running) try { val s = ss!!.accept(); Thread { handle(s) }.start() } catch (e: Exception) { if (running) onErr(e.message ?: "accept"); break }
            }.start()
            return true
        }

        fun stop() { running = false; try { ss?.close() } catch (e: Exception) {} }

        private fun reply(s: Socket, status: Int, text: String, json: String) {
            val b = json.toByteArray(Charsets.UTF_8)
            s.getOutputStream().apply { write("HTTP/1.1 $status $text\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(b); flush() }
        }

        private fun handle(s: Socket) {
            try {
                s.soTimeout = 10000
                val ins = s.getInputStream()
                val head = ByteArrayOutputStream(); var m = 0
                while (head.size() < 16384) { // read until CRLFCRLF
                    val b = ins.read(); if (b < 0) return
                    head.write(b)
                    m = if (b == (if (m % 2 == 0) 13 else 10)) m + 1 else if (b == 13) 1 else 0
                    if (m == 4) break
                }
                if (m != 4) { reply(s, 400, "Bad Request", "{\"ok\":false}"); return }
                val h = head.toString("ISO-8859-1")
                if (!Regex("^POST /notia/v1/notes ").containsMatchIn(h)) { reply(s, 404, "Not Found", "{\"ok\":false}"); return }
                val given = Regex("x-notia-code:[ \\t]*(\\S*)", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1) ?: ""
                if (!java.security.MessageDigest.isEqual(given.toByteArray(), code.toByteArray())) { reply(s, 401, "Unauthorized", "{\"ok\":false}"); return }
                val len = Regex("content-length:[ \\t]*(\\d+)", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                if (len <= 0 || len > MAX_BODY) { reply(s, 400, "Bad Request", "{\"ok\":false}"); return }
                val body = ByteArray(len.toInt()); var got = 0
                while (got < body.size) { val r = ins.read(body, got, body.size - got); if (r < 0) break; got += r }
                if (got < body.size) { reply(s, 400, "Bad Request", "{\"ok\":false}"); return }
                val list = clean(String(body, Charsets.UTF_8))
                if (list.isEmpty()) { reply(s, 400, "Bad Request", "{\"ok\":false}"); return }
                reply(s, 200, "OK", "{\"ok\":true,\"count\":${onNotes(list)}}")
            } catch (e: Exception) {
            } finally { try { s.close() } catch (e: Exception) {} }
        }
    }
}
