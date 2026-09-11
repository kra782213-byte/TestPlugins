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

    // 1. ÇALIŞAN ZENGİN KATEGORİ KATALOĞU
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Yeni Eklenen Filmler",
        "$mainUrl/imdb-7-puan-uzeri-filmler-2/page/" to "IMDB 7+ Filmler",
        "$mainUrl/tur/aksiyon-filmlerini-izleyin/page/" to "Aksiyon Filmleri",
        "$mainUrl/tur/bilim-kurgu-filmlerini-izleyin-5/page/" to "Bilim Kurgu Filmleri",
        "$mainUrl/tur/komedi-filmlerini-izleyin-1/page/" to "Komedi Filmleri",
        "$mainUrl/tur/korku-filmlerini-izleyin/page/" to "Korku Filmleri"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            request.data.removeSuffix("page/")
        } else {
            "${request.data}$page"
        }
        
        val document = app.get(url).document
        val home = ArrayList<SearchResponse>()

        // Film kartları için genişletilmiş seçiciler
        val elements = document.select("div.card-body, a.poster, div.poster-card, div.poster, article.card, div.card, a.card-single")
        
        elements.forEach { element ->
            val movie = parseMovieElement(element)
            if (movie != null) {
                home.add(movie)
            }
        }

        // Çift kart eklenmesini önlemek için tekilleştirme
        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun parseMovieElement(element: Element): SearchResponse? {
        val title = element.selectFirst("h2, h3, h4, .title, .poster-title")?.text()
            ?: element.selectFirst("img")?.attr("alt")
            ?: element.attr("title")

        val link = element.selectFirst("a")?.attr("href") ?: element.attr("href")
        if (link.isBlank() || link == "#") return null
        
        var posterUrl = element.selectFirst("img")?.let { img ->
            val dataSrc = img.attr("data-src")
            val src = img.attr("src")
            val lazySrc = img.attr("data-lazy-src")
            
            if (dataSrc.isNotBlank()) dataSrc
            else if (src.isNotBlank()) src
            else lazySrc
        } ?: ""

        if (posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        val cleanLink = fixUrlNull(link) ?: return null
        val cleanTitle = title.trim()

        if (cleanTitle.isNotBlank()) {
            return newMovieSearchResponse(cleanTitle, cleanLink, TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/arama/$query"
        val document = app.get(searchUrl).document
        val searchResults = ArrayList<SearchResponse>()

        val elements = document.select("div.card-body, a.poster, div.poster-card, div.poster, article.card, div.card")
        elements.forEach { element ->
            val movie = parseMovieElement(element)
            if (movie != null) {
                searchResults.add(movie)
            }
        }
        return searchResults.distinctBy { it.url }
    }

    // 2. FİLM DETAYLARINI ÇEKME (Afiş & Özet Düzeltildi)
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.title, h1, .post-title, .entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Bilinmeyen Film"
        
        var posterUrl = document.selectFirst("img.poster, div.poster img, div.card-body img")?.let { img ->
            val dataSrc = img.attr("data-src")
            val src = img.attr("src")
            if (dataSrc.isNotBlank()) dataSrc else src
        } ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        
        val plot = document.selectFirst("div.overview, div.summary, p.description, article.post-content, div.movie-description, div.content p")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.plot = plot
        }
    }

    // 3. VİDEO LİNKLERİNİ VE PLAYER SOURCELARINI ÇEKME (Sorun Çözüldü)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val extractedUrls = HashSet<String>()

        // Sayfadaki iframe bağlantılarını yakala
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotBlank() && !src.contains("facebook") && !src.contains("google") && !src.contains("twitter")) {
                fixUrlNull(src)?.let { extractedUrls.add(it) }
            }
        }

        // Sekme veya butonlarda yer alan player adreslerini yakala
        document.select("[data-video], [data-src], nav a[href], div.player-nav button").forEach { element ->
            val videoUrl = element.attr("data-video").ifEmpty { element.attr("data-src") }
            if (videoUrl.isNotBlank() && !videoUrl.startsWith("#")) {
                fixUrlNull(videoUrl)?.let { extractedUrls.add(it) }
            }
        }

        // Sayfa kaynak kodundaki gizli player URL'lerini Regex ile tara
        val html = document.html()
        val videoRegex = Regex("""(?i)(https?:\\?/\\?/[^"'\s<>]+\.(?:m3u8|mp4)|https?:\\?/\\?/[^"'\s<>]+(?:player|embed|v|video)[^"'\s<>]*)""")
        videoRegex.findAll(html).forEach { match ->
            val rawUrl = match.value.replace("\\/", "/")
            if (!rawUrl.contains("facebook") && !rawUrl.contains("google") && !rawUrl.contains("disqus")) {
                fixUrlNull(rawUrl)?.let { extractedUrls.add(it) }
            }
        }

        // Bulunan kaynakları Cloudstream oynatıcı motoruna ilet
        for (url in extractedUrls) {
            loadExtractor(url, subtitleCallback, callback)

            if (url.contains("hdfilmcehennemi") || url.contains("player")) {
                try {
                    val playerDoc = app.get(url).document
                    val innerIframe = playerDoc.selectFirst("iframe")?.attr("src")
                        ?: playerDoc.selectFirst("iframe")?.attr("data-src")
                    if (!innerIframe.isNullOrBlank()) {
                        fixUrlNull(innerIframe)?.let { loadExtractor(it, subtitleCallback, callback) }
                    }
                } catch (e: Exception) {
                    // Sayfa yükleme hatasını yut
                }
            }
        }

        return extractedUrls.isNotEmpty()
    }
}
