package com.edierun.vault.vault

import android.content.Context
import android.os.SystemClock
import com.edierun.vault.download.DownloadManager
import com.edierun.vault.net.TorManager
import com.edierun.vault.player.StreamServer

/**
 * Estado da sessão aberta. A chave só existe em memória enquanto o cofre está aberto.
 */
object Session {
    @Volatile
    var vault: Vault? = null

    /** Link recebido por partilha antes de o cofre estar aberto. */
    @Volatile
    var pendingUrl: String? = null

    @Volatile
    private var leavingAllowedUntil = 0L

    /** Chamado antes de abrir o selector de ficheiros do sistema, para o cofre não fechar. */
    fun allowLeaving() {
        leavingAllowedUntil = SystemClock.elapsedRealtime() + 5 * 60_000L
    }

    fun clearAllowLeaving() {
        leavingAllowedUntil = 0L
    }

    val leavingAllowed: Boolean
        get() = SystemClock.elapsedRealtime() < leavingAllowedUntil

    fun lock(ctx: Context) {
        val v = vault ?: return
        vault = null
        StreamServer.stop()
        // páginas offline decifradas temporariamente para leitura
        FileUtil.wipeDir(java.io.File(ctx.noBackupFilesDir, "tmp/view"))
        if (!DownloadManager.isUsing(v)) v.close()
        if (!DownloadManager.hasActive()) TorManager.stop(ctx)
    }
}
