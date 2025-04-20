package com.example.palermojustice.controller

import android.util.Log
import com.example.palermojustice.firebase.FirebaseManager
import com.example.palermojustice.model.*
import com.example.palermojustice.utils.Constants
import com.example.palermojustice.utils.GameRules
import com.example.palermojustice.model.RoleAction
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Main controller for game logic.
 * Handles game state transitions and overall game flow.
 */
class GameController private constructor(private val gameId: String) {
    // Game state properties
    private var currentGame: Game? = null
    private var currentState = GameState.LOBBY
    private var currentPhaseNumber = 0
    private var roleController: RoleController? = null
    private var votingController: VotingController? = null
    private var phaseController: PhaseController? = null
    private var gameListeners = mutableListOf<ValueEventListener>()

    // Firebase references
    private val database = FirebaseDatabase.getInstance()
    private val gameRef = database.getReference("games").child(gameId)

    // Listener for game updates
    private var gameStateListener: ((GameState) -> Unit)? = null
    private var gameResultListener: ((GameResult) -> Unit)? = null

    companion object {
        @Volatile
        private var INSTANCE: GameController? = null

        /**
         * Get instance of the GameController (Singleton pattern)
         */
        fun getInstance(gameId: String): GameController {
            return INSTANCE ?: synchronized(this) {
                val instance = GameController(gameId)
                INSTANCE = instance
                instance
            }
        }

        /**
         * Clear instance when game is over
         */
        fun clearInstance() {
            INSTANCE = null
        }
    }

    init {
        // Initialize sub-controllers
        roleController = RoleController(gameId)
        votingController = VotingController(gameId)
        phaseController = PhaseController(gameId)

        // Start listening to game updates
        setupGameListener()
    }

    /**
     * Set up listener for state changes
     */
    fun setGameStateListener(listener: (GameState) -> Unit) {
        gameStateListener = listener
    }

    /**
     * Set up listener for game results
     */
    fun setGameResultListener(listener: (GameResult) -> Unit) {
        gameResultListener = listener
    }

    /**
     * Set up Firebase listener for the game
     */
    private fun setupGameListener() {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    Log.d("GameController", "Received game data update")

                    // Update current game object
                    currentGame = Game.fromSnapshot(gameId, snapshot)

                    // Get current state from database
                    val stateStr = snapshot.child("status").getValue(String::class.java) ?: "lobby"
                    Log.d("GameController", "Game status from Firebase: $stateStr")

                    currentState = when (stateStr) {
                        "lobby" -> GameState.LOBBY
                        "night" -> GameState.NIGHT
                        "night_results" -> GameState.NIGHT_RESULTS
                        "day" -> GameState.DAY_DISCUSSION
                        "voting" -> GameState.DAY_VOTING
                        "execution_results" -> GameState.EXECUTION_RESULT
                        "finished" -> GameState.GAME_OVER
                        else -> GameState.LOBBY
                    }

                    // Get current phase number
                    currentPhaseNumber = snapshot.child("currentPhase")
                        .getValue(Long::class.java)?.toInt() ?: 0

                    Log.d("GameController", "Current phase number: $currentPhaseNumber")

                    // Notify listener of state change
                    gameStateListener?.invoke(currentState)

                    // Check for phase results
                    val phaseResultsSnapshot = snapshot.child("phaseResults")
                        .child(currentPhaseNumber.toString())

                    if (phaseResultsSnapshot.exists()) {
                        Log.d("GameController", "Found phase results for phase $currentPhaseNumber")
                        val result = parseGameResult(phaseResultsSnapshot, currentState)
                        gameResultListener?.invoke(result)
                    } else {
                        Log.d("GameController", "No phase results found for phase $currentPhaseNumber")
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("GameController", "Firebase listener cancelled: ${error.toException().message}")
            }
        }

        gameRef.addValueEventListener(listener)
        gameListeners.add(listener)
    }

    /**
     * Parse game result from Firebase data
     */
    private fun parseGameResult(snapshot: DataSnapshot, state: GameState): GameResult {
        Log.d("GameController", "Parsing game result for state $state")

        val eliminatedPlayerId = snapshot.child("eliminatedPlayerId").getValue(String::class.java)
        val eliminatedPlayerName = snapshot.child("eliminatedPlayerName").getValue(String::class.java)
        val eliminatedPlayerRole = snapshot.child("eliminatedPlayerRole").getValue(String::class.java)
        val investigationResult = snapshot.child("investigationResult").getValue(Boolean::class.java)
        val investigatedPlayerId = snapshot.child("investigatedPlayerId").getValue(String::class.java)
        val protectedPlayerId = snapshot.child("protectedPlayerId").getValue(String::class.java)
        val nightSummary = snapshot.child("nightSummary").getValue(String::class.java) ?: ""
        val winningTeamStr = snapshot.child("winningTeam").getValue(String::class.java)

        val winningTeam = winningTeamStr?.let {
            try {
                Team.valueOf(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        // Log the results for debugging
        Log.d("GameController", "Parsed result: eliminated=$eliminatedPlayerName, " +
                "investigation=${investigationResult != null}, " +
                "investigatedPlayer=$investigatedPlayerId, winningTeam=$winningTeam")

        // If this is night results and investigation data exists, ensure it's properly logged
        if (state == GameState.NIGHT_RESULTS && investigationResult != null && investigatedPlayerId != null) {
            Log.d("GameController", "Investigation result found: target=$investigatedPlayerId, result=$investigationResult")
        }

        return GameResult(
            phaseNumber = currentPhaseNumber,
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
    }

    /**
     * Start the game (transition from LOBBY to ROLE_ASSIGNMENT)
     */
    fun startGame(onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (currentState != GameState.LOBBY) {
            onFailure(Exception("Game has already started"))
            return
        }

        // Load player
        gameRef.child("players").get()
            .addOnSuccessListener { snapshot ->
                val players = snapshot.children.mapNotNull { playerSnap ->
                    val id = playerSnap.key ?: return@mapNotNull null
                    id
                }

                Log.d("GameController", "Fetched ${players.size} players for start: $players")

                if (players.size < Constants.MIN_PLAYERS) {
                    onFailure(Exception("Not enough players. Minimum: ${Constants.MIN_PLAYERS}"))
                    return@addOnSuccessListener
                }

                // Roles
                val roleAssignments = GameRules.assignRoles(players)

                val updates = mutableMapOf<String, Any>()
                updates["status"] = "night"
                updates["currentPhase"] = 1

                // Make sure all players are set to alive when game starts
                roleAssignments.forEach { (playerId, role) ->
                    updates["players/$playerId/role"] = role.name
                    updates["players/$playerId/isAlive"] = true  // Explicitly set all players to alive
                }

                gameRef.updateChildren(updates)
                    .addOnSuccessListener {
                        currentState = GameState.NIGHT
                        currentPhaseNumber = 1
                        onSuccess()
                    }
                    .addOnFailureListener { onFailure(it) }
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Perform a role action (night phase)
     */
    fun performRoleAction(
        playerId: String,
        targetId: String,
        actionType: ActionType,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        Log.d("GameController", "Player $playerId performing $actionType action on target $targetId")

        // Validate action
        val player = currentGame?.players?.get(playerId)
        if (player == null) {
            Log.e("GameController", "Invalid player: $playerId")
            onFailure(Exception("Invalid player"))
            return
        }

        if (!player.isAlive) {
            Log.e("GameController", "Player $playerId is not alive and cannot perform actions")
            onFailure(Exception("Dead players cannot perform actions"))
            return
        }

        // Check if action is valid for player's role
        val playerRole = try {
            player.role?.let { Role.valueOf(it) }
        } catch (e: IllegalArgumentException) {
            null
        }

        if (playerRole == null) {
            Log.e("GameController", "Player $playerId has no valid role")
            onFailure(Exception("Invalid player role"))
            return
        }

        if (!actionType.getValidRoles().contains(playerRole)) {
            Log.e("GameController", "Action $actionType is not valid for role $playerRole")
            onFailure(Exception("This action is not valid for your role"))
            return
        }

        // Create role action
        val action = RoleAction(
            actionType = actionType,
            sourcePlayerId = playerId,
            targetPlayerId = targetId,
            phaseNumber = currentPhaseNumber
        )

        // Submit the action to the role controller
        Log.d("GameController", "Submitting action to role controller: $action")
        roleController?.submitAction(action, onSuccess, onFailure)
    }

    /**
     * Submit a vote during day phase
     */
    fun submitVote(
        voterId: String,
        targetId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        Log.d("GameController", "Player $voterId submitting vote for $targetId")

        // Check if it's voting phase
        if (currentState != GameState.DAY_VOTING) {
            Log.e("GameController", "Cannot vote - not in voting phase. Current state: $currentState")
            onFailure(Exception("It's not voting time"))
            return
        }

        // Check if voter is alive
        val voter = currentGame?.players?.get(voterId)
        if (voter == null) {
            Log.e("GameController", "Invalid voter: $voterId")
            onFailure(Exception("Invalid voter"))
            return
        }

        if (!voter.isAlive) {
            Log.e("GameController", "Dead player $voterId cannot vote")
            onFailure(Exception("You cannot vote"))
            return
        }

        // Submit vote
        Log.d("GameController", "Submitting vote to VotingController")
        votingController?.submitVote(voterId, targetId, currentPhaseNumber, onSuccess, onFailure)
    }

    /**
     * Advance to the next phase of the game
     */
    fun advancePhase(
        playerId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        // Only host can advance phase
        val currentUserId = playerId
        val hostId = currentGame?.hostId

        if (currentUserId != hostId) {
            Log.e("GameController", "Only host can advance phase. Current user: $currentUserId, Host: $hostId")
            onFailure(Exception("Only the host can advance the phase"))
            return
        }

        // Get next state
        val nextState = currentState.getNextState()

        // Log the state transition attempt
        Log.d("GameController", "Attempting to advance from $currentState to $nextState")

        // Special handling for each state transition
        when (nextState) {
            GameState.NIGHT -> {
                // Increment phase number when transitioning to night
                currentPhaseNumber++
                Log.d("GameController", "Advancing to night phase $currentPhaseNumber")

                phaseController?.beginNightPhase(currentPhaseNumber,
                    onSuccess = {
                        Log.d("GameController", "Successfully advanced to NIGHT phase $currentPhaseNumber")
                        currentState = nextState
                        onSuccess()
                    },
                    onFailure = { exception ->
                        Log.e("GameController", "Failed to advance to NIGHT phase: ${exception.message}")
                        onFailure(exception)
                    }
                )
            }
            GameState.NIGHT_RESULTS -> {
                Log.d("GameController", "Processing night actions for phase $currentPhaseNumber")

                phaseController?.processNightActions(currentPhaseNumber,
                    onSuccess = {
                        Log.d("GameController", "Successfully processed night actions")
                        currentState = nextState
                        onSuccess()
                    },
                    onFailure = { exception ->
                        Log.e("GameController", "Failed to process night actions: ${exception.message}")
                        onFailure(exception)
                    }
                )
            }
            GameState.DAY_DISCUSSION -> {
                Log.d("GameController", "Beginning day discussion phase")

                phaseController?.beginDayPhase(currentPhaseNumber,
                    onSuccess = {
                        Log.d("GameController", "Successfully advanced to DAY_DISCUSSION")
                        currentState = nextState
                        onSuccess()
                    },
                    onFailure = { exception ->
                        Log.e("GameController", "Failed to advance to DAY_DISCUSSION: ${exception.message}")
                        onFailure(exception)
                    }
                )
            }
            GameState.DAY_VOTING -> {
                Log.d("GameController", "Beginning voting phase")

                phaseController?.beginVotingPhase(currentPhaseNumber,
                    onSuccess = {
                        Log.d("GameController", "Successfully advanced to DAY_VOTING")
                        currentState = nextState
                        onSuccess()
                    },
                    onFailure = { exception ->
                        Log.e("GameController", "Failed to advance to DAY_VOTING: ${exception.message}")
                        onFailure(exception)
                    }
                )
            }
            GameState.EXECUTION_RESULT -> {
                Log.d("GameController", "Processing voting results")

                phaseController?.processVotingResults(currentPhaseNumber,
                    onSuccess = {
                        Log.d("GameController", "Successfully processed voting results")
                        currentState = nextState
                        onSuccess()
                    },
                    onFailure = { exception ->
                        Log.e("GameController", "Failed to process voting results: ${exception.message}")
                        onFailure(exception)
                    }
                )
            }
            else -> {
                // Simple state transition for other states
                Log.d("GameController", "Simple transition to $nextState")

                gameRef.child("status").setValue(getFirebaseStatusForState(nextState))
                    .addOnSuccessListener {
                        Log.d("GameController", "Advanced to state: $nextState")
                        currentState = nextState
                        onSuccess()
                    }
                    .addOnFailureListener { exception ->
                        Log.e("GameController", "Failed to advance state: ${exception.message}")
                        onFailure(exception)
                    }
            }
        }
    }

    /**
     * Check if the game is over (if either team has won)
     */
    fun checkGameOver(): Team? {
        val players = currentGame?.players?.values?.toList() ?: return null

        Log.d("GameController", "Checking if game is over with ${players.size} players")

        // Check if Mafia has won (equal or more than citizens)
        val aliveMafia = players.count {
            it.role == Role.MAFIOSO.name && it.isAlive
        }
        val aliveCitizens = players.count {
            it.role != Role.MAFIOSO.name && it.isAlive
        }

        Log.d("GameController", "Game status: $aliveMafia mafia alive, $aliveCitizens citizens alive")

        return when {
            aliveMafia == 0 -> Team.CITIZENS // All mafia dead, citizens win
            aliveMafia >= aliveCitizens -> Team.MAFIA // Mafia equals or outnumbers citizens
            else -> null // Game continues
        }
    }

    /**
     * End the game with specified winning team
     */
    fun endGame(
        winningTeam: Team,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        Log.d("GameController", "Ending game with winning team: $winningTeam")

        val updates = mutableMapOf<String, Any>()
        updates["status"] = "finished"
        updates["winningTeam"] = winningTeam.name

        gameRef.updateChildren(updates)
            .addOnSuccessListener {
                Log.d("GameController", "Game ended successfully")
                currentState = GameState.GAME_OVER
                onSuccess()
            }
            .addOnFailureListener { exception ->
                Log.e("GameController", "Failed to end game: ${exception.message}")
                onFailure(exception)
            }
    }

    /**
     * Map GameState to Firebase status string
     */
    private fun getFirebaseStatusForState(state: GameState): String {
        return when (state) {
            GameState.LOBBY -> "lobby"
            GameState.NIGHT, GameState.ROLE_ASSIGNMENT -> "night"
            GameState.NIGHT_RESULTS -> "night_results"
            GameState.DAY_DISCUSSION -> "day"
            GameState.DAY_VOTING -> "voting"
            GameState.EXECUTION_RESULT -> "execution_results"
            GameState.GAME_OVER -> "finished"
        }
    }

    /**
     * Clean up listeners when game is over
     */
    fun cleanup() {
        Log.d("GameController", "Cleaning up GameController resources")

        gameListeners.forEach { listener ->
            gameRef.removeEventListener(listener)
        }
        gameListeners.clear()

        // Clean up sub-controllers
        roleController = null
        votingController = null
        phaseController = null

        clearInstance()
    }
}