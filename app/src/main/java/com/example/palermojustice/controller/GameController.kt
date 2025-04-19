package com.example.palermojustice.controller

import android.util.Log
import com.example.palermojustice.firebase.FirebaseManager
import com.example.palermojustice.model.*
import com.example.palermojustice.utils.Constants
import com.example.palermojustice.utils.GameRules
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
                    // Update current game object
                    currentGame = Game.fromSnapshot(gameId, snapshot)

                    // Get current state from database
                    val stateStr = snapshot.child("status").getValue(String::class.java) ?: "lobby"
                    currentState = when (stateStr) {
                        "lobby" -> GameState.LOBBY
                        "night" -> GameState.NIGHT
                        "day" -> GameState.DAY_DISCUSSION
                        "voting" -> GameState.DAY_VOTING
                        "finished" -> GameState.GAME_OVER
                        else -> GameState.LOBBY
                    }

                    // Get current phase number
                    currentPhaseNumber = snapshot.child("currentPhase")
                        .getValue(Long::class.java)?.toInt() ?: 0

                    // Notify listener of state change
                    gameStateListener?.invoke(currentState)

                    // Check for phase results
                    val phaseResultsSnapshot = snapshot.child("phaseResults")
                        .child(currentPhaseNumber.toString())

                    if (phaseResultsSnapshot.exists()) {
                        val result = parseGameResult(phaseResultsSnapshot, currentState)
                        gameResultListener?.invoke(result)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Handle error
            }
        }

        gameRef.addValueEventListener(listener)
        gameListeners.add(listener)
    }

    /**
     * Parse game result from Firebase data
     */
    private fun parseGameResult(snapshot: DataSnapshot, state: GameState): GameResult {
        val eliminatedPlayerId = snapshot.child("eliminatedPlayerId").getValue(String::class.java)
        val eliminatedPlayerName = snapshot.child("eliminatedPlayerName").getValue(String::class.java)
        val eliminatedPlayerRole = snapshot.child("eliminatedPlayerRole").getValue(String::class.java)
        val investigationResult = snapshot.child("investigationResult").getValue(Boolean::class.java)
        val investigatedPlayerId = snapshot.child("investigatedPlayerId").getValue(String::class.java)
        val protectedPlayerId = snapshot.child("protectedPlayerId").getValue(String::class.java)
        val winningTeamStr = snapshot.child("winningTeam").getValue(String::class.java)

        val winningTeam = winningTeamStr?.let {
            try {
                Team.valueOf(it)
            } catch (e: IllegalArgumentException) {
                null
            }
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
            winningTeam = winningTeam
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

                roleAssignments.forEach { (playerId, role) ->
                    updates["players/$playerId/role"] = role.name
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
        // Validate action
        val player = currentGame?.players?.get(playerId)
        if (player == null || !player.isAlive) {
            onFailure(Exception("Invalid player or player is not alive"))
            return
        }

        // Check if action is valid for player's role
        val playerRole = try {
            player.role?.let { Role.valueOf(it) }
        } catch (e: IllegalArgumentException) {
            null
        }

        if (playerRole == null || !actionType.getValidRoles().contains(playerRole)) {
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
        // Check if it's voting phase
        if (currentState != GameState.DAY_VOTING) {
            onFailure(Exception("It's not voting time"))
            return
        }

        // Check if voter is alive
        val voter = currentGame?.players?.get(voterId)
        if (voter == null || !voter.isAlive) {
            onFailure(Exception("You cannot vote"))
            return
        }

        // Submit vote
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
            onFailure(Exception("Only the host can advance the phase"))
            return
        }

        // Get next state
        val nextState = currentState.getNextState()

        // Special handling for each state transition
        when (nextState) {
            GameState.NIGHT -> {
                // Increment phase number when transitioning to night
                currentPhaseNumber++
                phaseController?.beginNightPhase(currentPhaseNumber, onSuccess, onFailure)
            }
            GameState.NIGHT_RESULTS -> {
                phaseController?.processNightActions(currentPhaseNumber, onSuccess, onFailure)
            }
            GameState.DAY_DISCUSSION -> {
                phaseController?.beginDayPhase(currentPhaseNumber, onSuccess, onFailure)
            }
            GameState.DAY_VOTING -> {
                phaseController?.beginVotingPhase(currentPhaseNumber, onSuccess, onFailure)
            }
            GameState.EXECUTION_RESULT -> {
                phaseController?.processVotingResults(currentPhaseNumber, onSuccess, onFailure)
            }
            else -> {
                // Simple state transition for other states
                gameRef.child("status").setValue(getFirebaseStatusForState(nextState))
                    .addOnSuccessListener {
                        currentState = nextState
                        onSuccess()
                    }
                    .addOnFailureListener { exception ->
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

        // Check if Mafia has won (equal or more than citizens)
        val aliveMafia = players.count {
            it.role == Role.MAFIOSO.name && it.isAlive
        }
        val aliveCitizens = players.count {
            it.role != Role.MAFIOSO.name && it.isAlive
        }

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
        val updates = mutableMapOf<String, Any>()
        updates["status"] = "finished"
        updates["winningTeam"] = winningTeam.name

        gameRef.updateChildren(updates)
            .addOnSuccessListener {
                currentState = GameState.GAME_OVER
                onSuccess()
            }
            .addOnFailureListener { exception ->
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
            GameState.DAY_DISCUSSION, GameState.NIGHT_RESULTS -> "day"
            GameState.DAY_VOTING -> "voting"
            GameState.EXECUTION_RESULT -> "day"
            GameState.GAME_OVER -> "finished"
        }
    }

    /**
     * Clean up listeners when game is over
     */
    fun cleanup() {
        gameListeners.forEach { listener ->
            gameRef.removeEventListener(listener)
        }
        gameListeners.clear()

        // Clean up sub-controllers
        roleController?.let {
            val method = it.javaClass.getMethod("cleanup")
            method.invoke(it)
        }

        votingController?.let {
            val method = it.javaClass.getMethod("cleanup")
            method.invoke(it)
        }

        phaseController?.let {
            val method = it.javaClass.getMethod("cleanup")
            method.invoke(it)
        }

        clearInstance()
    }
}