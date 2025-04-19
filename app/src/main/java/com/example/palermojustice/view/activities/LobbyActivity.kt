package com.example.palermojustice.view.activities

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.palermojustice.databinding.ActivityLobbyBinding
import com.example.palermojustice.firebase.FirebaseManager
import com.example.palermojustice.model.Player
import com.example.palermojustice.view.adapters.PlayerAdapter
import com.google.firebase.database.ValueEventListener

class LobbyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLobbyBinding
    private lateinit var gameId: String
    private lateinit var playerId: String
    private var isHost = false
    private var gameListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLobbyBinding.inflate(layoutInflater)
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

        // Setup UI based on host status
        setupUI()

        // Listen for game updates
        listenToGameUpdates()
    }

    private fun setupUI() {
        // Setup RecyclerView for players list
        binding.recyclerViewPlayers.layoutManager = LinearLayoutManager(this)

        // Show/hide start game button based on host status
        binding.buttonStartGame.visibility = if (isHost) View.VISIBLE else View.GONE

        // Show game code
        binding.textViewGameCode.text = "Game Code: Loading..."

        // Button event listeners
        binding.buttonStartGame.setOnClickListener {
            // Start game logic will be implemented later
            Toast.makeText(this, "Start game functionality coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.buttonLeaveGame.setOnClickListener {
            leaveGame()
        }
    }

    private fun listenToGameUpdates() {
        gameListener = FirebaseManager.listenToGame(
            gameId = gameId,
            onUpdate = { game ->
                // Update game code display
                binding.textViewGameCode.text = "Game Code: ${game.gameCode}"

                // Update player list
                updatePlayersList(game.players.values.toList())

                // Check if player is still in the game
                if (!game.players.containsKey(playerId)) {
                    Toast.makeText(this, "You have been removed from the game", Toast.LENGTH_SHORT).show()
                    finish()
                    return@listenToGame
                }

                // Update host status
                isHost = (game.hostId == playerId)
                binding.buttonStartGame.visibility = if (isHost) View.VISIBLE else View.GONE

                // Check if game has started
                if (game.status != "lobby") {
                    // Game started - we'll implement this navigation later
                    Toast.makeText(this, "Game has started!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { exception ->
                Toast.makeText(this, "Error: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun updatePlayersList(players: List<Player>) {
        val adapter = PlayerAdapter(players)
        binding.recyclerViewPlayers.adapter = adapter
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
    }
}