package com.edierun.vault.vault

import android.webkit.MimeTypeMap
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

object FileUtil {

    fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("não foi possível gravar ${target.name}")
        }
    }

    /**
     * Apagamento "possível": escreve zeros por cima e depois apaga.
     * Em memória flash isto não garante destruição física, mas evita que o conteúdo
     * fique legível no mesmo sítio. Os ficheiros do cofre já estão cifrados.
     */
    fun wipe(file: File) {
        try {
            if (file.isFile) {
                RandomAccessFile(file, "rw").use { raf ->
                    val zeros = ByteArray(1024 * 1024)
                    var left = raf.length()
                    raf.seek(0)
                    while (left > 0) {
                        val n = minOf(left, zeros.size.toLong()).toInt()
                        raf.write(zeros, 0, n)
                        left -= n
                    }
                    raf.fd.sync()
                }
            }
        } catch (_: Throwable) {
        }
        file.delete()
    }

    fun wipeDir(dir: File) {
        if (!dir.exists()) return
        dir.walkBottomUp().forEach { f -> if (f.isFile) wipe(f) else f.delete() }
    }

    private val extra = mapOf(
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "wmv" to "video/x-ms-wmv",
        "flv" to "video/x-flv",
        "3gp" to "video/3gpp",
        "ts" to "video/mp2t",
        "mts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "ogv" to "video/ogg",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "opus" to "audio/ogg",
        "ogg" to "audio/ogg",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "amr" to "audio/amr",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "bmp" to "image/bmp",
        "avif" to "image/avif"
    )

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isEmpty()) return "application/octet-stream"
        return extra[ext]
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
    }
}
