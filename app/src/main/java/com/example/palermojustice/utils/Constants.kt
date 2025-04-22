package com.example.palermojustice.utils

/**
 * Contains constant values used throughout the application.
 */
object Constants {
    // Minimum and maximum players
    const val MIN_PLAYERS = 4
    const val MAX_PLAYERS = 12

    // Role distribution based on player count
    val ROLE_DISTRIBUTION = mapOf(
        4 to RoleDistribution(mafiosi = 1, paesani = 2, ispettori = 1, sgarristi = 0, preti = 0),
        5 to RoleDistribution(mafiosi = 1, paesani = 3, ispettori = 1, sgarristi = 0, preti = 0),
        6 to RoleDistribution(mafiosi = 2, paesani = 3, ispettori = 1, sgarristi = 0, preti = 0),
        7 to RoleDistribution(mafiosi = 2, paesani = 3, ispettori = 1, sgarristi = 1, preti = 0),
        8 to RoleDistribution(mafiosi = 2, paesani = 4, ispettori = 1, sgarristi = 1, preti = 0),
        9 to RoleDistribution(mafiosi = 3, paesani = 4, ispettori = 1, sgarristi = 1, preti = 0),
        10 to RoleDistribution(mafiosi = 3, paesani = 4, ispettori = 1, sgarristi = 1, preti = 0),
        11 to RoleDistribution(mafiosi = 3, paesani = 5, ispettori = 1, sgarristi = 1, preti = 0),
        12 to RoleDistribution(mafiosi = 4, paesani = 5, ispettori = 1, sgarristi = 1, preti = 0)
    )

    // Phase timing (in milliseconds)
    const val RESULT_DISPLAY_TIME = 10_000L // 10 seconds
}

/**
 * Helper class to define role distribution for a specific player count
 */
data class RoleDistribution(
    val mafiosi: Int,
    val paesani: Int,
    val ispettori: Int,
    val sgarristi: Int,
    val preti: Int
)