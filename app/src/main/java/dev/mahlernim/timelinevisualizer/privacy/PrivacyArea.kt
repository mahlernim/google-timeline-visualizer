package dev.mahlernim.timelinevisualizer.privacy

data class PrivacyArea(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Double,
) {
    init {
        require(id.isNotBlank()) { "Privacy area ID must not be blank" }
        require(name.isNotBlank()) { "Privacy area name must not be blank" }
        require(latitude.isFinite() && latitude in -85.0..85.0) { "Invalid privacy area latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid privacy area longitude" }
        require(radiusKm.isFinite() && radiusKm in MIN_RADIUS_KM..MAX_RADIUS_KM) {
            "Privacy area radius must be between $MIN_RADIUS_KM and $MAX_RADIUS_KM km"
        }
    }

    companion object {
        const val MIN_RADIUS_KM = 0.5
        const val MAX_RADIUS_KM = 50.0
        const val DEFAULT_RADIUS_KM = 3.0
    }
}
