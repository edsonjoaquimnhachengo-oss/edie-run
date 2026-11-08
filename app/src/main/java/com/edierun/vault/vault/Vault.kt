package com.edierun.vault.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.edierun.vault.crypto.Crypto
import com.edierun.vault.crypto.EncryptedMediaDataSource
import com.edierun.vault.crypto.EncryptedReader
import com.edierun.vault.crypto.EncryptedWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

enum class Kind { VIDEO, IMAGE, AUDIO, PAGE, OTHER }

data class VaultItem(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val added: Long,
    val hasThumb: Boolean,
    val fav: Boolean = false,
    /** Origem: "imp" (importado do telemóvel), "dl" (baixado da internet), "web" (página guardada). */
    val src: String = "imp",
    /** Duração em milissegundos (vídeo e áudio). */
    val duration: Long = 0L
) {
    val kind: Kind
        get() = when {
            mime.startsWith("video/") -> Kind.VIDEO
            mime.startsWith("image/") -> Kind.IMAGE
            mime.startsWith("audio/") -> Kind.AUDIO
            mime == "multipart/related" || mime == "message/rfc822" ||
                name.endsWith(".mht", true) || name.endsWith(".mhtml", true) -> Kind.PAGE
            else -> Kind.OTHER
        }
}

/**
 * Um cofre aberto. Os nomes dos ficheiros, as miniaturas, as definições e o conteúdo
 * estão todos cifrados com a chave-mestra deste cofre. No disco só existem ficheiros
 * com nomes aleatórios.
 */
class Vault internal constructor(
    private val appContext: Context,
    val slot: Int,
    private val key: ByteArray
) {
    private val dir = VaultStore.dataDir(appContext, slot)
    private val indexFile = File(dir, "i")
    private val prefsFile = File(dir, "p")
    private val _items = MutableStateFlow<List<VaultItem>>(emptyList())
    val items: StateFlow<List<VaultItem>> = _items.asStateFlow()
    private val thumbCache = LruCache<String, Bitmap>(240)
    private var prefs = JSONObject()

    @Volatile
    var isClosed = false
        private set

    init {
        loadIndex()
        loadPrefs()
    }

    // ---------- índice ----------

    private fun loadIndex() {
        val list = mutableListOf<VaultItem>()
        if (indexFile.exists()) {
            val json = String(Crypto.open(key, indexFile.readBytes(), AAD_INDEX), Charsets.UTF_8)
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += VaultItem(
                    id = o.getString("id"),
                    name = o.getString("n"),
                    mime = o.getString("m"),
                    size = o.getLong("s"),
                    added = o.getLong("a"),
                    hasThumb = o.optBoolean("t", false),
                    fav = o.optBoolean("f", false),
                    src = o.optString("o", "imp"),
                    duration = o.optLong("d", 0L)
                )
            }
        }
        _items.value = list.sortedByDescending { it.added }
    }

    @Synchronized
    private fun saveIndex(list: List<VaultItem>) {
        checkOpen()
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("n", it.name)
                    .put("m", it.mime)
                    .put("s", it.size)
                    .put("a", it.added)
                    .put("t", it.hasThumb)
                    .put("f", it.fav)
                    .put("o", it.src)
                    .put("d", it.duration)
            )
        }
        FileUtil.atomicWrite(indexFile, Crypto.seal(key, arr.toString().toByteArray(Charsets.UTF_8), AAD_INDEX))
        _items.value = list.sortedByDescending { it.added }
    }

    // ---------- definições (também cifradas) ----------

    private fun loadPrefs() {
        prefs = try {
            if (prefsFile.exists()) JSONObject(String(Crypto.open(key, prefsFile.readBytes(), AAD_PREFS), Charsets.UTF_8))
            else JSONObject()
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    @Synchronized
    fun getPref(name: String, default: String): String = prefs.optString(name, default)

    @Synchronized
    fun getBoolPref(name: String, default: Boolean): Boolean = prefs.optBoolean(name, default)

    @Synchronized
    fun setPref(name: String, value: Any) {
        checkOpen()
        prefs.put(name, value)
        FileUtil.atomicWrite(prefsFile, Crypto.seal(key, prefs.toString().toByteArray(Charsets.UTF_8), AAD_PREFS))
    }

    // ---------- ficheiros ----------

    private fun dataFile(id: String) = File(dir, "f_$id")
    private fun thumbFile(id: String) = File(dir, "t_$id")
    private fun newId() = Crypto.hex(Crypto.randomBytes(12))

    private fun checkOpen() {
        if (isClosed) throw IllegalStateException("O cofre está fechado")
    }

    fun openReader(item: VaultItem): EncryptedReader {
        checkOpen()
        return EncryptedReader(dataFile(item.id), key)
    }

    /** Cifra um fluxo directamente para o cofre (nada fica em claro no disco). */
    fun importStream(
        name: String,
        mime: String,
        input: InputStream,
        thumb: Bitmap? = null,
        source: String = "imp",
        onBytes: (Long) -> Unit = {}
    ): VaultItem {
        checkOpen()
        val id = newId()
        val writer = EncryptedWriter(dataFile(id), key)
        var total = 0L
        try {
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                writer.write(buf, 0, n)
                total += n
                onBytes(total)
            }
            writer.close()
        } catch (t: Throwable) {
            writer.abort()
            throw t
        }
        durationTl.set(0L)
        val hasThumb = if (thumb != null) saveThumb(id, thumb) else makeThumb(id, mime)
        val item = VaultItem(
            id = id,
            name = cleanName(name),
            mime = mime,
            size = total,
            added = System.currentTimeMillis(),
            hasThumb = hasThumb,
            src = source,
            duration = durationTl.get() ?: 0L
        )
        synchronized(this) { saveIndex(_items.value + item) }
        return item
    }

    fun importFile(
        file: File,
        name: String,
        mime: String,
        thumb: Bitmap? = null,
        source: String = "imp",
        onBytes: (Long) -> Unit = {}
    ): VaultItem = FileInputStream(file).use { importStream(name, mime, it, thumb, source, onBytes) }

    fun setFavorite(ids: Set<String>, fav: Boolean) {
        checkOpen()
        synchronized(this) {
            saveIndex(_items.value.map { if (it.id in ids) it.copy(fav = fav) else it })
        }
    }

    /** Decifra um ficheiro para a área temporária privada (usado só pelo leitor de páginas offline). */
    fun decryptToTemp(item: VaultItem, dir: File): File {
        dir.mkdirs()
        val ext = item.name.substringAfterLast('.', "bin").take(8)
        val out = File(dir, Crypto.hex(Crypto.randomBytes(8)) + "." + ext)
        openReader(item).use { r -> out.outputStream().use { o -> r.inputStream().copyTo(o, 256 * 1024) } }
        return out
    }

    fun exportTo(item: VaultItem, out: OutputStream) {
        openReader(item).use { r -> r.inputStream().copyTo(out, 256 * 1024) }
    }

    fun delete(ids: Set<String>) {
        checkOpen()
        synchronized(this) {
            saveIndex(_items.value.filterNot { it.id in ids })
        }
        ids.forEach {
            dataFile(it).delete()
            thumbFile(it).delete()
            thumbCache.remove(it)
        }
    }

    fun rename(item: VaultItem, newName: String) {
        val n = cleanName(newName)
        if (n.isBlank()) return
        synchronized(this) {
            saveIndex(_items.value.map { if (it.id == item.id) it.copy(name = n) else it })
        }
    }

    fun wipeAll() {
        checkOpen()
        val ids = _items.value.map { it.id }.toSet()
        synchronized(this) { saveIndex(emptyList()) }
        ids.forEach { dataFile(it).delete(); thumbFile(it).delete() }
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("f_") || f.name.startsWith("t_")) f.delete()
        }
        thumbCache.evictAll()
    }

    // ---------- miniaturas ----------

    fun thumbnail(item: VaultItem): Bitmap? {
        if (isClosed || !item.hasThumb) return null
        thumbCache.get(item.id)?.let { return it }
        return try {
            val bytes = Crypto.open(key, thumbFile(item.id).readBytes(), AAD_THUMB)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { thumbCache.put(item.id, it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun makeThumb(id: String, mime: String): Boolean {
        val bmp: Bitmap? = try {
            when {
                mime.startsWith("image/") -> EncryptedReader(dataFile(id), key).use { r ->
                    if (r.length > 80L * 1024 * 1024) null else decodeSampled(r.readAll(), THUMB_SIZE)
                }
                mime.startsWith("video/") || mime.startsWith("audio/") -> mediaFrame(id, mime.startsWith("audio/"))
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
        if (bmp == null) return false
        return saveThumb(id, bmp)
    }

    private fun saveThumb(id: String, source: Bitmap): Boolean {
        return try {
            val bmp = scaleDown(source, THUMB_SIZE)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            FileUtil.atomicWrite(thumbFile(id), Crypto.seal(key, out.toByteArray(), AAD_THUMB))
            thumbCache.put(id, bmp)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // duração lida ao gerar a miniatura; por linha de execução, porque podem decorrer várias importações ao mesmo tempo
    private val durationTl = ThreadLocal<Long>()

    private fun mediaFrame(id: String, audio: Boolean): Bitmap? {
        val reader = EncryptedReader(dataFile(id), key)
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(EncryptedMediaDataSource(reader))
            durationTl.set(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
            val frame: Bitmap? = if (audio) {
                mmr.embeddedPicture?.let { decodeSampled(it, THUMB_SIZE) }
            } else {
                mmr.getFrameAtTime(2_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: mmr.frameAtTime
            }
            return frame?.let { scaleDown(it, THUMB_SIZE) }
        } finally {
            try { mmr.release() } catch (_: Throwable) { }
            try { reader.close() } catch (_: Throwable) { }
        }
    }

    fun masterKeyCopy(): ByteArray {
        checkOpen()
        return key.copyOf()
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        key.fill(0)
        thumbCache.evictAll()
        _items.value = emptyList()
        prefs = JSONObject()
    }

    companion object {
        private val AAD_INDEX = "edierun-index".toByteArray()
        private val AAD_THUMB = "edierun-thumb".toByteArray()
        private val AAD_PREFS = "edierun-prefs".toByteArray()
        private const val THUMB_SIZE = 360

        fun cleanName(name: String): String =
            name.replace(Regex("[\\r\\n\\t/\\\\]"), " ").trim().take(180).ifBlank { "sem nome" }

        fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { scaleDown(it, maxDim) }
        }

        fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
            val m = maxOf(bmp.width, bmp.height)
            if (m <= maxDim) return bmp
            val f = maxDim.toFloat() / m
            return Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
        }
    }
}
