package com.example.palermojustice.view.activities

import android.os.Bundle
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.palermojustice.databinding.ActivityLobbyBinding
import com.example.palermojustice.firebase.FirebaseManager
import com.example.palermojustice.model.Player
import com.example.palermojustice.view.adapters.PlayerAdapter
import com.google.firebase.database.ValueEventListener
import com.example.palermojustice.controller.GameController
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class LobbyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLobbyBinding
    private lateinit var gameId: String
    private lateinit var playerId: String
    private var isHost = false
    private var gameListener: ValueEventListener? = null
    private var virtualPlayersEnabled = false

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

        // Show/hide host controls based on host status
        binding.buttonStartGame.visibility = if (isHost) View.VISIBLE else View.GONE
        binding.switchVirtualPlayers.visibility = if (isHost) View.VISIBLE else View.GONE
        binding.textVirtualPlayersLabel.visibility = if (isHost) View.VISIBLE else View.GONE

        // Show game code
        binding.textViewGameCode.text = "Game Code: Loading..."

        // Button event listeners
        binding.buttonStartGame.setOnClickListener {
            // Only host can start the game
            val controller = GameController.getInstance(gameId)
            controller.startGame(
                onSuccess = {
                    // Game state is updated to "night" in Firebase, listeners will react
                    runOnUiThread {
                        Toast.makeText(this, "Game started!", Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = { exception ->
                    runOnUiThread {
                        Toast.makeText(this, "Failed to start game: ${exception.message}", Toast.LENGTH_SHORT).show()
                        Log.e("LobbyActivity", "Error while updating game", exception)
                    }
                }
            )
        }

        binding.buttonLeaveGame.setOnClickListener {
            leaveGame()
        }

        // Virtual players switch
        binding.switchVirtualPlayers.setOnCheckedChangeListener { _, isChecked ->
            if (isHost) {
                toggleVirtualPlayers(isChecked)
            }
        }
    }

    private fun toggleVirtualPlayers(enabled: Boolean) {
        if (!isHost) {
            return
        }

        virtualPlayersEnabled = enabled

        // Update the value in Firebase
        val database = FirebaseDatabase.getInstance()
        val gameRef = database.getReference("games").child(gameId)

        gameRef.child("virtualPlayersEnabled").setValue(enabled)
            .addOnSuccessListener {
                Log.d("LobbyActivity", "Virtual players flag set to $enabled")
                Toast.makeText(this, "Virtual players ${if (enabled) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { exception ->
                Log.e("LobbyActivity", "Failed to update virtual players setting", exception)
                Toast.makeText(this, "Failed to update virtual players setting", Toast.LENGTH_SHORT).show()
                // Reset switch to previous state if update fails
                binding.switchVirtualPlayers.isChecked = !enabled
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
                binding.switchVirtualPlayers.visibility = if (isHost) View.VISIBLE else View.GONE
                binding.textVirtualPlayersLabel.visibility = if (isHost) View.VISIBLE else View.GONE

                // Update virtual players switch state if we're the host
                if (isHost) {
                    val virtualPlayersEnabled = FirebaseDatabase.getInstance()
                        .getReference("games")
                        .child(gameId)
                        .child("virtualPlayersEnabled")
                        .get()
                        .addOnSuccessListener { snapshot ->
                            val enabled = snapshot.getValue(Boolean::class.java) ?: false
                            binding.switchVirtualPlayers.isChecked = enabled
                        }
                }

                // Check if game has started
                if (game.status != "lobby") {
                    // Navigate to GameActivity
                    val intent = Intent(this, GameActivity::class.java).apply {
                        putExtra("GAME_ID", gameId)
                        putExtra("PLAYER_ID", playerId)
                        putExtra("IS_HOST", isHost)
                    }
                    startActivity(intent)
                    finish()
                    return@listenToGame
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