package com.edierun.vault.player

import com.edierun.vault.crypto.Crypto
import com.edierun.vault.crypto.EncryptedReader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Pequeno servidor HTTP só em 127.0.0.1 que entrega ao leitor VLC o vídeo decifrado
 * em memória, bloco a bloco, com suporte para saltar (Range). Nada é escrito em claro
 * no disco. Cada vídeo tem um endereço com um código aleatório de 192 bits, por isso
 * outras apps do telemóvel não conseguem adivinhar o endereço.
 */
object StreamServer {
    private class Entry(val open: () -> EncryptedReader, val mime: String)

    private var server: ServerSocket? = null
    private val entries = ConcurrentHashMap<String, Entry>()

    @Synchronized
    fun register(open: () -> EncryptedReader, mime: String): String {
        val s = ensureStarted()
        val token = Crypto.hex(Crypto.randomBytes(24))
        entries[token] = Entry(open, mime)
        return "http://127.0.0.1:${s.localPort}/$token"
    }

    fun unregister(url: String) {
        entries.remove(url.substringAfterLast('/'))
    }

    @Synchronized
    fun stop() {
        entries.clear()
        try { server?.close() } catch (_: Throwable) { }
        server = null
    }

    private fun ensureStarted(): ServerSocket {
        server?.let { if (!it.isClosed) return it }
        val s = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(isDaemon = true, name = "edie-stream") {
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (_: Throwable) { break }
                thread(isDaemon = true, name = "edie-stream-conn") { handle(sock) }
            }
        }
        return s
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun simple(out: OutputStream, status: String) {
        out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun handle(sock: Socket) {
        try {
            sock.use {
                sock.soTimeout = 60_000
                val input = BufferedInputStream(sock.getInputStream())
                val out = BufferedOutputStream(sock.getOutputStream(), 128 * 1024)
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val token = parts[1].trimStart('/').substringBefore('?')
                val entry = entries[token]
                if (entry == null || (method != "GET" && method != "HEAD")) {
                    simple(out, "404 Not Found")
                    return
                }
                val reader = try { entry.open() } catch (_: Throwable) {
                    simple(out, "500 Internal Server Error")
                    return
                }
                reader.use { r ->
                    val total = r.length
                    var start = 0L
                    var end = total - 1
                    var partial = false
                    val range = headers["range"]
                    if (range != null && range.startsWith("bytes=")) {
                        val spec = range.removePrefix("bytes=").substringBefore(',').trim()
                        val a = spec.substringBefore('-').trim()
                        val b = spec.substringAfter('-', "").trim()
                        if (a.isEmpty() && b.isNotEmpty()) {
                            val suffix = b.toLongOrNull() ?: 0L
                            start = maxOf(0L, total - suffix)
                        } else {
                            start = a.toLongOrNull() ?: 0L
                            if (b.isNotEmpty()) end = minOf(total - 1, b.toLongOrNull() ?: (total - 1))
                        }
                        partial = true
                    }
                    if (total == 0L || start >= total || start > end) {
                        out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        return
                    }
                    val len = end - start + 1
                    val sb = StringBuilder()
                    sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    sb.append("Content-Type: ").append(entry.mime).append("\r\n")
                    sb.append("Accept-Ranges: bytes\r\n")
                    sb.append("Content-Length: ").append(len).append("\r\n")
                    if (partial) sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n")
                    sb.append("Connection: close\r\n\r\n")
                    out.write(sb.toString().toByteArray())
                    if (method == "GET") {
                        val buf = ByteArray(128 * 1024)
                        var pos = start
                        while (pos <= end) {
                            val want = minOf(buf.size.toLong(), end - pos + 1).toInt()
                            val n = r.read(pos, buf, 0, want)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            pos += n
                        }
                    }
                    out.flush()
                }
            }
        } catch (_: Throwable) {
            // o leitor fechou a ligação (por exemplo, ao saltar para outro ponto do vídeo)
        }
    }
}
