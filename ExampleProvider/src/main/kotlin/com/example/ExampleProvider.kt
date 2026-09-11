package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class HDFilmCehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Yeni Filmler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        val home = ArrayList<SearchResponse>()

        document.select("div.card-body.poster-card-body").forEach { element ->
            val title = element.selectFirst("h2.title")?.text() ?: ""
            val link = element.selectFirst("a")?.attr("href") ?: ""
            val posterUrl = element.selectFirst("img")?.attr("data-src") ?: ""

            if (title.isNotBlank() && link.isNotBlank()) {
                val movie = newMovieSearchResponse(title, link, TvType.Movie)
                movie.posterUrl = posterUrl
                home.add(movie)
            }
        }

        return newHomePageResponse(HomePageList(request.name, home))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/arama/$query"
        val document = app.get(searchUrl).document
        val searchResults = ArrayList<SearchResponse>()

        document.select("div.card-body.poster-card-body").forEach { element ->
            val title = element.selectFirst("h2.title")?.text() ?: ""
            val link = element.selectFirst("a")?.attr("href") ?: ""
            val posterUrl = element.selectFirst("img")?.attr("data-src") ?: ""

            if (title.isNotBlank() && link.isNotBlank()) {
                val movie = newMovieSearchResponse(title, link, TvType.Movie)
                movie.posterUrl = posterUrl
                searchResults.add(movie)
            }
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title")?.text() ?: "Bilinmeyen Film"
        val posterUrl = document.selectFirst("img.poster")?.attr("src")
        val plot = document.selectFirst("div.summary")?.text()

        val response = newMovieLoadResponse(title, url, TvType.Movie, url)
        response.posterUrl = posterUrl
        response.plot = plot
        return response
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return true
    }
}
