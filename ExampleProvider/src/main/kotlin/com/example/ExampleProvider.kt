@file:Suppress("DEPRECATION", "PARAMETER_NAME_CHANGED_ON_OVERRIDE", "UNUSED_PARAMETER")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Yeni Eklenen Filmler",
        "$mainUrl/turkce-dublaj-film-izle/page/" to "Türkçe Dublaj Filmler",
        "$mainUrl/turkce-altyazili-film-izle/page/" to "Türkçe Altyazılı Filmler",
        "$mainUrl/imdb-7-puan-uzeri-filmler/page/" to "IMDb 7+ Filmler",
        "$mainUrl/kategori/aksiyon/page/" to "Aksiyon Filmleri",
        "$mainUrl/kategori/bilim-kurgu/page/" to "Bilim Kurgu Filmleri",
        "$mainUrl/kategori/korku/page/" to "Korku Filmleri",
        "$mainUrl/kategori/komedi/page/" to "Komedi Filmleri",
    )

    private fun fixUrlSafe(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return mainUrl + url
        return url
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.title, h3, .poster-title, .title a")?.text() ?: this.attr("title")
        val link = this.selectFirst("a")?.attr("href") ?: this.attr("href")
        val posterUrl = this.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""

        if (title.isBlank() || link.isBlank()) return null

        return newMovieSearchResponse(title, fixUrlSafe(link), TvType.Movie) {
            this.posterUrl = fixUrlSafe(posterUrl)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data.substringBeforeLast("page/") else request.data + page
        val document = app.get(url).document
        
        val home = document.select("div.card-body, div.poster-card-body, a.poster, div.poster, article.card").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama/$query"
        val document = app.get(url).document
        
        return document.select("div.card-body, div.poster-card-body, a.poster, div.poster, article.card").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title, h1, .post-title")?.text()?.trim() ?: "Bilinmeyen Film"
        val poster = document.selectFirst("img.poster, div.card-body img, div.poster img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: ""
        val plot = document.selectFirst("div.summary, div.overview, article.post-content p, div.movie-description")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlSafe(poster)
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            val fixedSrc = fixUrlSafe(src)
            if (fixedSrc.isNotBlank() && !fixedSrc.contains("youtube")) {
                if (fixedSrc.contains(mainUrl) || fixedSrc.contains("/player/")) {
                    extractInternalPlayer(fixedSrc, data, subtitleCallback, callback)
                    foundLinks = true
                } else {
                    loadExtractor(fixedSrc, data, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }

        val rawHtml = document.html()
        val m3u8Regex = Regex("['\"](https?://[^'\"]*?\\.m3u8[^'\"]*?)['\"]")
        val mp4Regex = Regex("['\"](https?://[^'\"]*?\\.mp4[^'\"]*?)['\"]")

        m3u8Regex.findAll(rawHtml).forEach { match ->
            val videoUrl = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
            foundLinks = true
        }

        mp4Regex.findAll(rawHtml).forEach { match ->
            val videoUrl = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
            foundLinks = true
        }

        return foundLinks
    }

    private suspend fun extractInternalPlayer(
        playerUrl: String, 
        referer: String, 
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(playerUrl, headers = mapOf("Referer" to referer)).text
            val m3u8Regex = Regex("['\"](https?://[^'\"]*?\\.m3u8[^'\"]*?)['\"]")
            m3u8Regex.findAll(response).forEach { match ->
                val link = match.groupValues[1]
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "HDFC Özel",
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = playerUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }
}
