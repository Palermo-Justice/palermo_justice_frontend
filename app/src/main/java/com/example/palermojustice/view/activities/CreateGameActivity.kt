package com.example.palermojustice.view.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.palermojustice.databinding.ActivityCreateGameBinding
import com.example.palermojustice.firebase.FirebaseManager
import com.example.palermojustice.model.Player

class CreateGameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateGameBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonCreateGame.setOnClickListener {
            createGame()
        }
    }

    private fun createGame() {
        val playerName = binding.editTextPlayerName.text.toString()
        if (playerName.isEmpty()) {
            Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading indicator
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonCreateGame.isEnabled = false

        // Create player object
        val playerId = FirebaseManager.getCurrentUserId()
        val player = Player(id = playerId, name = playerName)

        // Create game in Firebase
        FirebaseManager.createGame(
            hostPlayer = player,
            onSuccess = { gameId, gameCode ->
                // Hide loading indicator
                binding.progressBar.visibility = View.GONE

                // Show game code to host
                Toast.makeText(this, "Game created! Code: $gameCode", Toast.LENGTH_LONG).show()

                // Navigate to lobby
                val intent = Intent(this, LobbyActivity::class.java).apply {
                    putExtra("GAME_ID", gameId)
                    putExtra("PLAYER_ID", playerId)
                    putExtra("IS_HOST", true)
                }
                startActivity(intent)
                finish()
            },
            onFailure = { exception ->
                // Hide loading indicator
                binding.progressBar.visibility = View.GONE
                binding.buttonCreateGame.isEnabled = true

                // Show error message
                Toast.makeText(this, "Error creating game: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }
}