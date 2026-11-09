package com.edierun.vault.vault

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Devolve um item do cofre à galeria do telemóvel, decifrado, numa pasta "Edie Run":
 * fotos em Imagens, vídeos em Filmes, áudio em Música e páginas em Transferências.
 */
object GalleryExport {
    const val FOLDER = "Edie Run"

    fun needsPermission(): Boolean = Build.VERSION.SDK_INT < 29

    fun restore(ctx: Context, vault: Vault, item: VaultItem): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) restoreScoped(ctx, vault, item) else restoreLegacy(ctx, vault, item)
        } catch (_: Throwable) {
            false
        }
    }

    private fun restoreScoped(ctx: Context, vault: Vault, item: VaultItem): Boolean {
        val resolver = ctx.contentResolver
        val vol = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val (collection, dir) = when (item.kind) {
            Kind.IMAGE -> MediaStore.Images.Media.getContentUri(vol) to Environment.DIRECTORY_PICTURES
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri(vol) to Environment.DIRECTORY_MOVIES
            Kind.AUDIO -> MediaStore.Audio.Media.getContentUri(vol) to Environment.DIRECTORY_MUSIC
            else -> MediaStore.Downloads.getContentUri(vol) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            val out = resolver.openOutputStream(uri) ?: throw java.io.IOException("sem saída")
            out.use { vault.exportTo(item, it) }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            true
        } catch (t: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Throwable) { }
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreLegacy(ctx: Context, vault: Vault, item: VaultItem): Boolean {
        val type = when (item.kind) {
            Kind.IMAGE -> Environment.DIRECTORY_PICTURES
            Kind.VIDEO -> Environment.DIRECTORY_MOVIES
            Kind.AUDIO -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(type), FOLDER).apply { mkdirs() }
        var f = File(dir, item.name)
        var n = 1
        val base = item.name.substringBeforeLast('.')
        val ext = item.name.substringAfterLast('.', "")
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        f.outputStream().use { vault.exportTo(item, it) }
        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(item.mime), null)
        return true
    }
}
