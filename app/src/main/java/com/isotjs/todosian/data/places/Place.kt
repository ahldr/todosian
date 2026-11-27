package com.isotjs.todosian.data.places

data class Place(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = DEFAULT_RADIUS_METERS,
) {
    fun toToken(): String {
        val base = "%.5f,%.5f".format(java.util.Locale.ROOT, latitude, longitude)
        return if (radiusMeters == DEFAULT_RADIUS_METERS) base else "$base/${radiusMeters.toInt()}"
    }

    companion object {
        const val DEFAULT_RADIUS_METERS = 150f

        private val coordinates = Regex("""(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)(?:/(\d{1,5}))?""")

        /** Resolves `lat,lon[/radius]` or an `@shortcut` name; null when invalid or unknown. */
        fun resolve(token: String, shortcuts: Map<String, Place>): Place? {
            if (token.startsWith("@")) return shortcuts[token.removePrefix("@")]
            val match = coordinates.matchEntire(token) ?: return null
            val lat = match.groupValues[1].toDouble()
            val lon = match.groupValues[2].toDouble()
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val radius = match.groupValues[3].toFloatOrNull()?.takeIf { it > 0f } ?: DEFAULT_RADIUS_METERS
            return Place(lat, lon, radius)
        }
    }
}
