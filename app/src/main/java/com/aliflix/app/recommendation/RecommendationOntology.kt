package com.aliflix.app.recommendation

import java.util.Locale

data class SemanticFacetMatch(
    val facet: SemanticFacet,
    val alias: String,
    val start: Int,
    val end: Int,
    val negated: Boolean,
)

object RecommendationOntology {
    private data class Entry(
        val facet: SemanticFacet,
        val aliases: List<String>,
    )

    private fun facet(
        id: String,
        label: String,
        category: SemanticFacetCategory,
        vararg aliases: String,
    ) = Entry(
        facet = SemanticFacet(
            id = id,
            label = label,
            category = category,
            discoveryTerms = (listOf(label) + aliases).distinct(),
        ),
        aliases = (listOf(label) + aliases)
            .map(::normalize)
            .filter(String::isNotBlank)
            .distinct(),
    )

    private val entries = listOf(
        facet("psychological", "Psychological", SemanticFacetCategory.SUBGENRE, "psychological thriller", "psychological drama"),
        facet("neo_noir", "Neo-noir", SemanticFacetCategory.SUBGENRE, "modern noir"),
        facet("noir", "Film noir", SemanticFacetCategory.SUBGENRE, "noir"),
        facet("whodunit", "Whodunit", SemanticFacetCategory.SUBGENRE, "murder mystery", "detective mystery"),
        facet("procedural", "Procedural", SemanticFacetCategory.SUBGENRE, "police procedural", "investigative procedural"),
        facet("courtroom", "Courtroom", SemanticFacetCategory.SUBGENRE, "courtroom drama", "legal drama"),
        facet("medical", "Medical", SemanticFacetCategory.SUBGENRE, "medical drama", "hospital drama"),
        facet("heist", "Heist", SemanticFacetCategory.SUBGENRE, "robbery", "caper"),
        facet("revenge", "Revenge", SemanticFacetCategory.SUBGENRE, "vengeance"),
        facet("survival", "Survival", SemanticFacetCategory.SUBGENRE, "fight for survival"),
        facet("folk_horror", "Folk horror", SemanticFacetCategory.SUBGENRE),
        facet("body_horror", "Body horror", SemanticFacetCategory.SUBGENRE),
        facet("cosmic_horror", "Cosmic horror", SemanticFacetCategory.SUBGENRE, "lovecraftian"),
        facet("found_footage", "Found footage", SemanticFacetCategory.SUBGENRE),
        facet("gothic", "Gothic", SemanticFacetCategory.SUBGENRE, "gothic horror", "gothic romance"),
        facet("creature_feature", "Creature feature", SemanticFacetCategory.SUBGENRE, "monster movie"),
        facet("cyberpunk", "Cyberpunk", SemanticFacetCategory.SUBGENRE),
        facet("space_opera", "Space opera", SemanticFacetCategory.SUBGENRE),
        facet("time_travel", "Time travel", SemanticFacetCategory.SUBGENRE),
        facet("time_loop", "Time loop", SemanticFacetCategory.SUBGENRE),
        facet("slice_of_life", "Slice of life", SemanticFacetCategory.SUBGENRE),
        facet("coming_of_age", "Coming of age", SemanticFacetCategory.SUBGENRE, "coming-of-age"),
        facet("political", "Political", SemanticFacetCategory.SUBGENRE, "political thriller", "political drama"),
        facet("conspiracy", "Conspiracy", SemanticFacetCategory.SUBGENRE, "paranoid thriller"),
        facet("espionage", "Espionage", SemanticFacetCategory.SUBGENRE, "spy thriller", "spy drama"),
        facet("social_realism", "Social realism", SemanticFacetCategory.SUBGENRE, "social drama"),
        facet("disaster", "Disaster", SemanticFacetCategory.SUBGENRE, "disaster movie"),
        facet("road_movie", "Road story", SemanticFacetCategory.SUBGENRE, "road movie", "road trip"),
        facet("chamber", "One-location", SemanticFacetCategory.SUBGENRE, "single location", "contained thriller", "chamber drama"),
        facet("anthology", "Anthology", SemanticFacetCategory.SUBGENRE),
        facet("true_story", "True story", SemanticFacetCategory.SUBGENRE, "based on a true story", "true events"),
        facet("mockumentary", "Mockumentary", SemanticFacetCategory.SUBGENRE),
        facet("dark_comedy", "Dark comedy", SemanticFacetCategory.SUBGENRE, "black comedy"),
        facet("romantic_comedy", "Romantic comedy", SemanticFacetCategory.SUBGENRE, "rom com", "romcom"),
        facet("tragicomedy", "Tragicomedy", SemanticFacetCategory.SUBGENRE),
        facet("adult_animation", "Adult animation", SemanticFacetCategory.SUBGENRE, "animated for adults"),
        facet("anime", "Anime", SemanticFacetCategory.SUBGENRE, "japanese animation"),

        facet("grief", "Grief", SemanticFacetCategory.THEME, "bereavement", "loss of a loved one"),
        facet("identity", "Identity", SemanticFacetCategory.THEME, "self discovery", "self-discovery"),
        facet("friendship", "Friendship", SemanticFacetCategory.THEME),
        facet("family_dynamics", "Family dynamics", SemanticFacetCategory.THEME, "dysfunctional family"),
        facet(
            "morality",
            "Moral conflict",
            SemanticFacetCategory.THEME,
            "moral dilemma",
            "ethical dilemma",
            "moral ambiguity",
        ),
        facet("class", "Class conflict", SemanticFacetCategory.THEME, "class inequality"),
        facet("corruption", "Corruption", SemanticFacetCategory.THEME),
        facet("justice", "Justice", SemanticFacetCategory.THEME),
        facet("obsession", "Obsession", SemanticFacetCategory.THEME),
        facet("isolation", "Isolation", SemanticFacetCategory.THEME, "loneliness"),
        facet("memory", "Memory", SemanticFacetCategory.THEME, "amnesia"),
        facet("dreams", "Dreams", SemanticFacetCategory.THEME, "dream world"),
        facet("technology", "Technology", SemanticFacetCategory.THEME, "artificial intelligence", "ai"),
        facet("climate", "Climate", SemanticFacetCategory.THEME, "environmental"),
        facet("religion", "Religion", SemanticFacetCategory.THEME, "faith"),
        facet("colonialism", "Colonialism", SemanticFacetCategory.THEME, "postcolonial"),
        facet("migration", "Migration", SemanticFacetCategory.THEME, "immigration"),
        facet("motherhood", "Motherhood", SemanticFacetCategory.THEME),
        facet("fatherhood", "Fatherhood", SemanticFacetCategory.THEME),
        facet("queer", "Queer themes", SemanticFacetCategory.THEME, "lgbt", "lgbtq"),

        facet("bleak", "Bleak", SemanticFacetCategory.TONE, "grim", "hopeless"),
        facet("warm", "Warm", SemanticFacetCategory.TONE, "heartwarming"),
        facet("cozy", "Cozy", SemanticFacetCategory.TONE, "comforting", "comfort watch"),
        facet("tense", "Tense", SemanticFacetCategory.TONE, "suspenseful"),
        facet("unsettling", "Unsettling", SemanticFacetCategory.TONE, "disturbing"),
        facet("uplifting", "Uplifting", SemanticFacetCategory.TONE, "feel good", "feel-good"),
        facet("sentimental", "Sentimental", SemanticFacetCategory.TONE, "sappy", "cheesy"),
        facet("cynical", "Cynical", SemanticFacetCategory.TONE),
        facet("hopeful", "Hopeful", SemanticFacetCategory.TONE, "optimistic"),
        facet("melancholic", "Melancholic", SemanticFacetCategory.TONE, "melancholy"),
        facet("absurdist", "Absurdist", SemanticFacetCategory.TONE, "absurd"),
        facet("deadpan", "Deadpan", SemanticFacetCategory.TONE, "dry humor", "dry comedy"),
        facet("campy", "Campy", SemanticFacetCategory.TONE, "camp"),
        facet("gritty", "Gritty", SemanticFacetCategory.TONE, "grounded"),
        facet("whimsical", "Whimsical", SemanticFacetCategory.TONE),
        facet("eerie", "Eerie", SemanticFacetCategory.TONE, "creepy"),

        facet("slow_burn", "Slow-burn", SemanticFacetCategory.PACE, "slow burn", "deliberate pace"),
        facet("fast_paced", "Fast-paced", SemanticFacetCategory.PACE, "fast paced", "quick paced"),
        facet("meditative", "Meditative", SemanticFacetCategory.PACE, "contemplative"),
        facet("relentless", "Relentless", SemanticFacetCategory.PACE, "nonstop", "non-stop"),
        facet("episodic", "Episodic", SemanticFacetCategory.PACE),

        facet("rural", "Rural", SemanticFacetCategory.SETTING, "countryside", "village"),
        facet("urban", "Urban", SemanticFacetCategory.SETTING, "big city"),
        facet("small_town", "Small town", SemanticFacetCategory.SETTING),
        facet("space", "Space", SemanticFacetCategory.SETTING, "outer space"),
        facet("ocean", "At sea", SemanticFacetCategory.SETTING, "ocean", "ship"),
        facet("desert", "Desert", SemanticFacetCategory.SETTING),
        facet("future", "Future", SemanticFacetCategory.SETTING, "futuristic"),
        facet("historical_setting", "Historical setting", SemanticFacetCategory.SETTING, "period setting", "period piece"),

        facet("unreliable_narrator", "Unreliable narrator", SemanticFacetCategory.PLOT_DEVICE),
        facet("twist_heavy", "Twist-heavy", SemanticFacetCategory.PLOT_DEVICE, "plot twists", "big twist"),
        facet("nonlinear", "Nonlinear", SemanticFacetCategory.PLOT_DEVICE, "non-linear"),
        facet("mystery_box", "Mystery-box", SemanticFacetCategory.PLOT_DEVICE, "mystery box"),
        facet("locked_room", "Locked-room mystery", SemanticFacetCategory.PLOT_DEVICE, "locked room"),

        facet("arthouse", "Arthouse", SemanticFacetCategory.STYLE, "art house", "auteur"),
        facet("stylish", "Stylish", SemanticFacetCategory.STYLE, "stylized"),
        facet("visual_storytelling", "Visual storytelling", SemanticFacetCategory.STYLE, "visually stunning", "beautiful cinematography"),
        facet("practical_effects", "Practical effects", SemanticFacetCategory.STYLE),
        facet("dialogue_driven", "Dialogue-driven", SemanticFacetCategory.STYLE, "dialogue heavy"),
        facet("minimalist", "Minimalist", SemanticFacetCategory.STYLE, "minimalistic"),
        facet("surreal", "Surreal", SemanticFacetCategory.STYLE, "surrealist"),
        facet("dreamlike", "Dreamlike", SemanticFacetCategory.STYLE, "dream-like"),

        facet("ensemble", "Ensemble", SemanticFacetCategory.NARRATIVE, "ensemble cast"),
        facet("character_driven", "Character-driven", SemanticFacetCategory.NARRATIVE, "character study"),
        facet("plot_driven", "Plot-driven", SemanticFacetCategory.NARRATIVE),
        facet("female_led", "Female-led", SemanticFacetCategory.NARRATIVE, "woman protagonist", "women protagonists", "female protagonist"),
        facet("morally_grey", "Morally grey", SemanticFacetCategory.NARRATIVE, "morally gray", "grey morality", "gray morality"),
        facet("competent_lead", "Competent protagonist", SemanticFacetCategory.NARRATIVE, "competent lead", "competence porn"),
        facet("antihero", "Antihero", SemanticFacetCategory.NARRATIVE, "anti-hero"),

        facet("date_night", "Date night", SemanticFacetCategory.AUDIENCE),
        facet("friends", "With friends", SemanticFacetCategory.AUDIENCE, "group watch"),
        facet("family_watch", "Family watch", SemanticFacetCategory.AUDIENCE, "family night"),

        facet("low_gore", "Low gore", SemanticFacetCategory.CONTENT_INTENSITY, "not gory", "little gore"),
        facet("graphic_violence", "Graphic violence", SemanticFacetCategory.CONTENT_INTENSITY, "graphic", "very violent"),
        facet("jump_scares", "Jump scares", SemanticFacetCategory.CONTENT_INTENSITY, "jumpscares"),
        facet("low_action", "Low action", SemanticFacetCategory.CONTENT_INTENSITY, "little action", "not much action"),
        facet("no_romance", "No romance", SemanticFacetCategory.CONTENT_INTENSITY, "without romance"),
        facet("family_safe", "Family-safe", SemanticFacetCategory.CONTENT_INTENSITY, "kid friendly", "child friendly"),
    )

    val facets: List<SemanticFacet> = entries.map(Entry::facet)

    fun byId(id: String): SemanticFacet? =
        entries.firstOrNull { it.facet.id == id }?.facet

    fun match(text: String): List<SemanticFacetMatch> {
        val normalized = normalize(text)
        return entries.flatMap { entry ->
            entry.aliases.mapNotNull { alias ->
                val regex = Regex("""(?<![a-z0-9])${Regex.escape(alias)}(?![a-z0-9])""")
                val found = regex.find(normalized) ?: return@mapNotNull null
                val prefix = normalized
                    .substring(maxOf(0, found.range.first - 42), found.range.first)
                SemanticFacetMatch(
                    facet = entry.facet,
                    alias = alias,
                    start = found.range.first,
                    end = found.range.last + 1,
                    negated = NEGATION_PATTERN.containsMatchIn(prefix),
                )
            }
        }
            .distinctBy { it.facet.id }
            .sortedBy(SemanticFacetMatch::start)
    }

    fun detect(text: String): List<SemanticFacet> =
        match(text).filterNot(SemanticFacetMatch::negated).map(SemanticFacetMatch::facet)

    private fun normalize(value: String): String = value
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9+%.' -]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val NEGATION_PATTERN = Regex(
        """\b(?:no|not|without|avoid|exclude|anything but|less|lighter on)\s+(?:\w+\s+){0,3}$""",
    )
}
