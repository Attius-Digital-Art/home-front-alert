package com.attius.homefrontalert

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks

enum class LocationTrackingMode {
    GPS_LIVE,
    FIXED_ZONE
}

data class ResolvedLocation(
    val lat: Double,
    val lng: Double,
    val zoneNameHe: String,
    val isFallback: Boolean,
    val source: String // "GPS", "SAVED", "DEFAULT"
)

class AppLocationManager(private val context: Context) {

    private val sharedPreferences = context.getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val distanceCalculator = ZoneDistanceCalculator(context)

    // The Israel Museum, Jerusalem (Hardcoded Default)
    companion object {
        const val DEFAULT_LAT = 31.7719
        const val DEFAULT_LNG = 35.2017
        const val DEFAULT_ZONE_HE = "מוזיאון ישראל, ירושלים"
    }

    fun getTrackingMode(): LocationTrackingMode {
        val modeStr = sharedPreferences.getString("tracking_mode", LocationTrackingMode.GPS_LIVE.name)
        return try { LocationTrackingMode.valueOf(modeStr!!) } catch (e: Exception) { LocationTrackingMode.GPS_LIVE }
    }

    fun setTrackingMode(mode: LocationTrackingMode) {
        sharedPreferences.edit().putString("tracking_mode", mode.name).apply()
    }

    /**
     * Legacy support for the boolean switch used in older versions
     */
    fun isUsingLiveGps(): Boolean {
        return getTrackingMode() == LocationTrackingMode.GPS_LIVE
    }

    fun setUsingLiveGps(use: Boolean) {
        setTrackingMode(if (use) LocationTrackingMode.GPS_LIVE else LocationTrackingMode.FIXED_ZONE)
    }

    fun getSavedZoneHe(): String? {
        return sharedPreferences.getString("fixed_zone_he", null)
    }

    fun setSavedZoneHe(zoneHe: String) {
        val loc = distanceCalculator.getZoneCoordinates(zoneHe)
        if (loc != null) {
            sharedPreferences.edit()
                .putString("fixed_zone_he", zoneHe)
                .putString("manual_lat", loc.lat.toString())
                .putString("manual_lng", loc.lng.toString())
                .apply()
        }
    }

    private var cachedLocation: ResolvedLocation? = null

    /**
     * Resolves the location based on current settings and environmental availability.
     */
    @SuppressLint("MissingPermission")
    fun resolveCurrentLocation(): ResolvedLocation {
        val mode = getTrackingMode()
        val savedZoneHe = getSavedZoneHe()
        
        // 1. If GPS Mode is active, prioritize real-time lock
        if (mode == LocationTrackingMode.GPS_LIVE) {
            try {
                // First, try last known location (instant)
                val lastLocTask = fusedLocationClient.lastLocation
                val lastLoc: Location? = Tasks.await(lastLocTask, 1, java.util.concurrent.TimeUnit.SECONDS)
                if (lastLoc != null && (System.currentTimeMillis() - lastLoc.time) < 300000) { // 5 mins fresh
                    val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(lastLoc.latitude, lastLoc.longitude)
                    if (zoneInfo != null && zoneInfo.second < 150.0) {
                        val res = ResolvedLocation(lastLoc.latitude, lastLoc.longitude, zoneInfo.first, false, "GPS")
                        cachedLocation = res
                        return res
                    }
                }

                // If not fresh or null, try a fresh high-accuracy hit
                val locationTask = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                val location: Location? = Tasks.await(locationTask, 4, java.util.concurrent.TimeUnit.SECONDS)
                
                if (location != null) {
                    val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(location.latitude, location.longitude)
                    if (zoneInfo != null && zoneInfo.second < 150.0) {
                        val res = ResolvedLocation(location.latitude, location.longitude, zoneInfo.first, false, "GPS")
                        cachedLocation = res
                        return res
                    }
                }
            } catch (e: Exception) {
                // If on main thread or timeout, we fall back
            }
        }

        // 2. Fallback to Saved Zone if Mode is FIXED or GPS failed
        if (savedZoneHe != null) {
            val loc = distanceCalculator.getZoneCoordinates(savedZoneHe)
            if (loc != null) {
                val res = ResolvedLocation(loc.lat, loc.lng, savedZoneHe, mode == LocationTrackingMode.GPS_LIVE, "SAVED")
                cachedLocation = res
                return res
            }
        }

        // 3. Memory Cache Fallback (Avoid jumping back to Jerusalem if we had a lock recently)
        cachedLocation?.let { 
            if (mode == LocationTrackingMode.GPS_LIVE) return it.copy(isFallback = true)
        }

        // 4. Absolute Fallback: Jerusalem Museum
        return ResolvedLocation(DEFAULT_LAT, DEFAULT_LNG, DEFAULT_ZONE_HE, true, "DEFAULT")
    }

    /**
     * Compatibility wrapper for existing background service logic
     */
    fun getCurrentLocationSync(): Pair<Double, Double> {
        val res = resolveCurrentLocation()
        return Pair(res.lat, res.lng)
    }
    
    // Legacy support for UI
    fun getManualLat(): String? = sharedPreferences.getString("manual_lat", null)
    fun getManualLng(): String? = sharedPreferences.getString("manual_lng", null)
}
