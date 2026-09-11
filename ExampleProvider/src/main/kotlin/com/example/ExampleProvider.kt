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

    // 1. KATEGORİ VİTRİNLERİ
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Yeni Filmler",
        "$mainUrl/tur/aksiyon-filmlerini-izleyin/page/" to "Aksiyon Filmleri",
        "$mainUrl/tur/korku-filmlerini-izleyin/page/" to "Korku Filmleri",
        "$mainUrl/tur/komedi-filmlerini-izleyin-1/page/" to "Komedi Filmleri",
        "$mainUrl/tur/bilim-kurgu-filmlerini-izleyin-5/page/" to "Bilim Kurgu Filmleri"
    )

    // Linkleri düzeltmek için %100 güvenli fonksiyon (Hata verdirtmez)
    private fun fixUrlSafe(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return mainUrl + url
        return url
    }

    // Afiş ve Başlıkları çeken yardımcı araç
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.title, h3, .poster-title")?.text() ?: this.attr("title")
        val link = this.selectFirst("a")?.attr("href") ?: this.attr("href")
        
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: ""

        if (title.isBlank() || link.isBlank()) return null

        return newMovieSearchResponse(title, fixUrlSafe(link), TvType.Movie) {
            this.posterUrl = fixUrlSafe(posterUrl)
        }
    }

    // 2. ANA SAYFAYI LİSTELEME
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data.substringBeforeLast("page/") else request.data + page
        val document = app.get(url).document
        
        val home = document.select("div.card-body, div.poster-card-body, a.poster, div.poster, article.card").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    // 3. ARAMA YAPMA
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama/$query"
        val document = app.get(url).document
        
        val results = document.select("div.card-body, div.poster-card-body, a.poster, div.poster, article.card").mapNotNull {
            it.toSearchResult()
        }
        return results.distinctBy { it.url }
    }

    // 4. FİLM DETAYLARI (Özet, Başlık, Afiş)
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title, h1, .post-title")?.text()?.trim() ?: "Bilinmeyen Film"
        
        val poster = document.selectFirst("img.poster, div.card-body img, div.poster img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: ""
        
        val plot = document.selectFirst("div.summary, div.overview, article.post-content, div.movie-description")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlSafe(poster)
            this.plot = plot
        }
    }

    // 5. OYNATICI LİNKLERİNİ ÇEKME VE ÇÖZME (En kritik yer)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // Sayfadaki direkt iframeleri bul
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            val fixedSrc = fixUrlSafe(src)
            if (fixedSrc.isNotBlank() && !fixedSrc.contains("youtube") && !fixedSrc.contains("facebook")) {
                loadExtractor(fixedSrc, subtitleCallback, callback)
                foundLinks = true
            }
        }

        // Sayfada buton veya sekmelere gizlenmiş video adreslerini bul
        document.select("[data-video], [data-src]").forEach { element ->
            val src = element.attr("data-video").ifEmpty { element.attr("data-src") }
            val fixedSrc = fixUrlSafe(src)
            
            if (fixedSrc.isNotBlank() && fixedSrc.startsWith("http") && !fixedSrc.contains("youtube")) {
                // Eğer sitenin kendi oynatıcısıysa içine girip asıl videoyu çıkart
                if (fixedSrc.contains("hdfilmcehennemi") || fixedSrc.contains("player")) {
                    try {
                        val playerDoc = app.get(fixedSrc).document
                        playerDoc.select("iframe").forEach { innerIframe ->
                            val innerSrc = innerIframe.attr("data-src").ifEmpty { innerIframe.attr("src") }
                            val fixedInner = fixUrlSafe(innerSrc)
                            if (fixedInner.isNotBlank()) {
                                loadExtractor(fixedInner, subtitleCallback, callback)
                                foundLinks = true
                            }
                        }
                    } catch (e: Exception) {
                        // Hata olursa es geç, uygulamanın çökmesini engelle
                    }
                } else {
                    loadExtractor(fixedSrc, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }
}
