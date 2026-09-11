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
        "$mainUrl/page/" to "Yeni Filmler",
        "$mainUrl/en-cok-izlenenler/page/" to "En Çok İzlenenler",
        "$mainUrl/imdb-7-uzeri/page/" to "IMDB 7+ Filmler"
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

        // Sitedeki farklı tema ve tasarımlara karşı çoklu seçici kullanımı
        val elements = document.select("div.card-body, a.poster, div.poster-card, div.poster, article.card, div.card")
        
        elements.forEach { element ->
            val movie = parseMovieElement(element)
            if (movie != null) {
                home.add(movie)
            }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun parseMovieElement(element: Element): SearchResponse? {
        val title = element.selectFirst("h2, h3, .title, .poster-title, img")?.let {
            if (it.tagName() == "img") it.attr("alt") else it.text()
        } ?: element.attr("title")

        val link = element.selectFirst("a")?.attr("href") ?: element.attr("href")
        
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

        if (cleanTitle.isNotBlank() && cleanLink.isNotBlank()) {
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
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.title, h1, .post-title")?.text()?.trim() ?: "Bilinmeyen Film"
        
        val posterUrl = document.selectFirst("img.poster, div.poster img, img")?.let { img ->
            val dataSrc = img.attr("data-src")
            if (dataSrc.isNotBlank()) dataSrc else img.attr("src")
        }
        
        val plot = document.selectFirst("div.summary, div.overview, p.description, article.post-content")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // İlerleyen adımlarda video kaynakları eklenecektir
        return true
    }
}
