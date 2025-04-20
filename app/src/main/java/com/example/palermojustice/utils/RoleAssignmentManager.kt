package com.example.palermojustice.utils

import com.example.palermojustice.model.Role

/**
 * Utility class that assign Roles.
 */
object RoleAssignmentManager {

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
}