package com.edierun.vault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.edierun.vault.vault.Vault
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Atalho da página inicial do navegador. O ícone fica guardado (cifrado) nas definições do cofre. */
data class Shortcut(val title: String, val url: String, val icon: Bitmap? = null) {
    val host: String get() = hostOf(url)
}

fun hostOf(url: String): String =
    (Uri.parse(url).host ?: "").lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.")

object ShortcutStore {
    private const val KEY = "shortcuts"

    private val defaults = listOf(
        Shortcut("YouTube", "https://m.youtube.com"),
        Shortcut("Facebook", "https://m.facebook.com"),
        Shortcut("Instagram", "https://www.instagram.com"),
        Shortcut("TikTok", "https://www.tiktok.com"),
        Shortcut("X", "https://x.com"),
        Shortcut("Vimeo", "https://vimeo.com"),
        Shortcut("Dailymotion", "https://www.dailymotion.com"),
        Shortcut("DuckDuckGo", "https://duckduckgo.com")
    )

    fun load(vault: Vault): List<Shortcut> {
        val raw = vault.getPref(KEY, "")
        if (raw.isBlank()) return defaults
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val icon = o.optString("i", "").takeIf { it.isNotEmpty() }?.let { b64 ->
                    try {
                        val bytes = Base64.decode(b64, Base64.NO_WRAP)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (_: Throwable) {
                        null
                    }
                }
                Shortcut(o.getString("t"), o.getString("u"), icon)
            }
        } catch (_: Throwable) {
            defaults
        }
    }

    fun save(vault: Vault, list: List<Shortcut>) {
        val arr = JSONArray()
        list.forEach { s ->
            val o = JSONObject().put("t", s.title).put("u", s.url)
            s.icon?.let { bmp ->
                val out = ByteArrayOutputStream()
                scaleIcon(bmp).compress(Bitmap.CompressFormat.PNG, 100, out)
                o.put("i", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            }
            arr.put(o)
        }
        vault.setPref(KEY, arr.toString())
    }

    fun scaleIcon(bmp: Bitmap): Bitmap {
        val max = 64
        if (bmp.width <= max && bmp.height <= max) return bmp
        return Bitmap.createScaledBitmap(bmp, max, max, true)
    }
}
