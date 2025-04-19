package com.example.palermojustice.model

/**
 * Represents actions that can be performed by specific roles.
 * Each action has a type, source player, and target player.
 */
data class RoleAction(
    val actionType: ActionType,
    val sourcePlayerId: String,
    val targetPlayerId: String,
    val phaseNumber: Int
) {
    /**
     * Convert RoleAction to Map for Firebase storage
     */
    fun toMap(): Map<String, Any> {
        return mapOf(
            "actionType" to actionType.name,
            "sourcePlayerId" to sourcePlayerId,
            "targetPlayerId" to targetPlayerId,
            "timestamp" to System.currentTimeMillis()
        )
    }

    companion object {
        /**
         * Create a RoleAction from Firebase data
         */
        fun fromMap(sourcePlayerId: String, data: Map<String, Any>): RoleAction? {
            try {
                val actionTypeStr = data["actionType"] as? String ?: return null
                val targetPlayerId = data["targetPlayerId"] as? String ?: return null
                val phaseNumber = (data["phaseNumber"] as? Long)?.toInt() ?: 0

                val actionType = try {
                    ActionType.valueOf(actionTypeStr)
                } catch (e: IllegalArgumentException) {
                    return null
                }

                return RoleAction(
                    actionType = actionType,
                    sourcePlayerId = sourcePlayerId,
                    targetPlayerId = targetPlayerId,
                    phaseNumber = phaseNumber
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}

/**
 * Types of actions that can be performed by roles
 */
enum class ActionType {
    KILL, // Mafia night action
    INVESTIGATE, // Inspector night action
    PROTECT, // Protector night action
    BLESS, // Priest night action
    VOTE; // Day voting action

    /**
     * Returns the valid roles that can perform this action
     */
    fun getValidRoles(): List<Role> {
        return when (this) {
            KILL -> listOf(Role.MAFIOSO)
            INVESTIGATE -> listOf(Role.ISPETTORE)
            PROTECT -> listOf(Role.SGARRISTA)
            BLESS -> listOf(Role.IL_PRETE)
            VOTE -> Role.values().toList() // Everyone can vote
        }
    }
}