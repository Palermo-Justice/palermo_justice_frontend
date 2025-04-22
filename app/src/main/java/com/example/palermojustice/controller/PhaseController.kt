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
        Log.d("PhaseController", "Beginning night phase $phaseNumber")

        val updates = HashMap<String, Any>()
        updates["status"] = "night"
        updates["currentPhase"] = phaseNumber

        gameRef.updateChildren(updates)
            .addOnSuccessListener {
                Log.d("PhaseController", "Successfully updated game to night phase $phaseNumber")
                onSuccess()
            }
            .addOnFailureListener { exception ->
                Log.e("PhaseController", "Failed to begin night phase: ${exception.message}")
                onFailure(exception)
            }
    }

    fun processNightActions(
        phaseNumber: Int,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        Log.d("PhaseController", "Processing night actions for phase $phaseNumber")

        // Get all night actions using a safer approach
        val actionsRef = gameRef.child("actions").child("night").child(phaseNumber.toString())

        actionsRef.get().addOnSuccessListener { actionsSnapshot ->
            val actions = mutableListOf<RoleAction>()

            if (actionsSnapshot.exists()) {
                Log.d("PhaseController", "Found night actions for phase $phaseNumber")

                // Extract actions from snapshot
                for (playerSnapshot in actionsSnapshot.children) {
                    val playerId = playerSnapshot.key ?: continue
                    Log.d("PhaseController", "Processing action from player $playerId")

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
                    if (action != null) {
                        actions.add(action)
                        Log.d("PhaseController", "Added action: ${action.actionType} from $playerId targeting ${action.targetPlayerId}")
                    } else {
                        Log.e("PhaseController", "Failed to create action from data: $actionData")
                    }
                }
            } else {
                Log.d("PhaseController", "No night actions found for phase $phaseNumber")
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

                // Process night actions in order of priority with night summary
                val result = processNightActionsInOrder(actions, players, phaseNumber)

                // Save results to database
                saveNightResults(phaseNumber, result, onSuccess, onFailure)
            }.addOnFailureListener { exception ->
                Log.e("PhaseController", "Failed to get players: ${exception.message}")
                onFailure(exception)
            }
        }.addOnFailureListener { exception ->
            Log.e("PhaseController", "Failed to get night actions: ${exception.message}")
            onFailure(exception)
        }
    }

    /**
     * Process all night actions and determine results
     */
    private fun processNightActionsInOrder(
        actions: List<RoleAction>,
        players: Map<String, Player>,
        phaseNumber: Int
    ): GameResult {
        var eliminatedPlayerId: String? = null
        var eliminatedPlayerName: String? = null
        var eliminatedPlayerRole: String? = null
        var investigationResult: Boolean? = null
        var investigatedPlayerId: String? = null
        var protectedPlayerId: String? = null
        var nightSummary = StringBuilder("Night Phase Results:\n\n")

        Log.d("PhaseController", "Processing ${actions.size} night actions in order of priority")

        // Get protection targets
        val protectionActions = actions.filter {
            it.actionType == ActionType.PROTECT || it.actionType == ActionType.BLESS
        }

        Log.d("PhaseController", "Found ${protectionActions.size} protection actions")

        // Multiple protections can happen, all protected players are safe
        val protectedPlayers = protectionActions.map { it.targetPlayerId }.toSet()

        if (protectedPlayers.isNotEmpty()) {
            protectedPlayerId = protectedPlayers.first() // Save one for results
            Log.d("PhaseController", "Protected players: $protectedPlayers")

            // Add protection details to summary
            if (protectedPlayers.size == 1) {
                val protectorAction = protectionActions.first()
                val protectorRole = players[protectorAction.sourcePlayerId]?.role
                val protectorName = players[protectorAction.sourcePlayerId]?.name ?: "Someone"
                val protectedName = players[protectedPlayerId]?.name ?: "a player"

                if (protectorRole == Role.SGARRISTA.name) {
                    nightSummary.append("$protectorName (Sgarrista) protected $protectedName during the night.\n")
                } else {
                    nightSummary.append("$protectorName (Il Prete) blessed $protectedName during the night.\n")
                }
            } else if (protectedPlayers.size > 1) {
                nightSummary.append("${protectedPlayers.size} players were protected during the night.\n")
            }

            nightSummary.append("\n")
        }

        // Get kill targets
        val killActions = actions.filter { it.actionType == ActionType.KILL }
        Log.d("PhaseController", "Found ${killActions.size} kill actions")

        // If there are multiple kill actions (multiple mafia), take the most frequent target
        val killTargets = killActions.groupBy { it.targetPlayerId }
            .maxByOrNull { (_, actions) -> actions.size }
            ?.key

        Log.d("PhaseController", "Kill target: $killTargets")

        // Process kill if target isn't protected
        if (killActions.isNotEmpty()) {
            if (killTargets != null) {
                val targetName = players[killTargets]?.name ?: "Unknown"

                if (!protectedPlayers.contains(killTargets)) {
                    eliminatedPlayerId = killTargets
                    eliminatedPlayerName = players[killTargets]?.name
                    eliminatedPlayerRole = players[killTargets]?.role

                    Log.d("PhaseController", "Player $eliminatedPlayerName ($eliminatedPlayerRole) will be eliminated")
                    nightSummary.append("The Mafia targeted and eliminated $eliminatedPlayerName during the night.\n")
                    if (eliminatedPlayerRole != null) {
                        val roleName = try {
                            Role.valueOf(eliminatedPlayerRole).displayName
                        } catch (e: IllegalArgumentException) {
                            eliminatedPlayerRole
                        }
                        nightSummary.append("$eliminatedPlayerName was a $roleName.\n")
                    }

                    // Update player status to dead
                    gameRef.child("players").child(killTargets).child("isAlive")
                        .setValue(false)
                } else {
                    Log.d("PhaseController", "Kill target was protected!")
                    nightSummary.append("The Mafia targeted $targetName, but they were protected and survived the night.\n")
                }
            } else {
                nightSummary.append("The Mafia tried to eliminate someone, but failed.\n")
            }
        } else {
            nightSummary.append("The Mafia did not target anyone during the night.\n")
        }

        nightSummary.append("\n")

        // Process investigations
        val investigationActions = actions.filter { it.actionType == ActionType.INVESTIGATE }
        Log.d("PhaseController", "Found ${investigationActions.size} investigation actions")

        if (investigationActions.isNotEmpty()) {
            val investigation = investigationActions.first() // Only one detective
            investigatedPlayerId = investigation.targetPlayerId

            // Determine if target is mafia
            val targetRole = players[investigation.targetPlayerId]?.role
            investigationResult = targetRole == Role.MAFIOSO.name

            val inspectorId = investigation.sourcePlayerId
            val inspectorName = players[inspectorId]?.name ?: "The Inspector"
            val targetName = players[investigatedPlayerId]?.name ?: "a suspect"

            nightSummary.append("$inspectorName (Ispettore) investigated $targetName during the night.\n")
            // Note: We don't reveal the result in the public summary

            Log.d("PhaseController", "Investigation result: Player ${investigation.targetPlayerId} is Mafia: $investigationResult")

            // Log additional details to ensure data is properly passed
            Log.d("PhaseController", "Inspector ID: $inspectorId, Target ID: $investigatedPlayerId")
        }

        // Check if game is over (all mafia dead or mafia >= citizens)
        val alivePlayers = players.values.filter { it.isAlive }
        val aliveMafia = alivePlayers.count { it.role == Role.MAFIOSO.name }
        val aliveCitizens = alivePlayers.count { it.role != Role.MAFIOSO.name }

        Log.d("PhaseController", "Game status: $aliveMafia mafia alive, $aliveCitizens citizens alive")

        val winningTeam = when {
            aliveMafia == 0 -> Team.CITIZENS
            aliveMafia >= aliveCitizens -> Team.MAFIA
            else -> null
        }

        // If game is over, add to summary
        if (winningTeam != null) {
            Log.d("PhaseController", "Game over! Winning team: $winningTeam")

            nightSummary.append("\nGAME OVER!\n")
            if (winningTeam == Team.MAFIA) {
                nightSummary.append("The Mafia has taken control of the town!\n")
            } else {
                nightSummary.append("The Citizens have eliminated all Mafia members and saved the town!\n")
            }

            gameRef.child("status").setValue("finished")
            gameRef.child("winningTeam").setValue(winningTeam.name)
        }

        return GameResult(
            phaseNumber = phaseNumber,
            state = GameState.NIGHT_RESULTS,
            eliminatedPlayerId = eliminatedPlayerId,
            eliminatedPlayerName = eliminatedPlayerName,
            eliminatedPlayerRole = eliminatedPlayerRole,
            investigationResult = investigationResult,
            investigatedPlayerId = investigatedPlayerId,
            protectedPlayerId = protectedPlayerId,
            winningTeam = winningTeam,
            nightSummary = nightSummary.toString()
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
                // Update game status to display night results
                gameRef.child("status").setValue("night_results")
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

            // If no one was executed (tie or no votes), just save empty result and check if game is over
            if (executedId == null) {
                Log.d("PhaseController", "No player executed (tie or no votes)")

                // Check if game is over - important to check even if no one was executed
                checkGameOver { winningTeam ->
                    val result = GameResult(
                        phaseNumber = phaseNumber,
                        state = if (winningTeam != null) GameState.GAME_OVER else GameState.EXECUTION_RESULT,
                        winningTeam = winningTeam
                    )

                    Log.d("PhaseController", "Game result after checking: winningTeam=$winningTeam")
                    saveExecutionResult(result, onSuccess, onFailure)
                }
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
                                // Create result object with appropriate state
                                val result = GameResult(
                                    phaseNumber = phaseNumber,
                                    state = if (winningTeam != null) GameState.GAME_OVER else GameState.EXECUTION_RESULT,
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
        Log.d("PhaseController", "Saving execution result for phase ${result.phaseNumber}, state=${result.state}")

        // First, save the result to the phase results
        gameRef.child("phaseResults").child(result.phaseNumber.toString())
            .setValue(result.toMap())
            .addOnSuccessListener {
                Log.d("PhaseController", "Phase result saved successfully")

                // If game is over, update game status before calling onSuccess
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
                    // Game continues, update status to night AND increment the phase number
                    val nextPhaseNumber = result.phaseNumber + 1

                    val updates = HashMap<String, Any>()
                    updates["status"] = "night"
                    updates["currentPhase"] = nextPhaseNumber

                    gameRef.updateChildren(updates)
                        .addOnSuccessListener {
                            Log.d("PhaseController", "Execution result saved, status updated to night, phase incremented to $nextPhaseNumber")
                            onSuccess()
                        }
                        .addOnFailureListener { exception ->
                            Log.e("PhaseController", "Failed to update status to night: ${exception.message}")
                            onFailure(exception)
                        }
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