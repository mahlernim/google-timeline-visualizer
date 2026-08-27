package dev.mahlernim.timelinevisualizer.ui

import androidx.lifecycle.ViewModel
import dev.mahlernim.timelinevisualizer.data.LocationFilterMode
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.DistanceUnitPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val camera: CameraSettings,
    val distanceUnit: DistanceUnitPreference,
    val locationFilter: LocationFilterMode,
    val simplifyRouteDetail: Boolean,
    val keepPastRoutesVisible: Boolean,
)

class SettingsViewModel(
    private val cameraPreferences: CameraSettingsPreferences,
    private val distanceUnitPreferences: DistanceUnitPreferences,
    private val locationFilterPreferences: LocationFilterPreferences,
    private val timelineDisplayPreferences: TimelineDisplayPreferences,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        SettingsState(
            camera = cameraPreferences.load(),
            distanceUnit = distanceUnitPreferences.load(),
            locationFilter = locationFilterPreferences.load(),
            simplifyRouteDetail = timelineDisplayPreferences.simplifyRouteDetail(),
            keepPastRoutesVisible = timelineDisplayPreferences.keepPastRoutesVisible(),
        ),
    )
    val state: StateFlow<SettingsState> = mutableState.asStateFlow()

    fun updateCamera(settings: CameraSettings) {
        cameraPreferences.save(settings)
        mutableState.value = mutableState.value.copy(camera = settings)
    }

    fun resetVideoDefaults() {
        mutableState.value = mutableState.value.copy(
            camera = cameraPreferences.reset(),
        )
    }

    fun updateDistanceUnit(preference: DistanceUnitPreference) {
        distanceUnitPreferences.save(preference)
        mutableState.value = mutableState.value.copy(distanceUnit = preference)
    }

    fun updateLocationFilter(mode: LocationFilterMode) {
        locationFilterPreferences.save(mode)
        mutableState.value = mutableState.value.copy(locationFilter = mode)
    }

    fun updateSimplifyRouteDetail(enabled: Boolean) {
        timelineDisplayPreferences.setSimplifyRouteDetail(enabled)
        mutableState.value = mutableState.value.copy(simplifyRouteDetail = enabled)
    }

    fun updateKeepPastRoutesVisible(enabled: Boolean) {
        timelineDisplayPreferences.setKeepPastRoutesVisible(enabled)
        mutableState.value = mutableState.value.copy(keepPastRoutesVisible = enabled)
    }
}
