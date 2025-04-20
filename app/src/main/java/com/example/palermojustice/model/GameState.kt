package com.example.palermojustice.model

/**
 * Represents the possible states of the game.
 * The game follows a state machine pattern, transitioning between these states.
 */
enum class GameState(val displayName: String) {
    LOBBY("Lobby"),
    ROLE_ASSIGNMENT("Role Assignment"),
    NIGHT("Night"),
    NIGHT_RESULTS("Night Results"),
    DAY_DISCUSSION("Day Discussion"),
    DAY_VOTING("Day Voting"),
    EXECUTION_RESULT("Execution Result"),
    GAME_OVER("Game Over");

    /**
     * Returns the next game state based on the current state
     */
    fun getNextState(): GameState {
        return when (this) {
            LOBBY -> ROLE_ASSIGNMENT
            ROLE_ASSIGNMENT -> NIGHT
            NIGHT -> NIGHT_RESULTS
            NIGHT_RESULTS -> DAY_DISCUSSION
            DAY_DISCUSSION -> DAY_VOTING
            DAY_VOTING -> EXECUTION_RESULT
            EXECUTION_RESULT -> {
                // After execution results, go to night phase unless game is over
                // Note: The actual game over logic will be in the controller
                NIGHT
            }
            GAME_OVER -> GAME_OVER // Game over is a terminal state
        }
    }

    /**
     * Returns whether this state requires player actions
     */
    fun requiresPlayerAction(playerRole: String?, isAlive: Boolean): Boolean {
        // Dead players can't take actions
        if (!isAlive) return false

        return when (this) {
            NIGHT -> {
                // At night, only specific roles can act
                when (playerRole) {
                    Role.MAFIOSO.name,
                    Role.ISPETTORE.name,
                    Role.SGARRISTA.name,
                    Role.IL_PRETE.name -> true
                    else -> false
                }
            }
            DAY_VOTING -> true // Everyone votes during day
            else -> false // Other phases don't require player action
        }
    }
}