package com.example.palermojustice.view.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.palermojustice.databinding.FragmentResultsBinding
import com.example.palermojustice.firebase.FirebaseManager
import com.example.palermojustice.model.GameResult
import com.example.palermojustice.model.GameState
import com.example.palermojustice.model.Role
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.FirebaseDatabase

/**
 * Fragment for displaying game phase results.
 * Shows the outcome of night actions and voting.
 */
class ResultsFragment : Fragment() {
    private var _binding: FragmentResultsBinding? = null
    private val binding get() = _binding!!

    private var gameId: String = ""
    private var playerId: String = ""
    private var resultType: GameState = GameState.NIGHT_RESULTS
    private var gameListener: ValueEventListener? = null

    companion object {
        fun newInstance(
            gameId: String,
            playerId: String,
            resultType: GameState
        ): ResultsFragment {
            val fragment = ResultsFragment()
            val args = Bundle().apply {
                putString("GAME_ID", gameId)
                putString("PLAYER_ID", playerId)
                putString("RESULT_TYPE", resultType.name)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            gameId = it.getString("GAME_ID", "")
            playerId = it.getString("PLAYER_ID", "")
            val resultTypeStr = it.getString("RESULT_TYPE", "")

            resultType = try {
                GameState.valueOf(resultTypeStr)
            } catch (e: IllegalArgumentException) {
                GameState.NIGHT_RESULTS
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup UI based on result type
        setupUI()

        // Load results
        loadResults()
    }

    private fun setupUI() {
        // Set title based on result type
        val title = when (resultType) {
            GameState.NIGHT_RESULTS -> "Night Results"
            GameState.EXECUTION_RESULT -> "Execution Results"
            GameState.GAME_OVER -> "Game Over"
            else -> "Results"
        }

        binding.textViewResultTitle.text = title

        // Set subtitle based on result type
        val subtitle = when (resultType) {
            GameState.NIGHT_RESULTS -> "Here's what happened during the night..."
            GameState.EXECUTION_RESULT -> "The town has made its decision..."
            GameState.GAME_OVER -> "The game has ended!"
            else -> ""
        }

        binding.textViewResultSubtitle.text = subtitle
    }

    private fun loadResults() {
        // Get current phase number and results from Firebase
        val gameRef = FirebaseDatabase.getInstance().getReference("games").child(gameId)

        gameRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val phaseNumber = snapshot.child("currentPhase").getValue(Long::class.java)?.toInt() ?: 0
                val resultsSnapshot = snapshot.child("phaseResults").child(phaseNumber.toString())

                if (resultsSnapshot.exists()) {
                    // Extract result data
                    val stateStr = resultsSnapshot.child("state").getValue(String::class.java)
                    val eliminatedPlayerId = resultsSnapshot.child("eliminatedPlayerId").getValue(String::class.java)
                    val eliminatedPlayerName = resultsSnapshot.child("eliminatedPlayerName").getValue(String::class.java)
                    val eliminatedPlayerRole = resultsSnapshot.child("eliminatedPlayerRole").getValue(String::class.java)
                    val investigationResult = resultsSnapshot.child("investigationResult").getValue(Boolean::class.java)
                    val investigatedPlayerId = resultsSnapshot.child("investigatedPlayerId").getValue(String::class.java)
                    val winningTeamStr = resultsSnapshot.child("winningTeam").getValue(String::class.java)

                    // Create GameResult object
                    val state = try {
                        stateStr?.let { GameState.valueOf(it) } ?: resultType
                    } catch (e: IllegalArgumentException) {
                        resultType
                    }

                    val result = GameResult(
                        phaseNumber = phaseNumber,
                        state = state,
                        eliminatedPlayerId = eliminatedPlayerId,
                        eliminatedPlayerName = eliminatedPlayerName,
                        eliminatedPlayerRole = eliminatedPlayerRole,
                        investigationResult = investigationResult,
                        investigatedPlayerId = investigatedPlayerId,
                        winningTeam = null // We'll handle this separately
                    )

                    // Update UI with result
                    updateResults(result)

                    // Check for player-specific private information
                    checkPrivateResults(resultsSnapshot)
                } else {
                    // No results available yet
                    binding.textViewResultMessage.text = "Results are being prepared..."
                }
            }

            override fun onCancelled(error: DatabaseError) {
                binding.textViewResultMessage.text = "Error loading results"
            }
        })
    }

    /**
     * Update the UI with game results
     */
    fun updateResults(result: GameResult) {
        // Show main result message
        binding.textViewResultMessage.text = result.getPublicDescription()

        // Show eliminated player info if available
        if (result.eliminatedPlayerName != null) {
            binding.textViewEliminatedPlayer.text = "Eliminated: ${result.eliminatedPlayerName}"
            binding.textViewEliminatedRole.text = "Role: ${getRoleDisplayName(result.eliminatedPlayerRole)}"
            binding.eliminatedSection.visibility = View.VISIBLE
        } else {
            binding.eliminatedSection.visibility = View.GONE
        }

        // Show game over info if available
        if (result.winningTeam != null) {
            binding.textViewWinningTeam.text = "Winner: ${result.winningTeam.name}"
            binding.gameOverSection.visibility = View.VISIBLE
        } else {
            binding.gameOverSection.visibility = View.GONE
        }
    }

    /**
     * Check if there are private results specific to this player
     */
    private fun checkPrivateResults(resultsSnapshot: DataSnapshot) {
        // Get player role
        val gameRef = FirebaseDatabase.getInstance().getReference("games")
            .child(gameId)
            .child("players")
            .child(playerId)

        gameRef.get().addOnSuccessListener { playerSnapshot ->
            val role = playerSnapshot.child("role").getValue(String::class.java)

            // Check for detective's investigation results
            if (role == Role.ISPETTORE.name) {
                val investigatedPlayerId = resultsSnapshot.child("investigatedPlayerId").getValue(String::class.java)

                if (investigatedPlayerId == playerId) {
                    val investigationResult = resultsSnapshot.child("investigationResult").getValue(Boolean::class.java)

                    // Show private result
                    val resultText = if (investigationResult == true) {
                        "Your investigation reveals: This player IS part of the Mafia!"
                    } else {
                        "Your investigation reveals: This player is NOT part of the Mafia."
                    }

                    binding.textViewPrivateResult.text = resultText
                    binding.privateResultSection.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * Get friendly display name for a role
     */
    private fun getRoleDisplayName(roleString: String?): String {
        if (roleString == null) return "Unknown"

        return try {
            Role.valueOf(roleString).displayName
        } catch (e: IllegalArgumentException) {
            roleString
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null

        // Remove Firebase listeners
        gameListener?.let { FirebaseManager.removeGameListener(gameId, it) }
    }
}