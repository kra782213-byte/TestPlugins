package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    // 1. ZENGİNLEŞTİRİLMİŞ KATALOGLAR
    // HDFilmCehennemi'nin güncel ve çalışan kategori bağlantıları eklendi.
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Yeni Eklenen Filmler",
        "$mainUrl/turkce-dublaj-film-izle/page/" to "Türkçe Dublaj Filmler",
        "$mainUrl/turkce-altyazili-film-izle/page/" to "Türkçe Altyazılı Filmler",
        "$mainUrl/imdb-7-puan-uzeri-filmler/page/" to "IMDb 7+ Filmler",
        "$mainUrl/kategori/aksiyon/page/" to "Aksiyon",
        "$mainUrl/kategori/bilim-kurgu/page/" to "Bilim Kurgu",
        "$mainUrl/kategori/korku/page/" to "Korku",
        "$mainUrl/kategori/komedi/page/" to "Komedi",
        "$mainUrl/kategori/animasyon/page/" to "Animasyon"
    )

    // URL'leri güvenli hale getiren yardımcı
    private fun fixUrlSafe(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return mainUrl + url
        return url
    }

    // Arama ve Ana Sayfa sonuçlarını işleyen ortak araç
    private fun Element.toSearchResult(): SearchResponse? {
        // Site tasarımındaki olası tüm başlık etiketleri
        val title = this.selectFirst("h2.title, h3, .poster-title, .title a")?.text() 
            ?: this.attr("title")
        
        // Bağlantı adresi
        val link = this.selectFirst("a")?.attr("href") ?: this.attr("href")
        
        // Afiş resmi
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: ""

        if (title.isBlank() || link.isBlank()) return null

        return newMovieSearchResponse(title, fixUrlSafe(link), TvType.Movie) {
            this.posterUrl = fixUrlSafe(posterUrl)
        }
    }

    // 2. ANA SAYFA ÇEKİMİ
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data.substringBeforeLast("page/") else request.data + page
        val document = app.get(url).document
        
        // Film kartlarını bul
        val home = document.select("div.card-body, div.poster-card-body, a.poster, div.poster, article.card").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    // 3. ARAMA FONKSİYONU
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama/$query"
        val document = app.get(url).document
        
        return document.select("div.card-body, div.poster-card-body, a.poster, div.poster, article.card").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    // 4. FİLM DETAYLARI
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title, h1, .post-title")?.text()?.trim() ?: "Bilinmeyen Film"
        
        val poster = document.selectFirst("img.poster, div.card-body img, div.poster img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: ""
        
        // Daha geniş özet seçicileri eklendi
        val plot = document.selectFirst("div.summary, div.overview, article.post-content p, div.movie-description")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlSafe(poster)
            this.plot = plot
        }
    }

    // 5. GİZLİ OYNATICILARI VE VİDEOLARI BULMA (KESİN ÇÖZÜM)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // ADIM 1: Sitedeki direk iframe'leri bul (Vidmoly, Closeload vb.)
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            val fixedSrc = fixUrlSafe(src)
            
            if (fixedSrc.isNotBlank() && !fixedSrc.contains("youtube")) {
                if (fixedSrc.contains(mainUrl) || fixedSrc.contains("/player/")) {
                    // Eğer sitenin kendi oynatıcısıysa, içine girip gerçek videoyu çıkart
                    extractInternalPlayer(fixedSrc, data, subtitleCallback, callback)
                    foundLinks = true
                } else {
                    // Bulut sağlayıcısıysa Cloudstream'in hazır çözücülerini (Extractor) kullan
                    loadExtractor(fixedSrc, data, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }

        // ADIM 2: Butonların veya sekmelerin içine gizlenmiş veri linklerini (data-url) bul
        document.select("[data-src], [data-url], [data-video], .server-btn, div.nav-tabs a").forEach { el ->
            val src = el.attr("data-src").ifEmpty { el.attr("data-url") }.ifEmpty { el.attr("data-video") }
            val fixedSrc = fixUrlSafe(src)
            
            if (fixedSrc.isNotBlank() && fixedSrc.startsWith("http") && !fixedSrc.contains("youtube")) {
                if (fixedSrc.contains(mainUrl) || fixedSrc.contains("/player/")) {
                    extractInternalPlayer(fixedSrc, data, subtitleCallback, callback)
                    foundLinks = true
                } else {
                    loadExtractor(fixedSrc, data, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }

        // ADIM 3: Sayfanın kaynak koduna doğrudan gömülü M3U8 veya MP4 varsa Regex ile bul
        val rawHtml = document.html()
        val m3u8Regex = Regex("['\"](https?://[^'\"]*?\\.m3u8[^'\"]*?)['\"]")
        val mp4Regex = Regex("['\"](https?://[^'\"]*?\\.mp4[^'\"]*?)['\"]")

        m3u8Regex.findAll(rawHtml).forEach { match ->
            val videoUrl = match.groupValues[1]
            callback.invoke(
                ExtractorLink(name, "HDFilmCehennemi M3U8", videoUrl, data, Qualities.Unknown.value, true)
            )
            foundLinks = true
        }

        mp4Regex.findAll(rawHtml).forEach { match ->
            val videoUrl = match.groupValues[1]
            callback.invoke(
                ExtractorLink(name, "HDFilmCehennemi MP4", videoUrl, data, Qualities.Unknown.value, false)
            )
            foundLinks = true
        }

        return foundLinks
    }

    // Sitenin kendi oynatıcısının ("player/index.php") içine sızıp gerçek linki alan fonksiyon
    private suspend fun extractInternalPlayer(
        playerUrl: String, 
        referer: String, 
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Sunucunun bizi engellememesi için "Referer" başlığı gönderiyoruz (ÇOK ÖNEMLİ)
            val response = app.get(playerUrl, headers = mapOf("Referer" to referer)).text
            val doc = Jsoup.parse(response)

            // 1. Oynatıcının içinde başka bir iframe varsa onu çöz
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                val fixedSrc = fixUrlSafe(src)
                if(fixedSrc.isNotBlank() && !fixedSrc.contains("youtube")) {
                    loadExtractor(fixedSrc, playerUrl, subtitleCallback, callback)
                }
            }

            // 2. Oynatıcının javascript kodları içine gömülmüş gizli linkleri çıkart
            val m3u8Regex = Regex("['\"](https?://[^'\"]*?\\.m3u8[^'\"]*?)['\"]")
            m3u8Regex.findAll(response).forEach { match ->
                val link = match.groupValues[1]
                callback.invoke(
                    ExtractorLink(name, "HDFC Özel Oynatıcı", link, playerUrl, Qualities.Unknown.value, true)
                )
            }
            
            val mp4Regex = Regex("['\"](https?://[^'\"]*?\\.mp4[^'\"]*?)['\"]")
            mp4Regex.findAll(response).forEach { match ->
                val link = match.groupValues[1]
                callback.invoke(
                    ExtractorLink(name, "HDFC Oynatıcı (MP4)", link, playerUrl, Qualities.Unknown.value, false)
                )
            }
        } catch (e: Exception) {
            // Player açılamazsa uygulama çökmesin diye hatayı yutuyoruz
            e.printStackTrace()
        }
    }
}
