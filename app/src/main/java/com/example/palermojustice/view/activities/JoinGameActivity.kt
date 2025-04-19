package com.example.palermojustice.view.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.palermojustice.databinding.ActivityJoinGameBinding
import com.example.palermojustice.firebase.FirebaseManager
import com.example.palermojustice.model.Player

class JoinGameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJoinGameBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJoinGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonJoinGame.setOnClickListener {
            joinGame()
        }
    }

    private fun joinGame() {
        val playerName = binding.editTextPlayerName.text.toString()
        val gameCode = binding.editTextGameCode.text.toString()

        if (playerName.isEmpty() || gameCode.isEmpty()) {
            Toast.makeText(this, "Please enter your name and the game code", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading indicator
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonJoinGame.isEnabled = false

        // Create player object
        val playerId = FirebaseManager.getCurrentUserId()
        val player = Player(id = playerId, name = playerName)

        // Join game by code
        FirebaseManager.joinGameByCode(
            gameCode = gameCode,
            player = player,
            onSuccess = { gameId ->
                // Hide loading indicator
                binding.progressBar.visibility = View.GONE

                // Navigate to lobby
                val intent = Intent(this, LobbyActivity::class.java).apply {
                    putExtra("GAME_ID", gameId)
                    putExtra("PLAYER_ID", playerId)
                    putExtra("IS_HOST", false)
                }
                startActivity(intent)
                finish()
            },
            onFailure = { exception ->
                // Hide loading indicator
                binding.progressBar.visibility = View.GONE
                binding.buttonJoinGame.isEnabled = true

                // Show error message
                Toast.makeText(this, "Error joining game: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }
}