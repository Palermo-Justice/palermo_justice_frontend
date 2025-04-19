package com.example.palermojustice.model

/**
 * Represents a game role with specific abilities and team affiliations.
 * Each role has unique actions that can be performed during different game phases.
 */
enum class Role(
    val displayName: String,
    val description: String,
    val team: Team,
    val canPerformNightAction: Boolean
) {
    MAFIOSO(
        displayName = "Mafioso",
        description = "Works with other Mafiosi to eliminate citizens each night",
        team = Team.MAFIA,
        canPerformNightAction = true
    ),
    PAESANO(
        displayName = "Paesano",
        description = "Regular citizen trying to survive and vote during the day",
        team = Team.CITIZENS,
        canPerformNightAction = false
    ),
    ISPETTORE(
        displayName = "Ispettore",
        description = "Can investigate one player each night to determine if they are Mafia",
        team = Team.CITIZENS,
        canPerformNightAction = true
    ),
    SGARRISTA(
        displayName = "Sgarrista",
        description = "Can protect one player each night from elimination",
        team = Team.CITIZENS,
        canPerformNightAction = true
    ),
    IL_PRETE(
        displayName = "Il Prete",
        description = "Can bless a player and protect them from elimination",
        team = Team.CITIZENS,
        canPerformNightAction = true
    );

    /**
     * Returns true if the role is allowed to perform actions during the night phase
     */
    fun canActAtNight(): Boolean {
        return canPerformNightAction
    }

    /**
     * Returns description of what this role does at night
     */
    fun getNightActionDescription(): String {
        return when (this) {
            MAFIOSO -> "Choose a player to eliminate"
            ISPETTORE -> "Choose a player to investigate"
            SGARRISTA -> "Choose a player to protect"
            IL_PRETE -> "Choose a player to bless"
            else -> ""
        }
    }
}

/**
 * Represents the teams in the game
 */
enum class Team {
    MAFIA,
    CITIZENS;

    /**
     * Returns true if this team has won the game based on the alive players
     */
    fun hasWon(players: List<Player>): Boolean {
        val aliveMafia = players.count { it.role == Role.MAFIOSO.name && it.isAlive }
        val aliveCitizens = players.count { it.role != Role.MAFIOSO.name && it.isAlive }

        return when (this) {
            MAFIA -> aliveMafia >= aliveCitizens
            CITIZENS -> aliveMafia == 0
        }
    }
}