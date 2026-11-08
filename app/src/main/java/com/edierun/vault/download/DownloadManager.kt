package com.edierun.vault.download

import android.content.Context
import android.content.Intent
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import com.edierun.vault.crypto.Crypto
import com.edierun.vault.net.TorManager
import com.edierun.vault.vault.FileUtil
import com.edierun.vault.vault.Session
import com.edierun.vault.vault.Vault
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

enum class Quality(val label: String) {
    HIGH("Alta (até 1080p)"),
    MEDIUM("Média (720p)"),
    LOW("Leve (480p)"),
    AUDIO("Só áudio (MP3)");

    companion object {
        fun from(key: String): Quality = entries.firstOrNull { it.name == key } ?: HIGH
    }
}

enum class JobStatus { QUEUED, RUNNING, SAVING, DONE, FAILED, CANCELLED }

data class DownloadJob(
    val id: String,
    val slot: Int,
    val url: String,
    val title: String,
    val status: JobStatus,
    val progress: Float = -1f,
    val detail: String = ""
) {
    val isActive: Boolean
        get() = status == JobStatus.QUEUED || status == JobStatus.RUNNING || status == JobStatus.SAVING
}

enum class EngineState { PREPARING, READY, FAILED }

/**
 * Downloads de vídeos (yt-dlp: YouTube, Facebook, Instagram, TikTok, X, Vimeo e centenas
 * de outros sites) e de ficheiros directos. O resultado é cifrado para o cofre e o
 * ficheiro temporário é apagado.
 */
object DownloadManager {
    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val _engine = MutableStateFlow(EngineState.PREPARING)
    val engine: StateFlow<EngineState> = _engine.asStateFlow()
    var engineError: String = ""
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)
    private val owners = ConcurrentHashMap<String, Vault>()
    private val tasks = ConcurrentHashMap<String, Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private lateinit var app: Context

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    fun init(context: Context) {
        app = context.applicationContext
        // Restos de uma sessão anterior interrompida
        FileUtil.wipeDir(tmpRoot())
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                _engine.value = EngineState.READY
            } catch (t: Throwable) {
                engineError = t.message ?: t.toString()
                _engine.value = EngineState.FAILED
            }
        }
    }

    private fun tmpRoot(): File = File(app.noBackupFilesDir, "tmp")

    fun isUsing(v: Vault): Boolean = owners.values.any { it === v }

    /** Cofre ainda aberto por downloads em curso (para ser reutilizado ao voltar a entrar). */
    fun openVaultForSlot(slot: Int): Vault? = owners.values.firstOrNull { it.slot == slot && !it.isClosed }
    fun hasActive(): Boolean = _jobs.value.any { it.isActive }

    private fun update(id: String, f: (DownloadJob) -> DownloadJob) {
        _jobs.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    fun enqueueMedia(vault: Vault, url: String, quality: Quality) {
        enqueue(vault, url, url) { id -> runYtDlp(vault, id, url, quality) }
    }

    fun enqueueDirect(vault: Vault, url: String, name: String?, mime: String?) {
        enqueue(vault, url, name ?: url) { id -> runDirect(vault, id, url, name, mime) }
    }

    private fun enqueue(vault: Vault, url: String, title: String, block: suspend (String) -> Unit) {
        val id = Crypto.hex(Crypto.randomBytes(8))
        owners[id] = vault
        _jobs.update { it + DownloadJob(id, vault.slot, url, title, JobStatus.QUEUED, detail = "Em espera") }
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (_: Throwable) {
            // sem serviço em primeiro plano o download continua, mas pára se o cofre fechar
        }
        tasks[id] = scope.launch {
            try {
                slots.withPermit {
                    if (id in cancelled) throw CancellationException()
                    update(id) { it.copy(status = JobStatus.RUNNING, detail = "A iniciar…") }
                    block(id)
                }
                update(id) { it.copy(status = JobStatus.DONE, progress = 1f, detail = "Guardado no cofre") }
            } catch (c: CancellationException) {
                update(id) { it.copy(status = JobStatus.CANCELLED, detail = "Cancelado") }
            } catch (t: Throwable) {
                if (id in cancelled) update(id) { it.copy(status = JobStatus.CANCELLED, detail = "Cancelado") }
                else update(id) { it.copy(status = JobStatus.FAILED, detail = friendly(t)) }
            } finally {
                tasks.remove(id)
                owners.remove(id)
                cancelled.remove(id)
                FileUtil.wipeDir(File(tmpRoot(), id))
                if (!isUsing(vault) && Session.vault !== vault) vault.close()
                if (!hasActive() && Session.vault == null) TorManager.stop(app)
            }
        }
    }

    fun cancel(id: String) {
        cancelled.add(id)
        try { YoutubeDL.getInstance().destroyProcessById(id) } catch (_: Throwable) { }
        tasks[id]?.cancel()
    }

    fun clearFinished(slot: Int) {
        _jobs.update { list -> list.filter { it.isActive || it.slot != slot } }
    }

    /** Se o Tor estiver ligado (ou a ligar), os downloads esperam por ele e passam por ele. */
    private suspend fun torProxyUrl(): String? {
        if (!TorManager.isWanted) return null
        var waited = 0
        while (TorManager.state.value == TorManager.State.STARTING && waited < 180) {
            delay(1000)
            waited++
        }
        if (!TorManager.isReady) throw IOException("O Tor está activo mas não ligou; o download foi travado para não expor o seu IP.")
        return "http://127.0.0.1:${TorManager.httpPort}"
    }

    private suspend fun waitEngine() {
        while (_engine.value == EngineState.PREPARING) delay(300)
        if (_engine.value == EngineState.FAILED) throw IOException("Motor de downloads indisponível: $engineError")
    }

    private suspend fun runYtDlp(vault: Vault, id: String, url: String, quality: Quality) {
        waitEngine()
        val proxy = torProxyUrl()
        val tmp = File(tmpRoot(), id).apply { mkdirs() }
        val req = YoutubeDLRequest(url)
        req.addOption("-o", File(tmp, "%(title).80B.%(ext)s").absolutePath)
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--no-cache-dir")
        req.addOption("--user-agent", USER_AGENT)
        when (quality) {
            Quality.HIGH -> fmt(req, 1080)
            Quality.MEDIUM -> fmt(req, 720)
            Quality.LOW -> fmt(req, 480)
            Quality.AUDIO -> {
                req.addOption("-f", "ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
            }
        }
        if (proxy != null) req.addOption("--proxy", proxy)

        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().execute(req, id) { progress, _, line ->
                update(id) {
                    it.copy(
                        progress = if (progress >= 0f) progress / 100f else -1f,
                        detail = line.trim().take(140).ifBlank { it.detail }
                    )
                }
            }
        }
        if (id in cancelled) throw CancellationException()

        val files = tmp.listFiles()?.filter {
            it.isFile && it.length() > 0 && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp")
        }.orEmpty()
        if (files.isEmpty()) throw IOException("O site não devolveu nenhum ficheiro.")

        files.forEach { f ->
            val total = f.length()
            update(id) { it.copy(status = JobStatus.SAVING, title = f.name, progress = 0f, detail = "A cifrar para o cofre…") }
            vault.importFile(f, f.name, FileUtil.guessMime(f.name)) { done ->
                if (id in cancelled) throw CancellationException()
                update(id) { it.copy(progress = done.toFloat() / total) }
            }
            FileUtil.wipe(f)
        }
    }

    private fun fmt(req: YoutubeDLRequest, h: Int) {
        req.addOption(
            "-f",
            "bv*[height<=$h][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/b"
        )
        req.addOption("--merge-output-format", "mp4/mkv")
    }

    private suspend fun runDirect(vault: Vault, id: String, rawUrl: String, name: String?, mime: String?) {
        val proxyUrl = torProxyUrl()
        var target = rawUrl
        if (proxyUrl != null && target.startsWith("http://")) target = "https://" + target.removePrefix("http://")
        withContext(Dispatchers.IO) {
            val u = URL(target)
            val conn = (if (proxyUrl != null) u.openConnection(TorManager.httpProxy()) else u.openConnection()) as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 40_000
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("O servidor respondeu $code")
                val total = conn.contentLengthLong
                val headerMime = conn.contentType?.substringBefore(';')?.trim()
                val finalMime = mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                    ?: headerMime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                    ?: FileUtil.guessMime(name ?: target)
                val finalName = name ?: URLUtil.guessFileName(target, conn.getHeaderField("Content-Disposition"), finalMime)
                update(id) { it.copy(title = finalName, detail = "A receber e a cifrar…") }
                conn.inputStream.use { input ->
                    vault.importStream(finalName, finalMime, input) { done ->
                        if (id in cancelled) throw CancellationException()
                        update(id) {
                            it.copy(
                                progress = if (total > 0) done.toFloat() / total else -1f,
                                detail = FileUtil.humanSize(done) + if (total > 0) " de " + FileUtil.humanSize(total) else ""
                            )
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun updateEngine(): String = withContext(Dispatchers.IO) {
        try {
            waitEngine()
            val status = YoutubeDL.getInstance().updateYoutubeDL(app)
            if (status.toString().contains("UP_TO_DATE", ignoreCase = true)) "O motor já está na versão mais recente."
            else "Motor de downloads actualizado."
        } catch (t: Throwable) {
            "Não foi possível actualizar: " + (t.message ?: t.toString()).take(160)
        }
    }

    private fun friendly(t: Throwable): String {
        val msg = t.message ?: t.javaClass.simpleName
        val errLine = msg.lines().lastOrNull { it.contains("ERROR:") }
        val text = errLine?.substringAfter("ERROR:")?.trim() ?: msg.lines().lastOrNull { it.isNotBlank() } ?: msg
        return when {
            text.contains("Unsupported URL", true) -> "Este site ou link não é suportado."
            text.contains("Sign in to confirm", true) -> "O YouTube pediu verificação (acontece muito com Tor). Desligue o Tor ou tente mais tarde."
            text.contains("Private video", true) -> "O vídeo é privado."
            text.contains("Unable to resolve host", true) || text.contains("Failed to resolve", true) -> "Sem ligação à internet."
            else -> text.take(220)
        }
    }
}
