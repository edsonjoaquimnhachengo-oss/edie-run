package com.edierun.vault.vault

import android.content.Context
import com.edierun.vault.crypto.Crypto
import com.edierun.vault.download.DownloadManager
import java.io.File
import java.security.GeneralSecurityException

/**
 * Dois cofres independentes, guardados em "s0/d0" e "s1/d1".
 * Na configuração a ordem é sorteada, por isso nada no disco indica qual é o cofre
 * principal e qual é o de emergência. Cada um abre com o seu PIN.
 */
object VaultStore {
    private const val SLOTS = 2
    private val SLOT_AAD = "edierun-slot".toByteArray()

    private fun root(ctx: Context): File = File(ctx.noBackupFilesDir, "ev").apply { mkdirs() }
    private fun slotFile(ctx: Context, i: Int) = File(root(ctx), "s$i")
    fun dataDir(ctx: Context, i: Int): File = File(root(ctx), "d$i").apply { mkdirs() }

    fun isSetUp(ctx: Context): Boolean = (0 until SLOTS).all { slotFile(ctx, it).exists() }

    fun isValidPin(pin: String) = pin.length in 4..12 && pin.all { it in '0'..'9' }

    fun setup(ctx: Context, mainPin: String, emergencyPin: String) {
        require(isValidPin(mainPin) && isValidPin(emergencyPin) && mainPin != emergencyPin)
        val pins = listOf(mainPin, emergencyPin).shuffled(Crypto.random)
        pins.forEachIndexed { i, pin ->
            val master = Crypto.randomBytes(Crypto.KEY_LEN)
            writeSlot(ctx, i, pin, master)
            master.fill(0)
        }
    }

    private fun writeSlot(ctx: Context, i: Int, pin: String, master: ByteArray) {
        val salt = Crypto.randomBytes(Crypto.SALT_LEN)
        val kek = Crypto.deriveKey(pin, salt)
        val sealed = Crypto.seal(kek, master, SLOT_AAD)
        kek.fill(0)
        FileUtil.atomicWrite(slotFile(ctx, i), salt + sealed)
    }

    private fun openSlot(ctx: Context, i: Int, pin: String): ByteArray? {
        val f = slotFile(ctx, i)
        if (!f.exists()) return null
        val bytes = f.readBytes()
        if (bytes.size <= Crypto.SALT_LEN) return null
        val salt = bytes.copyOfRange(0, Crypto.SALT_LEN)
        val sealed = bytes.copyOfRange(Crypto.SALT_LEN, bytes.size)
        val kek = Crypto.deriveKey(pin, salt)
        return try {
            Crypto.open(kek, sealed, SLOT_AAD)
        } catch (e: GeneralSecurityException) {
            null
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Tenta o PIN nos dois cofres. Testa sempre os dois, para que o tempo de resposta
     * não revele qual dos cofres foi aberto.
     */
    fun tryUnlock(ctx: Context, pin: String): Vault? {
        if (!isValidPin(pin)) return null
        var found: Vault? = null
        for (i in 0 until SLOTS) {
            val master = openSlot(ctx, i, pin)
            if (master != null && found == null) {
                val running = DownloadManager.openVaultForSlot(i)
                if (running != null) {
                    master.fill(0)
                    found = running
                } else {
                    found = Vault(ctx.applicationContext, i, master)
                }
            } else {
                master?.fill(0)
            }
        }
        return found
    }

    /** Muda o PIN do cofre aberto. Recusa um PIN que já abra o outro cofre. */
    fun changePin(ctx: Context, vault: Vault, newPin: String): Boolean {
        if (!isValidPin(newPin)) return false
        for (i in 0 until SLOTS) {
            if (i != vault.slot) {
                val other = openSlot(ctx, i, newPin)
                if (other != null) {
                    other.fill(0)
                    return false
                }
            }
        }
        val master = vault.masterKeyCopy()
        writeSlot(ctx, vault.slot, newPin, master)
        master.fill(0)
        return true
    }
}
