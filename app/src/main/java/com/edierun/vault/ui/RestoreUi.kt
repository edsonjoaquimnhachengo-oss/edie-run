package com.edierun.vault.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.edierun.vault.vault.GalleryExport
import com.edierun.vault.vault.Session
import com.edierun.vault.vault.Vault
import com.edierun.vault.vault.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Devolve itens à galeria do telemóvel. "move = true" retira-os do cofre depois de copiados.
 * Em Android 9 ou anterior pede primeiro autorização de escrita.
 */
@Composable
fun rememberGalleryRestorer(vault: Vault, onFinished: () -> Unit = {}): (List<VaultItem>, Boolean) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<List<VaultItem>, Boolean>?>(null) }

    val doRestore: (List<VaultItem>, Boolean) -> Unit = { list, move ->
        Toast.makeText(ctx, "A devolver ${list.size} ${if (list.size == 1) "item" else "itens"} à galeria…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { list.filter { GalleryExport.restore(ctx, vault, it) } }
            if (move && ok.isNotEmpty()) withContext(Dispatchers.IO) { vault.delete(ok.map { it.id }.toSet()) }
            val failed = list.size - ok.size
            val msg = buildString {
                append(if (move) "${ok.size} movido(s)" else "${ok.size} copiado(s)")
                append(" para a galeria, na pasta \"${GalleryExport.FOLDER}\".")
                if (failed > 0) append(" $failed falharam.")
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            onFinished()
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pending
        pending = null
        if (granted && p != null) doRestore(p.first, p.second)
        else if (!granted) Toast.makeText(ctx, "Sem autorização para escrever na galeria.", Toast.LENGTH_LONG).show()
    }

    return { list, move ->
        if (list.isNotEmpty()) {
            val needs = GalleryExport.needsPermission() &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            if (needs) {
                pending = list to move
                Session.allowLeaving()
                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                doRestore(list, move)
            }
        }
    }
}

/** Pergunta se copia ou move para a galeria. */
@Composable
fun RestoreDialog(count: Int, onCopy: () -> Unit, onMove: () -> Unit, onDismiss: () -> Unit) {
    IosAlert(
        title = "Devolver à galeria",
        message = "${if (count == 1) "O item vai" else "Os $count itens vão"} para a galeria do telemóvel, na pasta " +
            "\"${GalleryExport.FOLDER}\", sem cifra. Qualquer pessoa que abra a galeria os pode ver.",
        onDismiss = onDismiss,
        actions = listOf(
            AlertAction("Copiar (fica também no cofre)", onClick = onCopy),
            AlertAction("Mover (sai do cofre)", onClick = onMove),
            AlertAction("Cancelar", bold = true, onClick = onDismiss)
        )
    )
}
