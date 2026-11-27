package com.isotjs.todosian.data.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceTest {
    private val home = Place(1.0, 2.0, 300f)

    @Test
    fun resolves_coordinates_with_and_without_radius() {
        assertEquals(Place(52.52, 13.4, 150f), Place.resolve("52.52,13.4", emptyMap()))
        assertEquals(Place(-33.8, 151.2, 75f), Place.resolve("-33.8,151.2/75", emptyMap()))
    }

    @Test
    fun resolves_shortcuts_and_rejects_unknown_or_out_of_range() {
        assertEquals(home, Place.resolve("@home", mapOf("home" to home)))
        assertNull(Place.resolve("@work", mapOf("home" to home)))
        assertNull(Place.resolve("95.0,10.0", emptyMap()))
        assertNull(Place.resolve("Berlin", emptyMap()))
    }

    @Test
    fun token_round_trips() {
        assertEquals("1.00000,2.00000/300", home.toToken())
        assertEquals(home, Place.resolve(home.toToken(), emptyMap()))
    }
}
