package com.example.palermojustice.view.activities

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.palermojustice.controller.GameController
import com.example.palermojustice.databinding.ActivityRoleActionBinding
import com.example.palermojustice.firebase.FirebaseManager
import com.example.palermojustice.model.ActionType
import com.example.palermojustice.model.Game
import com.example.palermojustice.model.Role
import com.example.palermojustice.view.adapters.VotingAdapter
import com.google.firebase.database.ValueEventListener

/**
 * Activity for role-specific actions during night phase.
 * Allows players to perform their role-specific actions such as killing, investigating, or protecting.
 */
class RoleActionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoleActionBinding
    private lateinit var gameController: GameController
    private lateinit var adapter: VotingAdapter

    private var gameId: String = ""
    private var playerId: String = ""
    private var playerRole: String? = null
    private var actionType: ActionType = ActionType.VOTE
    private var selectedTargetId: String? = null
    private var gameListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoleActionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get data from intent
        gameId = intent.getStringExtra("GAME_ID") ?: ""
        playerId = intent.getStringExtra("PLAYER_ID") ?: ""
        playerRole = intent.getStringExtra("PLAYER_ROLE")
        val actionTypeStr = intent.getStringExtra("ACTION_TYPE") ?: ""

        // Parse action type
        actionType = try {
            ActionType.valueOf(actionTypeStr)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "Invalid action type", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (gameId.isEmpty() || playerId.isEmpty() || playerRole == null) {
            Toast.makeText(this, "Error: Invalid action data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Initialize controller
        gameController = GameController.getInstance(gameId)

        // Set up UI
        setupUI()

        // Listen for game updates to get player list
        listenToGameUpdates()
    }

    private fun setupUI() {
        // Set action title and description based on role
        val roleEnum = try {
            playerRole?.let { Role.valueOf(it) }
        } catch (e: IllegalArgumentException) {
            null
        }

        val actionTitle = when (actionType) {
            ActionType.KILL -> "Choose a Target to Eliminate"
            ActionType.INVESTIGATE -> "Choose a Player to Investigate"
            ActionType.PROTECT -> "Choose a Player to Protect"
            ActionType.BLESS -> "Choose a Player to Bless"
            ActionType.VOTE -> "Vote to Eliminate"
        }

        binding.textViewActionTitle.text = actionTitle

        // Set description based on role
        roleEnum?.let {
            binding.textViewActionDescription.text = it.getNightActionDescription()
        }

        // Setup recycler view
        binding.recyclerViewPlayers.layoutManager = LinearLayoutManager(this)
        adapter = VotingAdapter(emptyList(), playerId) { targetId ->
            selectedTargetId = targetId
            binding.buttonSubmitAction.isEnabled = true
        }
        binding.recyclerViewPlayers.adapter = adapter

        // Setup button
        binding.buttonSubmitAction.isEnabled = false
        binding.buttonSubmitAction.setOnClickListener {
            submitAction()
        }

        binding.buttonCancel.setOnClickListener {
            finish()
        }
    }

    private fun listenToGameUpdates() {
        gameListener = FirebaseManager.listenToGame(
            gameId = gameId,
            onUpdate = { game ->
                updatePlayersList(game)
            },
            onError = { exception ->
                Toast.makeText(this, "Error: ${exception.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        )
    }

    private fun updatePlayersList(game: Game) {
        // Filter players based on action type
        val targetablePlayers = when (actionType) {
            ActionType.KILL, ActionType.INVESTIGATE, ActionType.VOTE -> {
                // Can't target self or dead players
                game.players.values.filter {
                    it.id != playerId && it.isAlive
                }
            }
            ActionType.PROTECT, ActionType.BLESS -> {
                // Can protect/bless any alive player including self
                game.players.values.filter { it.isAlive }
            }
        }

        // Special case for Mafia - don't show other Mafia members for killing
        val finalPlayersList = if (actionType == ActionType.KILL && playerRole == Role.MAFIOSO.name) {
            targetablePlayers.filter { it.role != Role.MAFIOSO.name }
        } else {
            targetablePlayers
        }

        // Update adapter
        adapter.updatePlayers(finalPlayersList.toList())

        // Show message if no valid targets
        if (finalPlayersList.isEmpty()) {
            binding.textViewNoTargets.visibility = View.VISIBLE
            binding.recyclerViewPlayers.visibility = View.GONE
            binding.buttonSubmitAction.isEnabled = false
        } else {
            binding.textViewNoTargets.visibility = View.GONE
            binding.recyclerViewPlayers.visibility = View.VISIBLE
        }
    }

    private fun submitAction() {
        val targetId = selectedTargetId
        if (targetId == null) {
            Toast.makeText(this, "Please select a target", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading indicator
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonSubmitAction.isEnabled = false
        binding.buttonCancel.isEnabled = false

        // Submit action to controller
        gameController.performRoleAction(
            playerId = playerId,
            targetId = targetId,
            actionType = actionType,
            onSuccess = {
                // Hide loading indicator
                binding.progressBar.visibility = View.GONE

                // Show success message and finish
                Toast.makeText(this, "Action submitted successfully", Toast.LENGTH_SHORT).show()
                finish()
            },
            onFailure = { exception ->
                // Hide loading indicator
                binding.progressBar.visibility = View.GONE
                binding.buttonSubmitAction.isEnabled = true
                binding.buttonCancel.isEnabled = true

                // Show error message
                Toast.makeText(this, "Error: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove Firebase listener
        gameListener?.let { FirebaseManager.removeGameListener(gameId, it) }
    }
}