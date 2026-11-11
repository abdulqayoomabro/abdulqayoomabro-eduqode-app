package com.eduqode.app.offline

/**
 * Where lesson videos come from. The server says, per lesson, how the video plays online
 * (YouTube, Vimeo, ...) and whether there is an authorized file the app may download.
 *
 * To add a provider later (for example Bunny Stream with signed links), add a class that
 * implements [OfflineVideoProvider] and register it in [VideoProviders.all]; on the WordPress
 * side return { "provider": "bunny", "url": ... } from the 'eduqode_app_offline_video' filter.
 * Nothing else in the app needs to change.
 *
 * YouTube videos are never downloaded: no stream extraction, no scraping.
 */
interface OfflineVideoProvider {
    val id: String
    /** The URL to download, or null if this source cannot be downloaded. */
    fun downloadUrl(source: OfflineVideoSource): String?
}

/** A plain MP4/WebM file on a server we own or are allowed to use (own server, S3 + CDN, Bunny storage, ...). */
object DirectFileProvider : OfflineVideoProvider {
    override val id = "direct"
    override fun downloadUrl(source: OfflineVideoSource): String? {
        val url = source.url.trim()
        return url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }
}

object VideoProviders {
    private val all: Map<String, OfflineVideoProvider> = listOf<OfflineVideoProvider>(DirectFileProvider).associateBy { it.id }

    /** Online-only providers: shown with "Online only — internet required". */
    private val onlineOnly = setOf("youtube", "vimeo", "embedded", "shortcode")

    fun downloadUrl(source: OfflineVideoSource?): String? {
        if (source == null) return null
        return all[source.provider]?.downloadUrl(source)
    }

    fun isOnlineOnly(provider: String) = provider in onlineOnly

    fun label(provider: String) = when (provider) {
        "youtube" -> "YouTube"
        "vimeo" -> "Vimeo"
        "html5" -> "Video"
        else -> "Video"
    }
}
