package com.example.palermojustice.model

/**
 * Represents the results of a game phase or action.
 * This class encapsulates what happened during a phase and provides
 * information to display to players.
 */
data class GameResult(
    val phaseNumber: Int,
    val state: GameState,
    val eliminatedPlayerId: String? = null,
    val eliminatedPlayerName: String? = null,
    val eliminatedPlayerRole: String? = null,
    val investigationResult: Boolean? = null, // true if mafia, false if not
    val investigatedPlayerId: String? = null,
    val protectedPlayerId: String? = null,
    val winningTeam: Team? = null,
    val nightSummary: String = "" // Added to store summary text
) {
    /**
     * Convert to Map for Firebase storage
     */
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "phaseNumber" to phaseNumber,
            "state" to state.name,
            "eliminatedPlayerId" to eliminatedPlayerId,
            "eliminatedPlayerName" to eliminatedPlayerName,
            "eliminatedPlayerRole" to eliminatedPlayerRole,
            "investigationResult" to investigationResult,
            "investigatedPlayerId" to investigatedPlayerId,
            "protectedPlayerId" to protectedPlayerId,
            "winningTeam" to winningTeam?.name,
            "nightSummary" to nightSummary
        )
    }

    /**
     * Get a description of what happened in this phase for display to players
     */
    fun getPublicDescription(): String {
        return when (state) {
            GameState.NIGHT_RESULTS -> {
                if (nightSummary.isNotEmpty()) {
                    // Use the detailed night summary if available
                    nightSummary
                } else if (eliminatedPlayerId != null) {
                    "$eliminatedPlayerName was eliminated during the night. They were a $eliminatedPlayerRole."
                } else {
                    "No one was eliminated during the night."
                }
            }
            GameState.EXECUTION_RESULT -> {
                if (eliminatedPlayerId != null) {
                    "The town has voted to execute $eliminatedPlayerName. They were a $eliminatedPlayerRole."
                } else {
                    "The town couldn't reach a consensus. No one was executed today."
                }
            }
            GameState.GAME_OVER -> {
                if (winningTeam == Team.MAFIA) {
                    "Game Over! The Mafia has taken control of the town!"
                } else {
                    "Game Over! The Citizens have eliminated all Mafia members and saved the town!"
                }
            }
            else -> ""
        }
    }

    /**
     * Get private information for specific roles
     */
    fun getPrivateDescription(playerRole: String, playerId: String): String {
        // Ispettore gets investigation results
        if (playerRole == Role.ISPETTORE.name && investigationResult != null && investigatedPlayerId == playerId) {
            val result = if (investigationResult) "is part of the Mafia" else "is not part of the Mafia"
            return "Your investigation reveals that this player $result."
        }

        // No special info for other roles
        return ""
    }

    companion object {
        /**
         * Create GameResult from Firebase data
         */
        fun fromMap(data: Map<String, Any?>): GameResult? {
            try {
                val phaseNumber = (data["phaseNumber"] as? Long)?.toInt() ?: return null
                val stateStr = data["state"] as? String ?: return null
                val state = try {
                    GameState.valueOf(stateStr)
                } catch (e: IllegalArgumentException) {
                    return null
                }

                val eliminatedPlayerId = data["eliminatedPlayerId"] as? String
                val eliminatedPlayerName = data["eliminatedPlayerName"] as? String
                val eliminatedPlayerRole = data["eliminatedPlayerRole"] as? String
                val investigationResult = data["investigationResult"] as? Boolean
                val investigatedPlayerId = data["investigatedPlayerId"] as? String
                val protectedPlayerId = data["protectedPlayerId"] as? String
                val nightSummary = data["nightSummary"] as? String ?: ""

                val winningTeamStr = data["winningTeam"] as? String
                val winningTeam = winningTeamStr?.let {
                    try {
                        Team.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }

                return GameResult(
                    phaseNumber = phaseNumber,
                    state = state,
                    eliminatedPlayerId = eliminatedPlayerId,
                    eliminatedPlayerName = eliminatedPlayerName,
                    eliminatedPlayerRole = eliminatedPlayerRole,
                    investigationResult = investigationResult,
                    investigatedPlayerId = investigatedPlayerId,
                    protectedPlayerId = protectedPlayerId,
                    winningTeam = winningTeam,
                    nightSummary = nightSummary
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}