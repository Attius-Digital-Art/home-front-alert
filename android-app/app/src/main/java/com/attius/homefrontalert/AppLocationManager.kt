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
    val isFallback: Boolean, // True if we are using a substitute (old GPS, cached, or default) because real-time is missing
    val source: String,      // "GPS", "SAVED", "DEFAULT"
    val activeMode: LocationTrackingMode = LocationTrackingMode.FIXED_ZONE
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
        // Clear session cache when switching modes to prevent SSOT violations (e.g. old GPS showing in Fixed mode)
        cachedLocation = null
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

    fun setSavedZoneHe(zoneHe: String?) {
        val editor = sharedPreferences.edit()
        editor.putString("fixed_zone_he", zoneHe)
        
        zoneHe?.let {
            val loc = distanceCalculator.getZoneCoordinates(it)
            if (loc != null) {
                editor.putString("manual_lat", loc.lat.toString())
                editor.putString("manual_lng", loc.lng.toString())
                savePersistentLocation(loc.lat, loc.lng, it)
            }
        }
        editor.apply()
    }

    private var cachedLocation: ResolvedLocation? = null

    /**
     * Resolves the location based on current settings and environmental availability.
     */
    @SuppressLint("MissingPermission")
    fun resolveCurrentLocation(): ResolvedLocation {
        val mode = getTrackingMode()
        val savedZoneHe = getSavedZoneHe()
        
        // 1. Force SSOT for Fixed Zone Mode - Ignore any GPS/Mobile cache
        if (mode == LocationTrackingMode.FIXED_ZONE) {
            if (savedZoneHe != null) {
                val loc = distanceCalculator.getZoneCoordinates(savedZoneHe)
                if (loc != null) {
                    return ResolvedLocation(loc.lat, loc.lng, savedZoneHe, false, "SAVED", mode)
                }
            }
            return ResolvedLocation(DEFAULT_LAT, DEFAULT_LNG, DEFAULT_ZONE_HE, true, "DEFAULT", mode)
        }

        // 2. GPS Mode: Try for a real-time lock
        try {
            // A. Try last known location first (lightning fast fallback)
            val lastLocTask = fusedLocationClient.lastLocation
            val lastLoc: Location? = Tasks.await(lastLocTask, 1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            
            val ageMs = if (lastLoc != null) System.currentTimeMillis() - lastLoc.time else Long.MAX_VALUE
            // If we have a very fresh (2 min) location, consider it successfully "locked"
            if (lastLoc != null && ageMs < 120000) { 
                val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(lastLoc.latitude, lastLoc.longitude)
                if (zoneInfo != null && zoneInfo.second < 150.0) {
                    savePersistentLocation(lastLoc.latitude, lastLoc.longitude, zoneInfo.first)
                    val res = ResolvedLocation(lastLoc.latitude, lastLoc.longitude, zoneInfo.first, false, "GPS", mode)
                    cachedLocation = res
                    return res
                }
            }

            // B. Try a fresh high-accuracy hit (Satellites) - 4s timeout
            val locationTask = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            val location: Location? = Tasks.await(locationTask, 4, java.util.concurrent.TimeUnit.SECONDS)
            
            if (location != null) {
                val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(location.latitude, location.longitude)
                if (zoneInfo != null && zoneInfo.second < 150.0) {
                    savePersistentLocation(location.latitude, location.longitude, zoneInfo.first)
                    val res = ResolvedLocation(location.latitude, location.longitude, zoneInfo.first, false, "GPS", mode)
                    cachedLocation = res
                    return res
                }
            }

            // C. Indoor Fallback: Balanced Power (WiFi/Cell) if Satellites fail - 3s timeout
            val indoorTask = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            val indoorLoc: Location? = Tasks.await(indoorTask, 3, java.util.concurrent.TimeUnit.SECONDS)
            if (indoorLoc != null) {
                val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(indoorLoc.latitude, indoorLoc.longitude)
                if (zoneInfo != null && zoneInfo.second < 150.0) {
                    savePersistentLocation(indoorLoc.latitude, indoorLoc.longitude, zoneInfo.first)
                    val res = ResolvedLocation(indoorLoc.latitude, indoorLoc.longitude, zoneInfo.first, false, "GPS", mode)
                    cachedLocation = res
                    return res
                }
            }
        } catch (e: Exception) {
            // Timeout or permission issue
        }

        // 3. Fallback Chain for GPS Mode (when real-time is searching/failed)
        
        // 3a. Session Memory Cache - (Max 5 minutes old)
        cachedLocation?.let { 
            // If it's too old, we drop it to force a fresh search or deeper fallback
            // But we keep it if we have nothing else
            return it.copy(isFallback = true, activeMode = mode)
        }

        // 3b. Persistent Fallback - Last known from PREVIOUS sessions/locks
        val lastKnownLat = sharedPreferences.getString("last_known_lat", null)?.toDoubleOrNull()
        val lastKnownLng = sharedPreferences.getString("last_known_lng", null)?.toDoubleOrNull()
        val lastKnownZone = sharedPreferences.getString("last_known_zone_he", null)
        if (lastKnownLat != null && lastKnownLng != null && lastKnownZone != null) {
            return ResolvedLocation(lastKnownLat, lastKnownLng, lastKnownZone, true, "SAVED", mode)
        }

        // 3c. Manual Zone Fallback
        if (savedZoneHe != null) {
            val loc = distanceCalculator.getZoneCoordinates(savedZoneHe)
            if (loc != null) {
                return ResolvedLocation(loc.lat, loc.lng, savedZoneHe, true, "SAVED", mode)
            }
        }

        // 4. Absolute Fallback: Jerusalem Museum
        return ResolvedLocation(DEFAULT_LAT, DEFAULT_LNG, DEFAULT_ZONE_HE, true, "DEFAULT", mode)
    }

    private fun savePersistentLocation(lat: Double, lng: Double, zoneHe: String) {
        StatusManager.updateLocation(context, zoneHe, lat, lng)
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
