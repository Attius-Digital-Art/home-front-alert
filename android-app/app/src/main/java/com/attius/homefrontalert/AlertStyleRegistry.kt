package com.attius.homefrontalert

/**
 * Maps Home Front Command category IDs and titles to internal AlertType (URGENT, CAUTION, CALM).
 */
object AlertStyleRegistry {

    fun getStyle(catId: String, title: String): AlertType {
        // Known categories:
        // 1: Rocket Fire (Urgent)
        // 2: UAV Entry (Urgent)
        // 3: Earthquake (Urgent/Caution?)
        // 4: Tsunami (Urgent)
        // 5: Hazardous Materials (Urgent)
        // 10: Radiologic event (Urgent)
        // 13: Terrorist infiltration (Urgent)
        
        // This is a simplified mapper. 
        // In a real app, we might have a robust mapping.
        
        return when (catId) {
            "1", "2", "4", "5", "10", "13" -> AlertType.URGENT
            "3" -> AlertType.CAUTION // Earthquake might be a caution or urgent depending on severity
            "all_clear" -> AlertType.CALM
            else -> {
                // Heuristic based on title for unknown categories
                val lowerTitle = title.lowercase()
                if (lowerTitle.contains("ירי") || lowerTitle.contains("כלי טיס") || lowerTitle.contains("חדירה")) {
                    AlertType.URGENT
                } else if (lowerTitle.contains("רגיעה") || lowerTitle.contains("חזרה לשגרה")) {
                    AlertType.CALM
                } else {
                    AlertType.CAUTION
                }
            }
        }
    }
}
