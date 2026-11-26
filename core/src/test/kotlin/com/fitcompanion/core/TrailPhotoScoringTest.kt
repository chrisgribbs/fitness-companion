package com.fitcompanion.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailPhotoScoringTest {
    @Test fun rejectsWildlife() {
        assertNull(TrailPhotoScoring.score("File:Kookaburra Australia. (9312272960).jpg Birds of Queensland", 3000, 2000, 67.0))
        assertNull(TrailPhotoScoring.score("File:Koala at Noosa National Park.jpg", 3000, 2000, 50.0))
        assertNull(TrailPhotoScoring.score("File:Banksia flower, Noosa coastal track.jpg", 3000, 2000, 50.0))
    }

    @Test fun acceptsScenery() {
        assertNotNull(TrailPhotoScoring.score("File:Tea Tree Bay, Noosa National Park.jpg", 3000, 2000, 100.0))
        assertNotNull(TrailPhotoScoring.score("File:Dolphin Point lookout Noosa.jpg", 4000, 2250, 300.0))
    }

    @Test fun rejectsUnrelatedOrPortraitOrTiny() {
        assertNull(TrailPhotoScoring.score("File:IMG_1234.jpg", 3000, 2000, 20.0))
        assertNull(TrailPhotoScoring.score("File:Alexandria Bay.jpg", 2000, 3000, 20.0))
        assertNull(TrailPhotoScoring.score("File:Alexandria Bay.jpg", 640, 480, 20.0))
    }

    @Test fun prefersCloserAndMoreSpecific() {
        val near = TrailPhotoScoring.score("File:Hells Gates headland, Noosa coastal walk.jpg", 3000, 2000, 80.0)!!
        val far = TrailPhotoScoring.score("File:Hells Gates headland, Noosa coastal walk.jpg", 3000, 2000, 1200.0)!!
        assertTrue(near > far)
    }
}
