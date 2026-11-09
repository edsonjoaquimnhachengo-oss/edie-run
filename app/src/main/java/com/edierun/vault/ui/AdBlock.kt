package com.edierun.vault.ui

import android.net.Uri
import java.util.Locale

/**
 * Bloqueador de anúncios e rastreadores, feito no próprio telemóvel (nada passa por servidores
 * de terceiros). Menos pedidos significa páginas mais rápidas e menos dados gastos.
 */
object AdBlock {
    private val hosts: Set<String> = """
        doubleclick.net googlesyndication.com googleadservices.com google-analytics.com googletagmanager.com
        googletagservices.com adservice.google.com imasdk.googleapis.com googleads.g.doubleclick.net
        adnxs.com adsrvr.org amazon-adsystem.com criteo.com criteo.net taboola.com outbrain.com
        scorecardresearch.com quantserve.com quantcount.com moatads.com rubiconproject.com pubmatic.com
        openx.net casalemedia.com advertising.com yieldmo.com smartadserver.com adform.net teads.tv
        sharethrough.com bidswitch.net 3lift.com triplelift.com indexww.com media.net zedo.com
        popads.net popcash.net propellerads.com adsterra.com exoclick.com juicyads.com trafficjunky.net
        mgid.com revcontent.com hotjar.com mixpanel.com segment.io segment.com branch.io appsflyer.com
        adjust.com connect.facebook.net ads.yahoo.com analytics.yahoo.com bat.bing.com clarity.ms
        mc.yandex.ru an.yandex.ru ads-twitter.com static.ads-twitter.com analytics.twitter.com
        analytics.tiktok.com ads.tiktok.com business-api.tiktok.com adcolony.com unityads.unity3d.com
        applovin.com applvn.com vungle.com chartboost.com inmobi.com startappservice.com
        adsafeprotected.com doubleverify.com serving-sys.com spotxchange.com spotx.tv springserve.com
        lijit.com sovrn.com gumgum.com undertone.com contextweb.com districtm.io emxdgt.com
        33across.com rhythmone.com yieldlab.net adition.com adrecover.com adskeeper.com
        hilltopads.net clickadu.com onclickads.net onclickperformance.com adcash.com admaven.com
        a-ads.com coinzilla.com bitmedia.io richpush.co pushnami.com onesignal.com
        newrelic.com nr-data.net bugsnag.com sentry-cdn.com fullstory.com mouseflow.com crazyegg.com
        luckyorange.com inspectlet.com chartbeat.com chartbeat.net parsely.com kissmetrics.com
        omtrdc.net demdex.net everesttech.net 2o7.net krxd.net bluekai.com exelator.com
        rlcdn.com agkn.com tapad.com adsymptotic.com crwdcntrl.net liadm.com id5-sync.com
        pippio.com bidr.io dotomi.com mathtag.com turn.com mookie1.com adroll.com perfectaudience.com
        zemanta.com nativo.com plista.com ligatus.com yieldlove.com adyoulike.com
    """.trim().split(Regex("\\s+")).toSet()

    private val pathTokens = listOf(
        "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_", "/adserver", "/ad_status",
        "/ads.js", "/adsbygoogle", "/prebid", "/pixel?", "/tr?id=", "/collect?v="
    )

    fun isBlocked(uri: Uri): Boolean {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        var h = host
        while (true) {
            if (h in hosts) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.lastIndexOf('.')) break
            h = h.substring(dot + 1)
        }
        val path = (uri.encodedPath ?: "") + (uri.encodedQuery?.let { "?$it" } ?: "")
        return pathTokens.any { path.contains(it) }
    }

    private val mediaRegex = Regex("\\.(mp4|webm|m3u8|mpd|mov|m4v|3gp|mkv)(\\?|$)", RegexOption.IGNORE_CASE)

    /** O pedido parece ser um vídeo (ficheiro ou lista de reprodução). */
    fun looksLikeMedia(url: String): Boolean =
        mediaRegex.containsMatchIn(url) || url.contains("googlevideo.com/videoplayback") ||
            url.contains("/video/") && url.contains(".fbcdn.net")

    private val videoPages = listOf(
        Regex("youtube\\.com/(watch|shorts/|live/)"),
        Regex("youtu\\.be/"),
        Regex("tiktok\\.com/.+/video/"),
        Regex("facebook\\.com/.*(watch|videos?|reel)"),
        Regex("fb\\.watch/"),
        Regex("instagram\\.com/(reel|reels|p|tv)/"),
        Regex("(x|twitter)\\.com/.+/status/"),
        Regex("vimeo\\.com/\\d+"),
        Regex("dailymotion\\.com/video/"),
        Regex("reddit\\.com/r/.+/comments/"),
        Regex("twitch\\.tv/"),
        Regex("kwai\\.com/"),
        Regex("likee\\.video/")
    )

    /** É uma página de vídeo de um site conhecido (o motor de downloads trata dela directamente). */
    fun isKnownVideoPage(url: String): Boolean = videoPages.any { it.containsMatchIn(url) }
}
