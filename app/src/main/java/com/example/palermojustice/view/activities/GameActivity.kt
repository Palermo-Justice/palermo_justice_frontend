package com.example.palermojustice.view.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.palermojustice.R
import com.example.palermojustice.controller.GameController
import com.example.palermojustice.databinding.ActivityGameBinding
import com.example.palermojustice.firebase.FirebaseManager
import com.example.palermojustice.model.GameResult
import com.example.palermojustice.model.GameState
import com.example.palermojustice.model.Player
import com.example.palermojustice.model.Role
import com.example.palermojustice.utils.Constants
import com.example.palermojustice.utils.NotificationHelper
import com.example.palermojustice.view.fragments.DayPhaseFragment
import com.example.palermojustice.view.fragments.NightPhaseFragment
import com.example.palermojustice.view.fragments.ResultsFragment
import com.google.firebase.database.ValueEventListener

/**
 * Main activity for game play after lobby.
 * Manages displaying different game phases and transitions between them.
 */
class GameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameBinding
    private lateinit var gameController: GameController
    private lateinit var notificationHelper: NotificationHelper

    private var gameId: String = ""
    private var playerId: String = ""
    private var isHost: Boolean = false
    private var currentState: GameState = GameState.LOBBY
    private var currentPlayer: Player? = null
    private var gameListener: ValueEventListener? = null
    private var isGameOver: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get game information from intent
        gameId = intent.getStringExtra("GAME_ID") ?: ""
        playerId = intent.getStringExtra("PLAYER_ID") ?: ""
        isHost = intent.getBooleanExtra("IS_HOST", false)

        if (gameId.isEmpty() || playerId.isEmpty()) {
            Toast.makeText(this, "Error: Invalid game data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Initialize controllers and helpers
        gameController = GameController.getInstance(gameId)
        notificationHelper = NotificationHelper(this)

        // Set up UI
        setupUI()

        // Start listening for game updates
        listenToGameUpdates()

        // Set game state listener
        gameController.setGameStateListener { newState ->
            onGameStateChanged(newState)
        }

        // Set game result listener
        gameController.setGameResultListener { result ->
            onGameResultReceived(result)
        }
    }

    private fun setupUI() {
        // Set up host-specific UI
        binding.buttonAdvancePhase.visibility = if (isHost) View.VISIBLE else View.GONE

        // Set click listeners
        binding.buttonAdvancePhase.setOnClickListener {
            advanceGamePhase()
        }

        binding.buttonLeaveGame.setOnClickListener {
            confirmLeaveGame()
        }
    }

    private fun listenToGameUpdates() {
        gameListener = FirebaseManager.listenToGame(
            gameId = gameId,
            onUpdate = { game ->
                // Update current player with latest data from Firebase
                currentPlayer = game.players[playerId]

                // Debug log to check player status
                Log.d("GameActivity", "Player status: ${currentPlayer?.name}, alive: ${currentPlayer?.isAlive}, role: ${currentPlayer?.role}")

                // Check if player is still in the game
                if (currentPlayer == null) {
                    Toast.makeText(this, "You have been removed from the game", Toast.LENGTH_SHORT).show()
                    finish()
                    return@listenToGame
                }

                // Update host status
                isHost = (game.hostId == playerId)

                // Check if game is finished
                isGameOver = game.status == "finished"

                // Update button visibility
                updateButtonVisibility()

                // Update game status information
                updateGameStatusInfo(game.status)

                // Check if the player can act in the current state
                val canAct = currentState.requiresPlayerAction(
                    currentPlayer?.role,
                    currentPlayer?.isAlive ?: false
                )

                // If player can act and is alive, notify them
                if (canAct && currentPlayer?.isAlive == true) {
                    notificationHelper.notifyYourTurn(gameId, currentState)
                }
            },
            onError = { exception ->
                Toast.makeText(this, "Error: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun updateGameStatusInfo(status: String) {
        // Update game status text
        val statusText = when (status) {
            "lobby" -> "Waiting in Lobby"
            "night" -> "Night Phase"
            "night_results" -> "Night Results"
            "day" -> "Day Discussion"
            "voting" -> "Voting Phase"
            "execution_results" -> "Execution Results"
            "finished" -> "Game Over"
            else -> "Unknown"
        }

        binding.textViewGameStatus.text = "Status: $statusText"

        // Show role information if assigned
        currentPlayer?.role?.let { role ->
            binding.textViewPlayerRole.text = "Your Role: ${getRoleDisplayName(role)}"
            binding.textViewPlayerRole.visibility = View.VISIBLE
        } ?: run {
            binding.textViewPlayerRole.visibility = View.GONE
        }

        // Show alive/dead status
        val aliveStatus = if (currentPlayer?.isAlive == true) "Alive" else "Dead"
        binding.textViewPlayerStatus.text = "Status: $aliveStatus"
    }

    private fun getRoleDisplayName(roleString: String): String {
        return try {
            Role.valueOf(roleString).displayName
        } catch (e: IllegalArgumentException) {
            roleString
        }
    }


    /**
     * Handle game state changes
     */
    private fun onGameStateChanged(newState: GameState) {
        // Log the state change
        Log.d("GameActivity", "Game state changing from $currentState to $newState")

        // Only update if state has changed
        if (currentState == newState) {
            Log.d("GameActivity", "State unchanged, skipping update")
            return
        }

        currentState = newState

        // If game is over, set the flag
        if (newState == GameState.GAME_OVER) {
            isGameOver = true
        }

        // Notify about phase change
        if (isHost || currentPlayer?.isAlive == true) {
            notificationHelper.notifyPhaseChange(gameId, newState)
        }

        // Update UI based on new state
        when (newState) {
            GameState.NIGHT -> {
                Log.d("GameActivity", "Showing night phase UI")
                showNightPhase()
            }
            GameState.NIGHT_RESULTS -> {
                Log.d("GameActivity", "Showing night results phase UI")
                showResultsPhase(newState)

                // Disable the advance button temporarily for the host
                // to ensure players have time to see the results
                if (isHost) {
                    binding.buttonAdvancePhase.isEnabled = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        binding.buttonAdvancePhase.isEnabled = true
                        updateButtonText()
                    }, Constants.RESULT_DISPLAY_TIME) //  (10 seconds)
                }
            }
            GameState.DAY_DISCUSSION, GameState.DAY_VOTING -> {
                Log.d("GameActivity", "Showing day phase UI for state: $newState")
                showDayPhase(newState)
            }
            GameState.EXECUTION_RESULT -> {
                Log.d("GameActivity", "Showing execution results phase UI")
                showResultsPhase(newState)

                // Similar to night results, ensure players see execution results
                if (isHost) {
                    binding.buttonAdvancePhase.isEnabled = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        binding.buttonAdvancePhase.isEnabled = true
                        updateButtonText()
                    }, Constants.RESULT_DISPLAY_TIME)
                }
            }
            GameState.GAME_OVER -> {
                Log.d("GameActivity", "Game over, showing game over screen")
                showGameOverScreen()
            }
            else -> {
                Log.d("GameActivity", "Unhandled state: $newState")
            } // Other states handled elsewhere
        }

        // Always update button text when state changes
        updateButtonText()
        updateButtonVisibility()
    }

    private fun updateButtonText() {
        // Determine the button text based on current state
        val buttonText = when (currentState) {
            GameState.DAY_DISCUSSION -> "Start Voting"
            GameState.DAY_VOTING -> "End Voting"
            GameState.EXECUTION_RESULT -> "Begin Night"
            GameState.NIGHT -> "End Night"
            GameState.NIGHT_RESULTS -> "Begin Day Discussion"
            else -> "Next Phase"
        }

        binding.buttonAdvancePhase.text = buttonText
        Log.d("GameActivity", "Updated advance button text: $buttonText")
    }


    private fun updateButtonVisibility() {
        // Hide the button completely if game is over
        if (currentState == GameState.GAME_OVER || isGameOver) {
            Log.d("GameActivity", "Game is over, hiding advance button")
            binding.buttonAdvancePhase.visibility = View.GONE
        } else {
            // For all other states, show the button if the player is the host
            binding.buttonAdvancePhase.visibility = if (isHost) View.VISIBLE else View.GONE

            // Enable button except for result states where we have automatic timers
            if (isHost &&
                currentState != GameState.NIGHT_RESULTS &&
                currentState != GameState.EXECUTION_RESULT) {
                binding.buttonAdvancePhase.isEnabled = true
            }
        }

        // Always show the leave game button
        binding.buttonLeaveGame.visibility = View.VISIBLE

        Log.d("GameActivity", "Updated advance button: visible=${binding.buttonAdvancePhase.visibility == View.VISIBLE}")
    }

    /**
     * Receive game results and update UI
     */
    private fun onGameResultReceived(result: GameResult) {
        Log.d("GameActivity", "Received game result: $result")

        // Update UI based on results
        when (result.state) {
            GameState.NIGHT_RESULTS -> {
                if (result.eliminatedPlayerId != null) {
                    // Show elimination notification
                    notificationHelper.notifyPlayerEliminated(gameId, result.eliminatedPlayerName ?: "A player")
                }
            }
            GameState.EXECUTION_RESULT -> {
                if (result.eliminatedPlayerId != null) {
                    // Show elimination notification
                    notificationHelper.notifyPlayerEliminated(gameId, result.eliminatedPlayerName ?: "A player")
                }
            }
            GameState.GAME_OVER -> {
                // Show game over notification
                result.winningTeam?.let { team ->
                    notificationHelper.notifyGameOver(gameId, team.name)

                    // Ensure we show the game over screen
                    if (currentState != GameState.GAME_OVER) {
                        currentState = GameState.GAME_OVER
                        showGameOverScreen()
                    }

                    // Set the game over flag
                    isGameOver = true

                    // Hide the advance button
                    binding.buttonAdvancePhase.visibility = View.GONE
                }
            }
            else -> {}
        }

        // Update the results fragment if it's currently shown
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (currentFragment is ResultsFragment) {
            currentFragment.updateResults(result)
        }
    }

    private fun showNightPhase() {
        // Check if player can act during night
        val canActAtNight = currentPlayer?.let {
            it.isAlive && it.role?.let { role ->
                try {
                    Role.valueOf(role).canActAtNight()
                } catch (e: IllegalArgumentException) {
                    false
                }
            } ?: false
        } ?: false

        // Show night phase fragment
        val fragment = NightPhaseFragment.newInstance(
            gameId = gameId,
            playerId = playerId,
            canAct = canActAtNight,
            role = currentPlayer?.role
        )

        replaceFragment(fragment)
    }

    private fun showDayPhase(state: GameState) {
        // Show day phase fragment
        val fragment = DayPhaseFragment.newInstance(
            gameId = gameId,
            playerId = playerId,
            isVotingPhase = state == GameState.DAY_VOTING,
            canVote = currentPlayer?.isAlive == true
        )

        replaceFragment(fragment)
    }

    private fun showResultsPhase(state: GameState) {
        // Show results fragment
        val fragment = ResultsFragment.newInstance(
            gameId = gameId,
            playerId = playerId,
            resultType = state
        )

        replaceFragment(fragment)
    }

    /**
     * Show a proper game over screen
     */
    private fun showGameOverScreen() {
        // Show the results fragment with GAME_OVER state
        val fragment = ResultsFragment.newInstance(
            gameId = gameId,
            playerId = playerId,
            resultType = GameState.GAME_OVER
        )

        // Replace the current fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()

        // Set the game over flag
        isGameOver = true

        // Hide advance button and ensure leave button is visible
        binding.buttonAdvancePhase.visibility = View.GONE
        binding.buttonLeaveGame.visibility = View.VISIBLE

        // Show game over dialog after a short delay
        Handler(Looper.getMainLooper()).postDelayed({
            showGameOverDialog()
        }, 1000)
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun advanceGamePhase() {
        if (!isHost) {
            Toast.makeText(this, "Only the host can advance the game phase", Toast.LENGTH_SHORT).show()
            return
        }

        // Don't advance phase if game is over
        if (isGameOver) {
            Toast.makeText(this, "The game is over!", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading indicator
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonAdvancePhase.isEnabled = false

        // Try to advance the game phase
        gameController.advancePhase(
            playerId = playerId,
            onSuccess = {
                // Hide loading indicator
                binding.progressBar.visibility = View.GONE
                binding.buttonAdvancePhase.isEnabled = true
            },
            onFailure = { exception ->
                // Hide loading indicator
                binding.progressBar.visibility = View.GONE
                binding.buttonAdvancePhase.isEnabled = true

                // Show error message
                Toast.makeText(this, "Error: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    /**
     * Show the game over dialog with options to exit or stay
     */
    private fun showGameOverDialog() {
        AlertDialog.Builder(this)
            .setTitle("Game Over")
            .setMessage("The game has ended! You can stay to review the final results or return to the main menu.")
            .setPositiveButton("Return to Menu") { _, _ ->
                finish()
            }
            .setNegativeButton("Stay and Review") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    fun showPersonalResultDialog(message: String) {
        if (message.isNotEmpty()) {
            // Use a styled dialog to make investigation results more impactful
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Investigation Results")
                .setMessage(message)
                .setIcon(android.R.drawable.ic_menu_info_details)
                .setCancelable(false) // Force user to acknowledge
                .setPositiveButton("Understood") { dialog, _ ->
                    dialog.dismiss()
                }

            // Create and show the dialog
            val dialog = builder.create()
            dialog.show()

            // Optional: Style the message text for better emphasis
            try {
                val textView = dialog.findViewById<android.widget.TextView>(android.R.id.message)
                textView?.let {
                    it.textSize = 16f

                    // If the result indicates the target is Mafia, highlight it in red
                    if (message.contains("IS part of the Mafia")) {
                        it.setTextColor(android.graphics.Color.RED)
                    }
                }
            } catch (e: Exception) {
                // Fallback if styling fails
                Log.e("GameActivity", "Failed to style dialog: ${e.message}")
            }
        }
    }

    private fun confirmLeaveGame() {
        AlertDialog.Builder(this)
            .setTitle("Leave Game")
            .setMessage("Are you sure you want to leave the game? If you're the host, the game will continue with a new host.")
            .setPositiveButton("Yes") { _, _ ->
                leaveGame()
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun leaveGame() {
        FirebaseManager.leaveGame(
            gameId = gameId,
            playerId = playerId,
            onSuccess = {
                finish()
            },
            onFailure = { exception ->
                Toast.makeText(this, "Error leaving game: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove Firebase listener
        gameListener?.let { FirebaseManager.removeGameListener(gameId, it) }

        // Clean up controllers
        if (isFinishing) {
            gameController.cleanup()
        }
    }
}