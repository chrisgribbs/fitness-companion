package com.fitcompanion.core

/**
 * Picks photos that show the walk itself (beaches, headlands, the track, lookouts) rather than
 * whatever happens to be geotagged nearby (birds, flowers, people, buildings).
 */
object TrailPhotoScoring {

    /** Anything that is mainly a creature, plant, person, vehicle, food or document. */
    private val reject = Regex(
        "\\b(birds?|aves|kookaburras?|dacelo|lorikeets?|parrots?|cockatoos?|ibis|pelicans?|gulls?|eagles?|osprey|owls?|" +
            "animals?|fauna|mammals?|koalas?|kangaroos?|wallab(y|ies)|possums?|insects?|butterfl(y|ies)|moths?|dragonfl(y|ies)|beetles?|" +
            "bees?|ants?|spiders?|arachnida|reptiles?|lizards?|skinks?|goannas?|dragons?|snakes?|frogs?|turtles?|crabs?|fish(es)?|" +
            "jellyfish|starfish|molluscs?|shells?|fungi|fungus|mushrooms?|flowers?|flora|plants?|orchids?|leaf|leaves|seeds?|" +
            "people|persons?|portraits?|selfie|man|woman|men|women|children|dogs?|cats?|horses?|" +
            "cars?|vehicles?|buses|bus|trucks?|motorcycles?|aircraft|signs?|signage|plaques?|menus?|food|logo|maps?|diagrams?|" +
            "interiors?|rooms?|hotels?|apartments?|shops?|restaurants?|cafes?)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Words that suggest the scenery of a coastal or bush walk. */
    private val good = listOf(
        "beach", "coast", "coastal", "headland", "bay", "point", "track", "trail", "walk", "walkway", "path", "pathway",
        "boardwalk", "lookout", "view", "cliff", "rocks", "ocean", "sea", "surf", "national park", "landscape", "shore",
        "dune", "creek", "river", "estuary", "inlet", "panorama", "sunrise", "sunset", "esplanade", "foreshore",
    )

    /**
     * Score a candidate: higher is better, null means don't use it.
     * [text] is the file title, description and categories joined together.
     */
    fun score(text: String, width: Int, height: Int, distanceMeters: Double): Int? {
        if (width <= 0 || height <= 0) return null
        if (width < 800) return null                       // too small to look good full-width
        if (height > width) return null                    // portrait shots are rarely scenery
        if (reject.containsMatchIn(text)) return null
        val t = text.lowercase()
        val hits = good.count { t.contains(it) }
        if (hits == 0) return null                         // nothing says it's the walk; better no photo than a random one
        val closeness = when {
            distanceMeters < 150 -> 3
            distanceMeters < 400 -> 2
            distanceMeters < 800 -> 1
            else -> 0
        }
        return hits * 2 + closeness + (if (width.toDouble() / height >= 1.4) 1 else 0)
    }
}
