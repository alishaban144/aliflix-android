package com.aliflix.app.talk

import com.aliflix.app.model.MediaType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TalkGroundingTest {
    @Test fun dominantFeatureAndNumericTitlesResolveWithoutAddingAnUnrequestedEdition() {
        fun row(id:Int,title:String,year:Int,votes:Int)=com.aliflix.app.recommendation.V3CatalogMedia.fromJson(JSONObject()
            .put("tmdbId",id).put("mediaType","movie").put("title",title).put("releaseDate","$year-01-01").put("tmdbVoteCount",votes))
        val feature=row(264660,"Ex Machina",2015,13000);val short=row(1106784,"EX MACHINA",2023,2)
        assertEquals(listOf(feature.tmdbId),talkCatalogueMatches(listOf(feature,short),"Ex Machina",null,0).map{it.id})
        assertEquals(listOf(short.tmdbId),talkCatalogueMatches(listOf(feature,short),"Ex Machina",null,2023).map{it.id})
        val original=row(78,"Blade Runner",1982,10000);val sequel=row(335984,"Blade Runner 2049",2017,10000)
        assertEquals(listOf(sequel.tmdbId),talkCatalogueMatches(listOf(original,sequel),"Blade Runner 2049",null,0).map{it.id})
    }
    @Test fun comparisonChronologyUsesOnlyExistingIdentitiesAndQuestionOrdinalsDoNotSelectCards() {
        val first=listOf(TalkTitleCard(1,MediaType.MOVIE,"A choice"),TalkTitleCard(2,MediaType.MOVIE,"B choice"))
        val later=listOf(TalkTitleCard(3,MediaType.MOVIE,"C choice"),TalkTitleCard(4,MediaType.MOVIE,"D choice"))
        val cards=TalkCardContext().show(first).compared(first).show(later,true).compared(later)
        assertEquals(first,cards.choiceGroups.first())
        assertTrue(cards.references("What did I ask in my first question?").isEmpty())
        assertTrue(cards.references("Explain the first episode").isEmpty())
        assertEquals(listOf(later[0]),cards.references("Tell me about the first one"))
        assertFalse(cards.snapshot().toString().contains("What did I ask"))
        assertEquals(first,cards.question("Which two films were in my first question?").visible)
        assertTrue(cards.question("What are your technical limitations?").visible.isEmpty())
        assertTrue(cards.question("What is thirteen times seventeen?").visible.isEmpty())
        assertEquals(later,cards.question("Hi").visible)
        assertEquals(later,cards.question("Why is that?").visible)
        assertEquals(later,cards.question("Can you explain their differences?").visible)
    }
    private val annihilation=TalkTitleCard(300668,MediaType.MOVIE,"Annihilation","2018","/poster.jpg")
    private val other=TalkTitleCard(999,MediaType.MOVIE,"Annihilation","2011")
    @Test fun aReorderedShortlistCommitsItsOwnPostersAndSpeechTogether() {
        val pool=(1..6).map{TalkTitleCard(it,MediaType.MOVIE,"Film $it","200$it","/poster$it.jpg")}
        val picks=JSONArray(listOf(5,2,6).map{id->JSONObject().put("key","movie:$id").put("reason","A distinct thoughtful choice")})
        val plan=talkPresentation(pool,JSONObject().put("picks",picks))
        assertEquals(listOf(5,2,6),plan.cards.map{it.id})
        assertEquals(listOf("/poster5.jpg","/poster2.jpg","/poster6.jpg"),plan.cards.map{it.posterPath})
        assertTrue(talkAnswerNamesAllTitles(plan.speechText,plan.cards,pool))
        assertEquals(listOf(5,2),talkPresentation(pool,JSONObject().put("picks",JSONArray(listOf(5,2).map{id->JSONObject().put("key","movie:$id").put("reason","A distinct thoughtful choice")})),2).cards.map{it.id})
        assertTrue(runCatching{talkPresentation(pool,JSONObject().put("picks",JSONArray().put(JSONObject().put("key","movie:99").put("reason","Unknown film"))))}.isFailure)
        picks.getJSONObject(1).put("key","movie:5")
        assertTrue(runCatching{talkPresentation(pool,JSONObject().put("picks",picks))}.isFailure)
    }
    @Test fun followupsCannotTurnAnEstablishedRecommendationIntoAnEditionQuestion() {
        val context=TalkCardContext().show(listOf(other)).show(listOf(annihilation))
        assertEquals(listOf(annihilation),context.question("Tell me more about Annihilation").visible)
        assertEquals(listOf(annihilation),context.resolve("Annihilation"))
        assertEquals(listOf(other),context.resolve("Annihilation (2011)"))
        val args=context.ground(JSONObject().put("title","Annihilation").put("year",2011).put("kind","tv").put("tmdbId",999),"Tell me more about Annihilation")
        assertEquals(300668,args.getInt("tmdbId"));assertEquals(2018,args.getInt("year"));assertEquals("movie",args.getString("kind"))
        assertEquals(999,context.ground(args,"Show Annihilation (2011)").getInt("tmdbId"))
        assertEquals(listOf(annihilation),context.resolve("movie:300668"))
        assertTrue(talkContinuityTurns(emptyList(),true,context).toString().contains("300668"))
    }
    @Test fun aPartialTitleMatchOrWrongSpokenSelectionNeverPassesDelivery() {
        val bridge=talkResearchBridge("movies about gentlemen","en")
        assertTrue(talkIsApprovedBridge(bridge,bridge))
        assertFalse(talkIsApprovedBridge("$bridge Try Arrival, Moon and Gattaca.",bridge))
        val script="Annihilation (2018): An uncanny journey into change. Arrival (2016): Language reshapes a life. Moon (2009): Isolation and fractured identity."
        assertTrue(talkSpeechMatchesScript(script.replace(":",", "),script))
        assertFalse(talkSpeechMatchesScript("$script Try Interstellar too.",script))
        val cards=listOf(annihilation,TalkTitleCard(1,MediaType.MOVIE,"Arrival"),TalkTitleCard(2,MediaType.MOVIE,"Moon"))
        assertFalse(talkAnswerNamesAllTitles("Try Annihilation, Ex Machina and Interstellar",cards))
        assertFalse(talkAnswerNamesAllTitles("Try Annihilation",cards))
        assertTrue(talkAnswerNamesAllTitles("Annihilation (2018), Arrival and Moon are my three choices.",cards))
        assertFalse(talkAnswerNamesAllTitles("Annihilation, Arrival, Moon and Interstellar",cards,listOf(TalkTitleCard(3,MediaType.MOVIE,"Interstellar"))))
        val audio=TalkRecommendationDelivery();audio.hold(byteArrayOf(1,2));audio.clear();audio.hold(byteArrayOf(3,4))
        assertArrayEquals(byteArrayOf(3,4),audio.drain().single());assertTrue(audio.drain().isEmpty())
    }
    @Test fun artworkFormatsAndRestoredGroupsKeepExactIdentities() {
        val rows=JSONArray("""[{"id":300668,"type":"movie","title":"Annihilation","releaseDate":"2018-02-22","poster_path":"https://image.tmdb.org/t/p/w500/poster.jpg"}]""")
        assertEquals(annihilation,parseTalkTitles(rows).single())
        assertNull(talkPosterPath("//evil.example/poster.jpg"));assertNull(talkPosterPath("https://evil.example/poster.jpg"))
        val group=listOf(TalkTitleCard(1,MediaType.MOVIE,"First"),annihilation,other)
        val focused=TalkCardContext().show(group).show(listOf(annihilation))
        assertEquals(listOf(other),focused.resolve("the third one"))
        assertEquals(group,focused.group)
        assertEquals(annihilation.id,focused.hydrate(listOf(annihilation.copy(posterPath="/new.jpg"))).visible.single().id)
        val restored=talkRestoredCards(listOf(TalkLine("assistant","Three choices",titles=group),TalkLine("assistant","Focus",titles=listOf(annihilation.copy(posterPath="/new.jpg")))))
        assertEquals(group,restored.group)
        assertEquals("/new.jpg",restored.visible.single().posterPath)
        assertEquals("/new.jpg",restored.resolve("Annihilation").single().posterPath)
    }
    @Test fun numericTitlesAndHumanPronounsOverrideWrongModelCardGuesses() {
        val original=TalkTitleCard(78,MediaType.MOVIE,"Blade Runner","1982")
        val sequel=TalkTitleCard(335984,MediaType.MOVIE,"Blade Runner 2049","2017")
        val context=TalkCardContext().show(listOf(original,sequel,annihilation)).show(listOf(sequel))
        assertEquals(listOf(sequel),context.resolve("Blade Runner 2049"))
        assertEquals(listOf(original),context.resolve("Blade Runner"))
        assertEquals(listOf(sequel),context.references("Show its card again."))
        assertEquals(listOf(sequel),context.question("Tell me more about Blade Runner 2049").visible)
        assertEquals(listOf(original,sequel),context.references("Compare Blade Runner with Blade Runner 2049"))
        val wrong=JSONObject().put("title","Blade Runner").put("year",1982).put("tmdbId",78)
        assertEquals(sequel.id,context.ground(wrong,"Show its card again").getInt("tmdbId"))
        assertEquals(original.id,context.ground(wrong,"Show Blade Runner (1982)").getInt("tmdbId"))
        for(title in listOf("1917","2001: A Space Odyssey")) {
            val numeric=TalkTitleCard(1,MediaType.MOVIE,title,"1968")
            assertEquals(listOf(numeric),TalkCardContext().show(listOf(numeric)).references("Tell me more about $title"))
        }
    }
}
