package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object TmdbKeywordParser {

    fun parseKeywordSearchResults(html: String): List<ResolvedKeyword> {
        val doc = Jsoup.parse(html)
        val results = mutableListOf<ResolvedKeyword>()
        
        // TMDB public keyword search results page structure
        val elements = doc.select("div.results.flex div.search_results.keyword div.details")
        for (element in elements) {
            val link = element.selectFirst("a[href^=/keyword/]") ?: continue
            val url = link.attr("href")
            val idStr = url.substringAfter("/keyword/").substringBefore("-")
            val id = idStr.toIntOrNull() ?: continue
            val name = link.text().trim()
            if (name.isNotEmpty()) {
                results.add(ResolvedKeyword(id, name))
            }
        }
        return results
    }

    fun parseDiscoverPage(html: String, expectedType: MediaType): List<Media> {
        val doc = Jsoup.parse(html)
        val results = mutableListOf<Media>()

        // TMDB public discover page structure
        val cards = doc.select("div.card.style_1")
        for (card in cards) {
            val wrapper = card.selectFirst("div.wrapper") ?: continue
            val imageElement = wrapper.selectFirst("img.poster")
            val posterPath = imageElement?.attr("src")?.substringAfter("https://media.themoviedb.org/t/p/w220_and_h330_face")
            
            val details = card.selectFirst("div.details") ?: continue
            val titleLink = details.selectFirst("a.title") ?: continue
            val url = titleLink.attr("href")
            val idStr = url.substringAfter("/movie/").substringAfter("/tv/").substringBefore("-")
            val id = idStr.toIntOrNull() ?: continue
            val title = titleLink.text().trim()
            val dateStr = details.selectFirst("p")?.text()?.trim()
            val year = dateStr?.takeLast(4)?.toIntOrNull()?.toString() ?: ""

            // Simple media object for verification candidate collection
            results.add(
                Media(
                    id = id,
                    type = expectedType,
                    title = title,
                    overview = "",
                    genres = emptyList(),
                    year = year,
                    posterPath = posterPath,
                    backdropPath = null,
                    rating = 0.0,
                    cast = emptyList()
                )
            )
        }
        return results
    }

    fun parseTitleKeywords(html: String): List<ResolvedKeyword> {
        val doc = Jsoup.parse(html)
        val results = mutableListOf<ResolvedKeyword>()
        
        val keywordSection = doc.selectFirst("section.keywords") ?: return results
        val links = keywordSection.select("ul li a[href^=/keyword/]")
        for (link in links) {
            val url = link.attr("href")
            val idStr = url.substringAfter("/keyword/").substringBefore("-")
            val id = idStr.toIntOrNull() ?: continue
            val name = link.text().trim()
            if (name.isNotEmpty()) {
                results.add(ResolvedKeyword(id, name))
            }
        }
        return results
    }
}

data class ResolvedKeyword(
    val id: Int,
    val name: String
)
