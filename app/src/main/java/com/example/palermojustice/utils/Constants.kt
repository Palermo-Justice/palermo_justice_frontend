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
        10 to RoleDistribution(mafiosi = 3, paesani = 4, ispettori = 1, sgarristi = 1, preti = 1),
        11 to RoleDistribution(mafiosi = 3, paesani = 5, ispettori = 1, sgarristi = 1, preti = 1),
        12 to RoleDistribution(mafiosi = 4, paesani = 5, ispettori = 1, sgarristi = 1, preti = 1)
    )

    // Phase timing (in milliseconds)
    // TODO: remove, because events are manually triggerd
    const val DAY_DISCUSSION_TIME = 120_000L // 2 minutes
    const val DAY_VOTING_TIME = 60_000L // 1 minute
    const val NIGHT_ACTION_TIME = 60_000L // 1 minute
    const val RESULT_DISPLAY_TIME = 10_000L // 10 seconds

    // Firebase paths
    const val GAMES_PATH = "games"
    const val PLAYERS_PATH = "players"
    const val ACTIONS_PATH = "actions"
    const val PHASE_RESULTS_PATH = "phaseResults"
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
) {
    /**
     * Get total player count for this distribution
     */
    fun getTotalPlayers(): Int {
        return mafiosi + paesani + ispettori + sgarristi + preti
    }
}