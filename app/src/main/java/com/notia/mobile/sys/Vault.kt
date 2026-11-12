package com.notia.mobile.sys

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.notia.mobile.core.newId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** QR scanning through Google Play services (no camera permission, no bundled ML model). */
object Qr {
    fun scan(ctx: Context, onValue: (String) -> Unit, onFail: (String) -> Unit) {
        try {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(ctx, opts).startScan()
                .addOnSuccessListener { b -> val v = b.rawValue; if (v != null) onValue(v) else onFail("empty") }
                .addOnFailureListener { e -> onFail(e.message ?: "error") }
        } catch (e: Exception) { onFail(e.message ?: "error") }
    }
}

class VaultEntry(val id: String, var site: String, var user: String, var pass: String)

/**
 * Password vault. Entries are encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (never leaves secure hardware where available); the PIN or password only unlocks the screen and is
 * stored as a salted PBKDF2 hash. Wrong attempts are counted on disk, so leaving the screen does not reset them.
 */
object Vault {
    private const val ALIAS = "notia_vault_key"
    private const val ITER = 120_000

    private fun meta(ctx: Context) = File(ctx.filesDir, "vault.json")
    private fun data(ctx: Context) = File(ctx.filesDir, "vault.bin")
    private fun readMeta(ctx: Context): JSONObject = try { JSONObject(meta(ctx).readText()) } catch (e: Exception) { JSONObject() }
    private fun writeMeta(ctx: Context, o: JSONObject) { val t = File(ctx.filesDir, "vault.json.tmp"); t.writeText(o.toString()); t.renameTo(meta(ctx)) }

    fun isSet(ctx: Context) = readMeta(ctx).has("hash")

    private fun hash(secret: String, salt: ByteArray, alg: String): ByteArray =
        SecretKeyFactory.getInstance(alg).generateSecret(PBEKeySpec(secret.toCharArray(), salt, ITER, 256)).encoded

    private fun alg() = if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** 5 digits, or at least 6 characters with a letter and a digit. */
    fun valid(s: String) = Regex("^\\d{5}$").matches(s) || (s.length >= 6 && s.any { it.isLetter() } && s.any { it.isDigit() })

    fun setSecret(ctx: Context, secret: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val a = alg()
        writeMeta(ctx, JSONObject().put("salt", hex(salt)).put("hash", hex(hash(secret, salt, a))).put("alg", a).put("fails", 0).put("until", 0L))
    }

    /** Seconds the user must still wait, 0 when allowed to try. */
    fun waitSeconds(ctx: Context): Long = Math.max(0L, (readMeta(ctx).optLong("until") - System.currentTimeMillis() + 999) / 1000)

    fun verify(ctx: Context, secret: String): Boolean {
        val m = readMeta(ctx)
        if (!m.has("hash") || waitSeconds(ctx) > 0) return false
        val ok = try { MessageDigest.isEqual(hash(secret, unhex(m.getString("salt")), m.optString("alg", alg())), unhex(m.getString("hash"))) } catch (e: Exception) { false }
        if (ok) { m.put("fails", 0).put("until", 0L) }
        else {
            val f = m.optInt("fails") + 1
            m.put("fails", f)
            if (f >= 5) m.put("until", System.currentTimeMillis() + Math.min(300L, 15L shl Math.min(f - 5, 10)) * 1000)
        }
        writeMeta(ctx, m)
        return ok
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return kg.generateKey()
    }

    fun load(ctx: Context): MutableList<VaultEntry> {
        val f = data(ctx)
        if (!f.exists()) return ArrayList()
        return try {
            val b = f.readBytes()
            val ivLen = b[0].toInt() and 0xFF
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 1, ivLen))
            val arr = JSONArray(String(c.doFinal(b, 1 + ivLen, b.size - 1 - ivLen), Charsets.UTF_8))
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> VaultEntry(o.optString("id", newId()), o.optString("site"), o.optString("user"), o.optString("pass")) } }.toMutableList()
        } catch (e: Exception) { ArrayList() }
    }

    fun save(ctx: Context, list: List<VaultEntry>) {
        val arr = JSONArray(); list.forEach { arr.put(JSONObject().put("id", it.id).put("site", it.site).put("user", it.user).put("pass", it.pass)) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv; val ct = c.doFinal(arr.toString().toByteArray(Charsets.UTF_8))
        val t = File(ctx.filesDir, "vault.bin.tmp"); t.writeBytes(byteArrayOf(iv.size.toByte()) + iv + ct); t.renameTo(data(ctx))
    }
}
