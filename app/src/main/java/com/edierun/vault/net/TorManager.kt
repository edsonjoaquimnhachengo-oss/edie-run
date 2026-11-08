package com.edierun.vault.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.torproject.jni.TorService
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Liga e desliga o Tor integrado (biblioteca tor-android do Guardian Project).
 * O navegador e os downloads usam a porta "HTTP tunnel" do Tor, que resolve os nomes
 * dos sites dentro da rede Tor (sem fugas de DNS).
 */
object TorManager {
    enum class State { OFF, STARTING, READY, ERROR }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    @Volatile
    var httpPort = 8118
        private set

    private const val WANTED_HTTP_PORT = 8118

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: ServiceConnection? = null
    private var service: Any? = null
    private var waitJob: Job? = null

    val isReady: Boolean get() = _state.value == State.READY
    val isWanted: Boolean get() = _state.value == State.STARTING || _state.value == State.READY

    @Synchronized
    fun start(ctx: Context) {
        if (_state.value == State.STARTING || _state.value == State.READY) return
        val app = ctx.applicationContext
        _state.value = State.STARTING
        _message.value = "A ligar à rede Tor…"
        writeTorrc(app)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = try {
                    binder?.javaClass?.getMethod("getService")?.invoke(binder)
                } catch (_: Throwable) {
                    null
                }
                waitJob?.cancel()
                waitJob = scope.launch { waitUntilReady() }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                if (_state.value != State.OFF) {
                    _state.value = State.ERROR
                    _message.value = "O Tor parou."
                }
            }
        }
        connection = conn
        val ok = try {
            app.bindService(Intent(app, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (_: Throwable) {
            false
        }
        if (!ok) {
            connection = null
            _state.value = State.ERROR
            _message.value = "Não foi possível iniciar o Tor neste telemóvel."
        }
    }

    @Synchronized
    fun stop(ctx: Context) {
        waitJob?.cancel()
        waitJob = null
        connection?.let { c ->
            try { ctx.applicationContext.unbindService(c) } catch (_: Throwable) { }
        }
        connection = null
        service = null
        _state.value = State.OFF
        _message.value = ""
    }

    /** Escreve a configuração do Tor. Usa reflexão para funcionar com várias versões da biblioteca. */
    private fun writeTorrc(ctx: Context) {
        try {
            val m = TorService::class.java.getMethod("getTorrc", Context::class.java)
            val f = m.invoke(null, ctx) as File
            f.parentFile?.mkdirs()
            f.writeText(
                """
                HTTPTunnelPort $WANTED_HTTP_PORT
                ClientOnly 1
                AvoidDiskWrites 1
                """.trimIndent() + "\n"
            )
        } catch (_: Throwable) {
            // sem acesso ao torrc: fica a configuração por defeito da biblioteca
        }
    }

    private fun portFromService(): Int? {
        val s = service ?: return null
        return try {
            val p = s.javaClass.getMethod("getHttpTunnelPort").invoke(s) as? Int
            if (p != null && p > 0) p else null
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun waitUntilReady() {
        val started = System.currentTimeMillis()
        var attempt = 0
        while (scope.isActive && _state.value == State.STARTING) {
            attempt++
            portFromService()?.let { httpPort = it }
            if (checkTor(httpPort)) {
                _state.value = State.READY
                _message.value = "Ligado através do Tor"
                return
            }
            val secs = (System.currentTimeMillis() - started) / 1000
            if (secs > 180) {
                _state.value = State.ERROR
                _message.value = "O Tor não conseguiu ligar em 3 minutos. Verifique a internet e tente outra vez."
                return
            }
            _message.value = "A ligar à rede Tor… (${secs}s)"
            delay(if (attempt < 5) 2000 else 4000)
        }
    }

    /** Confirma, através do próprio Tor, que o tráfego está mesmo a sair pela rede Tor. */
    private fun checkTor(port: Int): Boolean {
        return try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
            val c = URL("https://check.torproject.org/api/ip").openConnection(proxy) as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.useCaches = false
            try {
                if (c.responseCode != 200) return false
                val body = c.inputStream.bufferedReader().use { it.readText() }
                body.replace(" ", "").contains("\"IsTor\":true")
            } finally {
                c.disconnect()
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun httpProxy(): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
}
