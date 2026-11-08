package com.edierun.vault.crypto

import android.media.MediaDataSource
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Formato dos ficheiros do cofre (permite saltar para qualquer ponto de um vídeo sem decifrar tudo):
 *
 *   "EDV1" | tamanho do bloco (int) | tamanho original (long) | chave do ficheiro cifrada (60 bytes)
 *   bloco 0 cifrado + tag | bloco 1 cifrado + tag | ...
 *
 * Cada ficheiro tem a sua chave aleatória, protegida pela chave-mestra do cofre.
 * Cada bloco é AES-GCM com nonce = número do bloco.
 */
object EncFormat {
    val MAGIC = byteArrayOf(0x45, 0x44, 0x56, 0x31) // "EDV1"
    const val CHUNK = 128 * 1024
    const val WRAPPED_LEN = Crypto.NONCE_LEN + Crypto.KEY_LEN + Crypto.TAG_LEN
    const val HEADER_LEN = 4 + 4 + 8 + WRAPPED_LEN
    private val FILE_KEY_AAD = "edierun-filekey".toByteArray()

    fun nonce(index: Long): ByteArray =
        ByteBuffer.allocate(Crypto.NONCE_LEN).putInt(0).putLong(index).array()

    fun wrapKey(master: ByteArray, fileKey: ByteArray): ByteArray = Crypto.seal(master, fileKey, FILE_KEY_AAD)

    fun unwrapKey(master: ByteArray, wrapped: ByteArray): ByteArray = Crypto.open(master, wrapped, FILE_KEY_AAD)
}

class EncryptedWriter(private val file: File, masterKey: ByteArray) : OutputStream() {
    private val raf = RandomAccessFile(file, "rw")
    private val chunkSize = EncFormat.CHUNK
    private val fileKey = Crypto.randomBytes(Crypto.KEY_LEN)
    private val keySpec = SecretKeySpec(fileKey, "AES")
    private val buf = ByteArray(chunkSize)
    private var bufLen = 0
    private var chunkIndex = 0L
    private var total = 0L
    private var closed = false

    init {
        raf.setLength(0)
        val header = ByteBuffer.allocate(EncFormat.HEADER_LEN)
        header.put(EncFormat.MAGIC)
        header.putInt(chunkSize)
        header.putLong(0L)
        header.put(EncFormat.wrapKey(masterKey, fileKey))
        raf.write(header.array())
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("ficheiro fechado")
        var o = off
        var remaining = len
        while (remaining > 0) {
            val n = minOf(remaining, chunkSize - bufLen)
            System.arraycopy(b, o, buf, bufLen, n)
            bufLen += n
            o += n
            remaining -= n
            total += n
            if (bufLen == chunkSize) flushChunk()
        }
    }

    private fun flushChunk() {
        if (bufLen == 0) return
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(Crypto.TAG_LEN * 8, EncFormat.nonce(chunkIndex)))
        raf.write(c.doFinal(buf, 0, bufLen))
        chunkIndex++
        bufLen = 0
    }

    val bytesWritten: Long get() = total

    override fun close() {
        if (closed) return
        closed = true
        try {
            flushChunk()
            raf.seek(8)
            raf.writeLong(total)
            raf.fd.sync()
        } finally {
            raf.close()
            fileKey.fill(0)
            buf.fill(0)
        }
    }

    /** Desiste da escrita e apaga o que já foi escrito. */
    fun abort() {
        if (!closed) {
            closed = true
            try { raf.close() } catch (_: Throwable) { }
            fileKey.fill(0)
            buf.fill(0)
        }
        file.delete()
    }
}

class EncryptedReader(file: File, masterKey: ByteArray) : Closeable {
    private class Header(val chunkSize: Int, val length: Long, val keySpec: SecretKeySpec)

    private val raf = RandomAccessFile(file, "r")
    private val header: Header = try {
        readHeader(raf, masterKey)
    } catch (t: Throwable) {
        raf.close()
        throw t
    }
    val length: Long = header.length
    private val chunkSize: Int = header.chunkSize
    private val keySpec: SecretKeySpec = header.keySpec
    private var cachedIndex = -1L
    private var cached = ByteArray(0)

    private fun readHeader(raf: RandomAccessFile, masterKey: ByteArray): Header {
        val bytes = ByteArray(EncFormat.HEADER_LEN)
        raf.readFully(bytes)
        val bb = ByteBuffer.wrap(bytes)
        val magic = ByteArray(4)
        bb.get(magic)
        if (!magic.contentEquals(EncFormat.MAGIC)) throw IOException("formato desconhecido")
        val size = bb.int
        val len = bb.long
        if (size <= 0 || len < 0) throw IOException("cabeçalho inválido")
        val wrapped = ByteArray(EncFormat.WRAPPED_LEN)
        bb.get(wrapped)
        val fk = EncFormat.unwrapKey(masterKey, wrapped)
        val spec = SecretKeySpec(fk, "AES")
        fk.fill(0)
        return Header(size, len, spec)
    }

    /** Lê a partir de [position]. Devolve -1 no fim do ficheiro. */
    @Synchronized
    fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (position >= length) return -1
        if (len <= 0) return 0
        var pos = position
        var o = off
        var remaining = minOf(len.toLong(), length - position).toInt()
        var read = 0
        while (remaining > 0) {
            val idx = pos / chunkSize
            val chunk = chunk(idx)
            val inChunk = (pos - idx * chunkSize).toInt()
            val n = minOf(remaining, chunk.size - inChunk)
            if (n <= 0) break
            System.arraycopy(chunk, inChunk, dst, o, n)
            pos += n
            o += n
            remaining -= n
            read += n
        }
        return read
    }

    private fun chunk(idx: Long): ByteArray {
        if (idx == cachedIndex) return cached
        val plainLen = minOf(chunkSize.toLong(), length - idx * chunkSize).toInt()
        val enc = ByteArray(plainLen + Crypto.TAG_LEN)
        raf.seek(EncFormat.HEADER_LEN + idx * (chunkSize.toLong() + Crypto.TAG_LEN))
        raf.readFully(enc)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(Crypto.TAG_LEN * 8, EncFormat.nonce(idx)))
        cached = c.doFinal(enc)
        cachedIndex = idx
        return cached
    }

    fun readAll(maxBytes: Long = 300L * 1024 * 1024): ByteArray {
        if (length > maxBytes) throw IOException("ficheiro grande demais para abrir em memória")
        val out = ByteArray(length.toInt())
        var pos = 0
        while (pos < out.size) {
            val n = read(pos.toLong(), out, pos, out.size - pos)
            if (n <= 0) break
            pos += n
        }
        return out
    }

    fun inputStream(start: Long = 0L): InputStream = object : InputStream() {
        var pos = start
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = this@EncryptedReader.read(pos, b, off, len)
            if (n > 0) pos += n
            return n
        }

        override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), maxOf(0L, length - pos)).toInt()
    }

    override fun close() {
        cached.fill(0)
        raf.close()
    }
}

/** Permite ao Android ler um vídeo cifrado (para gerar miniaturas) sem o decifrar para o disco. */
class EncryptedMediaDataSource(private val reader: EncryptedReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        if (size == 0) 0 else reader.read(position, buffer, offset, size)

    override fun getSize(): Long = reader.length

    override fun close() = reader.close()
}
