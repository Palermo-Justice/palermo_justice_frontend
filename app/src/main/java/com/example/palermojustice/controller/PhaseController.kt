package com.example.palermojustice.controller

import android.util.Log
import com.example.palermojustice.model.*
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Controller that manages game phase transitions and processes phase results.
 * Coordinates night actions, voting, and result calculation.
 */
class PhaseController(private val gameId: String) {

    // Firebase references
    private val database = FirebaseDatabase.getInstance()
    private val gameRef = database.getReference("games").child(gameId)
    private val listeners = mutableListOf<ValueEventListener>()

    // Sub-controllers
    private val roleController = RoleController(gameId)
    private val votingController = VotingController(gameId)

    /**
     * Begin the night phase
     */
    fun beginNightPhase(
        phaseNumber: Int,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val updates = HashMap<String, Any>()
        updates["status"] = "night"
        updates["currentPhase"] = phaseNumber

        gameRef.updateChildren(updates)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { exception ->
                onFailure(exception)
            }
    }

    /**
     * Process all night actions and determine results
     */
    fun processNightActions(
        phaseNumber: Int,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        // Get all night actions using a safer approach
        val actionsRef = gameRef.child("actions").child("night").child(phaseNumber.toString())

        actionsRef.get().addOnSuccessListener { actionsSnapshot ->
            val actions = mutableListOf<RoleAction>()

            if (actionsSnapshot.exists()) {
                // Extract actions from snapshot
                for (playerSnapshot in actionsSnapshot.children) {
                    val playerId = playerSnapshot.key ?: continue

                    // Convert to map
                    val actionData = HashMap<String, Any>()
                    playerSnapshot.children.forEach { child ->
                        child.key?.let { key ->
                            child.value?.let { value ->
                                actionData[key] = value
                            }
                        }
                    }

                    // Add phase number which might be missing in the data
                    actionData["phaseNumber"] = phaseNumber

                    // Create RoleAction from data
                    val action = RoleAction.fromMap(playerId, actionData)
                    action?.let { actions.add(it) }
                }
            }

            // Get all players
            gameRef.child("players").get().addOnSuccessListener { playersSnapshot ->
                val players = mutableMapOf<String, Player>()

                playersSnapshot.children.forEach { playerSnapshot ->
                    val playerId = playerSnapshot.key ?: return@forEach
                    val name = playerSnapshot.child("name").getValue(String::class.java) ?: ""
                    val role = playerSnapshot.child("role").getValue(String::class.java)
                    // Default to true if isAlive is not explicitly set
                    val isAlive = playerSnapshot.child("isAlive").getValue(Boolean::class.java) ?: true

                    players[playerId] = Player(
                        id = playerId,
                        name = name,
                        role = role,
                        isAlive = isAlive
                    )
                }

                // Debug log player status
                players.forEach { (id, player) ->
                    Log.d("PhaseController", "Player: ${player.name}, Role: ${player.role}, Alive: ${player.isAlive}")
                }

                // Process night actions in order of priority
                val result = processNightActionsInOrder(actions, players)

                // Save results to database
                saveNightResults(phaseNumber, result, onSuccess, onFailure)
            }.addOnFailureListener { exception ->
                onFailure(exception)
            }
        }.addOnFailureListener { exception ->
            onFailure(exception)
        }
    }

    /**
     * Process night actions in priority order:
     * 1. Protection actions (Sgarrista, Il Prete)
     * 2. Kill actions (Mafia)
     * 3. Investigation actions (Ispettore)
     */
    private fun processNightActionsInOrder(
        actions: List<RoleAction>,
        players: Map<String, Player>
    ): GameResult {
        var eliminatedPlayerId: String? = null
        var eliminatedPlayerName: String? = null
        var eliminatedPlayerRole: String? = null
        var investigationResult: Boolean? = null
        var investigatedPlayerId: String? = null
        var protectedPlayerId: String? = null

        // Get protection targets
        val protectionActions = actions.filter {
            it.actionType == ActionType.PROTECT || it.actionType == ActionType.BLESS
        }

        // Multiple protections can happen, all protected players are safe
        val protectedPlayers = protectionActions.map { it.targetPlayerId }.toSet()

        if (protectedPlayers.isNotEmpty()) {
            protectedPlayerId = protectedPlayers.first() // Save one for results
        }

        // Get kill targets
        val killActions = actions.filter { it.actionType == ActionType.KILL }

        // If there are multiple kill actions (multiple mafia), take the most frequent target
        val killTargets = killActions.groupBy { it.targetPlayerId }
            .maxByOrNull { (_, actions) -> actions.size }
            ?.key

        // Process kill if target isn't protected
        if (killTargets != null && !protectedPlayers.contains(killTargets)) {
            eliminatedPlayerId = killTargets
            eliminatedPlayerName = players[killTargets]?.name
            eliminatedPlayerRole = players[killTargets]?.role

            // Update player status to dead
            gameRef.child("players").child(killTargets).child("isAlive")
                .setValue(false)
        }

        // Process investigations
        val investigationActions = actions.filter { it.actionType == ActionType.INVESTIGATE }
        if (investigationActions.isNotEmpty()) {
            val investigation = investigationActions.first() // Only one detective
            investigatedPlayerId = investigation.targetPlayerId

            // Determine if target is mafia
            val targetRole = players[investigation.targetPlayerId]?.role
            investigationResult = targetRole == Role.MAFIOSO.name
        }

        // Check if game is over (all mafia dead or mafia >= citizens)
        val alivePlayers = players.values.filter { it.isAlive }
        val aliveMafia = alivePlayers.count { it.role == Role.MAFIOSO.name }
        val aliveCitizens = alivePlayers.count { it.role != Role.MAFIOSO.name }

        val winningTeam = when {
            aliveMafia == 0 -> Team.CITIZENS
            aliveMafia >= aliveCitizens -> Team.MAFIA
            else -> null
        }

        // If game is over, update game status
        if (winningTeam != null) {
            gameRef.child("status").setValue("finished")
            gameRef.child("winningTeam").setValue(winningTeam.name)
        }

        return GameResult(
            phaseNumber = 0, // Will be set by caller
            state = GameState.NIGHT_RESULTS,
            eliminatedPlayerId = eliminatedPlayerId,
            eliminatedPlayerName = eliminatedPlayerName,
            eliminatedPlayerRole = eliminatedPlayerRole,
            investigationResult = investigationResult,
            investigatedPlayerId = investigatedPlayerId,
            protectedPlayerId = protectedPlayerId,
            winningTeam = winningTeam
        )
    }

    /**
     * Save night phase results to database
     */
    private fun saveNightResults(
        phaseNumber: Int,
        result: GameResult,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val resultWithPhase = result.copy(phaseNumber = phaseNumber)

        // Save result to database
        gameRef.child("phaseResults").child(phaseNumber.toString())
            .setValue(resultWithPhase.toMap())
            .addOnSuccessListener {
                // Update game status to day
                gameRef.child("status").setValue("day")
                    .addOnSuccessListener {
                        onSuccess()
                    }
                    .addOnFailureListener { exception ->
                        onFailure(exception)
                    }
            }
            .addOnFailureListener { exception ->
                onFailure(exception)
            }
    }

    /**
     * Begin the day discussion phase
     */
    fun beginDayPhase(
        phaseNumber: Int,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val updates = HashMap<String, Any>()
        updates["status"] = "day"

        gameRef.updateChildren(updates)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { exception ->
                onFailure(exception)
            }
    }

    /**
     * Begin the voting phase
     */
    fun beginVotingPhase(
        phaseNumber: Int,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val updates = HashMap<String, Any>()
        updates["status"] = "voting"

        gameRef.updateChildren(updates)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { exception ->
                onFailure(exception)
            }
    }

    /**
     * Process voting results and determine execution outcome
     */
    fun processVotingResults(
        phaseNumber: Int,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        Log.d("PhaseController", "Processing voting results for phase $phaseNumber")

        // Tally votes
        votingController.tallyVotes(phaseNumber) { voteCounts, executedId ->
            Log.d("PhaseController", "Vote tally results: votes=$voteCounts, executedId=$executedId")

            // If no one was executed (tie or no votes), just save empty result
            if (executedId == null) {
                Log.d("PhaseController", "No player executed (tie or no votes)")
                val result = GameResult(
                    phaseNumber = phaseNumber,
                    state = GameState.EXECUTION_RESULT
                )

                saveExecutionResult(result, onSuccess, onFailure)
                return@tallyVotes
            }

            // Get executed player details
            gameRef.child("players").child(executedId).get()
                .addOnSuccessListener { playerSnapshot ->
                    val name = playerSnapshot.child("name").getValue(String::class.java) ?: ""
                    val role = playerSnapshot.child("role").getValue(String::class.java) ?: ""

                    Log.d("PhaseController", "Executing player: $name, role: $role")

                    // Mark player as dead
                    gameRef.child("players").child(executedId).child("isAlive")
                        .setValue(false)
                        .addOnSuccessListener {
                            Log.d("PhaseController", "Player marked as dead, checking game over condition")

                            // Check if game is over
                            checkGameOver { winningTeam ->
                                // Create result object
                                val result = GameResult(
                                    phaseNumber = phaseNumber,
                                    state = GameState.EXECUTION_RESULT,
                                    eliminatedPlayerId = executedId,
                                    eliminatedPlayerName = name,
                                    eliminatedPlayerRole = role,
                                    winningTeam = winningTeam
                                )

                                Log.d("PhaseController", "Game result: $result")

                                saveExecutionResult(result, onSuccess, onFailure)
                            }
                        }
                        .addOnFailureListener { exception ->
                            Log.e("PhaseController", "Failed to mark player as dead: ${exception.message}")
                            onFailure(exception)
                        }
                }
                .addOnFailureListener { exception ->
                    Log.e("PhaseController", "Failed to get executed player data: ${exception.message}")
                    onFailure(exception)
                }
        }
    }


    /**
     * Check if the game is over after execution
     */
    private fun checkGameOver(callback: (Team?) -> Unit) {
        gameRef.child("players").get().addOnSuccessListener { playersSnapshot ->
            var aliveMafia = 0
            var aliveCitizens = 0

            playersSnapshot.children.forEach { playerSnapshot ->
                val isAlive = playerSnapshot.child("isAlive").getValue(Boolean::class.java) ?: true
                if (isAlive) {
                    val role = playerSnapshot.child("role").getValue(String::class.java)
                    if (role == Role.MAFIOSO.name) {
                        aliveMafia++
                    } else {
                        aliveCitizens++
                    }
                }
            }

            val winningTeam = when {
                aliveMafia == 0 -> Team.CITIZENS
                aliveMafia >= aliveCitizens -> Team.MAFIA
                else -> null
            }

            // If game is over, update game status
            if (winningTeam != null) {
                gameRef.child("status").setValue("finished")
                gameRef.child("winningTeam").setValue(winningTeam.name)
            }

            callback(winningTeam)
        }.addOnFailureListener {
            callback(null)
        }
    }

    /**
     * Save execution result to database
     */
    private fun saveExecutionResult(
        result: GameResult,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        Log.d("PhaseController", "Saving execution result for phase ${result.phaseNumber}")

        // First, save the result to the phase results
        gameRef.child("phaseResults").child(result.phaseNumber.toString())
            .setValue(result.toMap())
            .addOnSuccessListener {
                Log.d("PhaseController", "Phase result saved successfully")

                // If game is over, update game status
                if (result.winningTeam != null) {
                    gameRef.child("status").setValue("finished")
                        .addOnSuccessListener {
                            Log.d("PhaseController", "Game marked as finished with winning team: ${result.winningTeam}")
                            onSuccess()
                        }
                        .addOnFailureListener { exception ->
                            Log.e("PhaseController", "Failed to update game status to finished: ${exception.message}")
                            onFailure(exception)
                        }
                } else {
                    // Game continues, transition back to day phase
                    Log.d("PhaseController", "Execution result saved, ready for next phase")
                    onSuccess()
                }
            }
            .addOnFailureListener { exception ->
                Log.e("PhaseController", "Failed to save execution result: ${exception.message}")
                onFailure(exception)
            }
    }

    /**
     * Clean up listeners when game is over
     */
    fun cleanup() {
        listeners.forEach { listener ->
            gameRef.removeEventListener(listener)
        }
        listeners.clear()
    }
}