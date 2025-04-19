package com.example.palermojustice.utils

import com.example.palermojustice.model.Role
import kotlin.random.Random

/**
 * Utility class that handles game rules enforcement and logic.
 */
object GameRules {

    /**
     * Assigns roles to players based on the number of players.
     * Ensures proper distribution of roles according to game rules.
     *
     * @param playerIds List of player IDs to assign roles to
     * @return Map of player ID to assigned Role
     */
    fun assignRoles(playerIds: List<String>): Map<String, Role> {
        val playerCount = playerIds.size

        // Get role distribution for this player count
        val distribution = Constants.ROLE_DISTRIBUTION[playerCount] ?:
        Constants.ROLE_DISTRIBUTION[Constants.MIN_PLAYERS]!!

        // Create list of roles based on distribution
        val roles = mutableListOf<Role>()

        // Add Mafiosi
        repeat(distribution.mafiosi) {
            roles.add(Role.MAFIOSO)
        }

        // Add Paesani (Citizens)
        repeat(distribution.paesani) {
            roles.add(Role.PAESANO)
        }

        // Add Ispettori (Detectives)
        repeat(distribution.ispettori) {
            roles.add(Role.ISPETTORE)
        }

        // Add Sgarristi (Protectors)
        repeat(distribution.sgarristi) {
            roles.add(Role.SGARRISTA)
        }

        // Add Il Prete (Priest)
        repeat(distribution.preti) {
            roles.add(Role.IL_PRETE)
        }

        // Shuffle roles
        roles.shuffle()

        // Assign roles to players
        val assignments = mutableMapOf<String, Role>()
        playerIds.forEachIndexed { index, playerId ->
            assignments[playerId] = if (index < roles.size) roles[index] else Role.PAESANO
        }

        return assignments
    }

    /**
     * Checks if a voting phase has reached consensus.
     *
     * @param votes Map of player IDs to their vote targets
     * @param alivePlayers Number of alive players
     * @return Pair of (consensus reached, target player ID if consensus)
     */
    fun checkVotingConsensus(votes: Map<String, String>, alivePlayers: Int): Pair<Boolean, String?> {
        // If no votes or not all alive players voted, no consensus
        if (votes.isEmpty() || votes.size < alivePlayers) {
            return Pair(false, null)
        }

        // Count votes for each target
        val voteCounts = mutableMapOf<String, Int>()
        votes.values.forEach { targetId ->
            voteCounts[targetId] = (voteCounts[targetId] ?: 0) + 1
        }

        // Find the player with the most votes
        var maxVotes = 0
        var targetId: String? = null
        var tie = false

        voteCounts.forEach { (id, count) ->
            when {
                count > maxVotes -> {
                    maxVotes = count
                    targetId = id
                    tie = false
                }
                count == maxVotes -> {
                    tie = true
                }
            }
        }

        // If there's a tie, no consensus
        if (tie) {
            return Pair(false, null)
        }

        // Check if votes for the target are a majority
        val majority = alivePlayers / 2 + 1
        if (maxVotes >= majority) {
            return Pair(true, targetId)
        }

        return Pair(false, null)
    }

    /**
     * Determines if a specific team has won based on alive players.
     *
     * @param players Map of player IDs to Player objects
     * @return The winning team, or null if the game continues
     */
    fun determineWinningTeam(players: Map<String, com.example.palermojustice.model.Player>): com.example.palermojustice.model.Team? {
        val alivePlayers = players.values.filter { it.isAlive }

        // Count alive players by role
        val aliveMafia = alivePlayers.count {
            it.role == com.example.palermojustice.model.Role.MAFIOSO.name
        }
        val aliveCitizens = alivePlayers.count {
            it.role != com.example.palermojustice.model.Role.MAFIOSO.name
        }

        return when {
            aliveMafia == 0 -> com.example.palermojustice.model.Team.CITIZENS // All mafia eliminated
            aliveMafia >= aliveCitizens -> com.example.palermojustice.model.Team.MAFIA // Mafia outnumbers or equals citizens
            else -> null // Game continues
        }
    }

    /**
     * Process night actions and determine the results.
     *
     * @param actions List of night actions performed by players
     * @param players Map of player IDs to Player objects
     * @return GameResult with outcome of night actions
     */
    fun processNightActions(
        actions: List<com.example.palermojustice.model.RoleAction>,
        players: Map<String, com.example.palermojustice.model.Player>,
        phaseNumber: Int
    ): com.example.palermojustice.model.GameResult {
        // Group actions by type
        val protectActions = actions.filter {
            it.actionType == com.example.palermojustice.model.ActionType.PROTECT
        }
        val blessActions = actions.filter {
            it.actionType == com.example.palermojustice.model.ActionType.BLESS
        }
        val killActions = actions.filter {
            it.actionType == com.example.palermojustice.model.ActionType.KILL
        }
        val investigateActions = actions.filter {
            it.actionType == com.example.palermojustice.model.ActionType.INVESTIGATE
        }

        // Get protected players
        val protectedPlayers = (protectActions + blessActions).map { it.targetPlayerId }.toSet()

        // Determine kill target (if multiple mafia, take most frequent target)
        val killTarget = killActions
            .groupBy { it.targetPlayerId }
            .maxByOrNull { (_, actions) -> actions.size }
            ?.key

        // Process kill if target isn't protected
        var eliminatedPlayerId: String? = null
        var eliminatedPlayerName: String? = null
        var eliminatedPlayerRole: String? = null

        if (killTarget != null && !protectedPlayers.contains(killTarget)) {
            eliminatedPlayerId = killTarget
            eliminatedPlayerName = players[killTarget]?.name
            eliminatedPlayerRole = players[killTarget]?.role
        }

        // Process investigation
        var investigationResult: Boolean? = null
        var investigatedPlayerId: String? = null

        if (investigateActions.isNotEmpty()) {
            val investigation = investigateActions.first()
            investigatedPlayerId = investigation.targetPlayerId

            // Check if target is Mafia
            investigationResult = players[investigatedPlayerId]?.role ==
                    com.example.palermojustice.model.Role.MAFIOSO.name
        }

        // Determine if game is over
        val updatedPlayers = players.toMutableMap()
        if (eliminatedPlayerId != null) {
            val player = updatedPlayers[eliminatedPlayerId]
            player?.let {
                updatedPlayers[eliminatedPlayerId] = it.copy(isAlive = false)
            }
        }

        val winningTeam = determineWinningTeam(updatedPlayers)

        return com.example.palermojustice.model.GameResult(
            phaseNumber = phaseNumber,
            state = com.example.palermojustice.model.GameState.NIGHT_RESULTS,
            eliminatedPlayerId = eliminatedPlayerId,
            eliminatedPlayerName = eliminatedPlayerName,
            eliminatedPlayerRole = eliminatedPlayerRole,
            investigationResult = investigationResult,
            investigatedPlayerId = investigatedPlayerId,
            protectedPlayerId = protectedPlayers.firstOrNull(),
            winningTeam = winningTeam
        )
    }
}