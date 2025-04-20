package com.example.palermojustice.view.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.palermojustice.databinding.FragmentResultsBinding
import com.example.palermojustice.firebase.FirebaseManager
import com.example.palermojustice.model.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.FirebaseDatabase
import android.graphics.Typeface
import com.example.palermojustice.view.activities.GameActivity

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

        // Make sure all sections are initially hidden until we have data
        binding.gameOverSection.visibility = View.GONE
        binding.eliminatedSection.visibility = View.GONE
        binding.privateResultSection.visibility = View.GONE
        binding.roundSummarySection.visibility = View.GONE
    }

    private fun loadResults() {
        // Get current phase number and results from Firebase
        val gameRef = FirebaseDatabase.getInstance().getReference("games").child(gameId)

        Log.d("ResultsFragment", "Loading results for game $gameId, resultType=$resultType")

        gameRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val phaseNumber = snapshot.child("currentPhase").getValue(Long::class.java)?.toInt() ?: 0
                Log.d("ResultsFragment", "Current phase from Firebase: $phaseNumber")

                val resultsSnapshot = snapshot.child("phaseResults").child(phaseNumber.toString())
                val gameStatus = snapshot.child("status").getValue(String::class.java) ?: ""

                Log.d("ResultsFragment", "Loading results for phase $phaseNumber, status: $gameStatus")

                if (resultsSnapshot.exists()) {
                    Log.d("ResultsFragment", "Results snapshot exists for phase $phaseNumber")

                    // Extract result data
                    val stateStr = resultsSnapshot.child("state").getValue(String::class.java)
                    val eliminatedPlayerId = resultsSnapshot.child("eliminatedPlayerId").getValue(String::class.java)
                    val eliminatedPlayerName = resultsSnapshot.child("eliminatedPlayerName").getValue(String::class.java)
                    val eliminatedPlayerRole = resultsSnapshot.child("eliminatedPlayerRole").getValue(String::class.java)
                    val investigationResult = resultsSnapshot.child("investigationResult").getValue(Boolean::class.java)
                    val investigatedPlayerId = resultsSnapshot.child("investigatedPlayerId").getValue(String::class.java)
                    val winningTeamStr = resultsSnapshot.child("winningTeam").getValue(String::class.java)

                    Log.d("ResultsFragment", "Parsed results: state=$stateStr, eliminated=$eliminatedPlayerName, investigatedPlayer=$investigatedPlayerId")

                    // Create GameResult object
                    val state = try {
                        stateStr?.let { GameState.valueOf(it) } ?: resultType
                    } catch (e: IllegalArgumentException) {
                        resultType
                    }

                    // Parse winning team if present
                    val winningTeam = if (winningTeamStr != null) {
                        try {
                            Team.valueOf(winningTeamStr)
                        } catch (e: IllegalArgumentException) {
                            null
                        }
                    } else null

                    val result = GameResult(
                        phaseNumber = phaseNumber,
                        state = state,
                        eliminatedPlayerId = eliminatedPlayerId,
                        eliminatedPlayerName = eliminatedPlayerName,
                        eliminatedPlayerRole = eliminatedPlayerRole,
                        investigationResult = investigationResult,
                        investigatedPlayerId = investigatedPlayerId,
                        winningTeam = winningTeam
                    )

                    // Update UI with result
                    updateResults(result)

                    // Check for player-specific private information directly
                    checkPrivateResultsDirectly(result)

                    // Load and display round summary
                    loadRoundSummary(phaseNumber)

                    // Special handling for game over state
                    if (gameStatus == "finished" || winningTeam != null) {
                        showGameOverDetails(winningTeam, snapshot)
                    }
                } else {
                    // No results available yet
                    Log.d("ResultsFragment", "No results found for phase $phaseNumber")
                    binding.textViewResultMessage.text = "Results are being prepared..."
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ResultsFragment", "Database error: ${error.message}")
                binding.textViewResultMessage.text = "Error loading results"
            }
        })
    }

    /**
     * Update the UI with game results
     */
    fun updateResults(result: GameResult) {
        // Check if view is attached and binding is valid
        if (!isAdded || _binding == null) {
            Log.d("ResultsFragment", "Fragment detached before updating results")
            return
        }

        Log.d("ResultsFragment", "Updating UI with result: $result")

        // Show main result message
        binding.textViewResultMessage.text = result.getPublicDescription()

        // Add a more prominent heading for night results
        if (resultType == GameState.NIGHT_RESULTS) {
            binding.textViewResultTitle.text = "NIGHT RESULTS"
            binding.textViewResultSubtitle.text = "Here's what happened during the night..."

            // Make the result message more prominent using simpler styling
            binding.textViewResultMessage.textSize = 18f
            binding.textViewResultMessage.setTypeface(null, Typeface.BOLD)
        }

        // Show eliminated player info if available
        if (result.eliminatedPlayerName != null) {
            binding.textViewEliminatedPlayer.text = "Eliminated: ${result.eliminatedPlayerName}"
            binding.textViewEliminatedRole.text = "Role: ${getRoleDisplayName(result.eliminatedPlayerRole)}"
            binding.eliminatedSection.visibility = View.VISIBLE

            // If this is night results and someone was eliminated, add visual emphasis
            if (resultType == GameState.NIGHT_RESULTS) {
                binding.eliminatedSection.setBackgroundColor(0xFFFFDDDD.toInt())
                binding.textViewEliminatedPlayer.setTextColor(0xFFCC0000.toInt())
            }
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

        // Only load round summary for night results if we have a valid binding
        if (resultType == GameState.NIGHT_RESULTS && _binding != null) {
            loadRoundSummary(result.phaseNumber)
        }
    }

    /**
     * Check if this player has investigation results to see
     * Fixed to properly check if THIS player performed the investigation
     */
    private fun checkPrivateResultsDirectly(result: GameResult) {
        // Exit early if view is detached
        if (!isAdded || _binding == null) {
            return
        }

        // Get player role
        val database = FirebaseDatabase.getInstance()
        val playerRef = database.getReference("games")
            .child(gameId)
            .child("players")
            .child(playerId)

        playerRef.get().addOnSuccessListener { playerSnapshot ->
            val role = playerSnapshot.child("role").getValue(String::class.java)

            Log.d("ResultsFragment", "Checking private results for player $playerId with role $role")

            // For Ispettore - show investigation results if they performed the investigation
            if (role == Role.ISPETTORE.name) {
                // Check if this player performed the investigation
                val actionsRef = database.getReference("games")
                    .child(gameId)
                    .child("actions")
                    .child("night")
                    .child(result.phaseNumber.toString())
                    .child(playerId)

                actionsRef.get().addOnSuccessListener { actionSnapshot ->
                    if (actionSnapshot.exists() &&
                        actionSnapshot.child("actionType").getValue(String::class.java) == ActionType.INVESTIGATE.name) {

                        // This player performed an investigation, show results
                        val targetId = actionSnapshot.child("targetPlayerId").getValue(String::class.java)

                        if (targetId != null && result.investigationResult != null) {
                            // Get target player's name
                            database.getReference("games")
                                .child(gameId)
                                .child("players")
                                .child(targetId)
                                .child("name")
                                .get()
                                .addOnSuccessListener { nameSnapshot ->
                                    val targetName = nameSnapshot.getValue(String::class.java) ?: "Unknown Player"

                                    // Show private result
                                    val resultText = if (result.investigationResult == true) {
                                        "Your investigation reveals: $targetName IS part of the Mafia!"
                                    } else {
                                        "Your investigation reveals: $targetName is NOT part of the Mafia."
                                    }

                                    Log.d("ResultsFragment", "Found investigation result: $resultText")
                                    binding.textViewPrivateResult.text = resultText
                                    binding.privateResultSection.visibility = View.VISIBLE

                                    // Notify the activity to show a dialog with this information
                                    if (activity is GameActivity) {
                                        (activity as GameActivity).showPersonalResultDialog(resultText)
                                    }
                                }
                        }
                    } else {
                        Log.d("ResultsFragment", "Player did not perform investigation this phase")
                    }
                }
            }
        }
    }

    /**
     * Load and display a summary of the current round
     */
    private fun loadRoundSummary(phaseNumber: Int) {
        val gameRef = FirebaseDatabase.getInstance().getReference("games").child(gameId)

        // Fetch all phase results up to this phase to create a summary
        gameRef.child("phaseResults").get().addOnSuccessListener { snapshot ->
            // Early check if fragment is still attached and binding is valid
            if (!isAdded || _binding == null) {
                Log.d("ResultsFragment", "Fragment detached before round summary loaded")
                return@addOnSuccessListener
            }

            val summaryBuilder = StringBuilder()
            summaryBuilder.append("Round ${phaseNumber/2 + 1} Summary:\n\n")

            // Get all phase results up to the current phase
            val phaseResults = mutableListOf<Pair<Int, DataSnapshot>>()
            for (phaseSnapshot in snapshot.children) {
                val phaseNum = phaseSnapshot.key?.toIntOrNull() ?: continue
                if (phaseNum <= phaseNumber) {
                    phaseResults.add(Pair(phaseNum, phaseSnapshot))
                }
            }

            // Sort by phase number
            phaseResults.sortBy { it.first }

            // Build summary text
            val currentRoundResults = phaseResults.filter { it.first > phaseNumber - 2 }
            for (pair in currentRoundResults) {
                val result = pair.second
                val stateStr = result.child("state").getValue(String::class.java) ?: continue
                val state = try {
                    GameState.valueOf(stateStr)
                } catch (e: IllegalArgumentException) {
                    continue
                }

                // Add night result
                if (state == GameState.NIGHT_RESULTS) {
                    val eliminatedName = result.child("eliminatedPlayerName").getValue(String::class.java)
                    val eliminatedRole = result.child("eliminatedPlayerRole").getValue(String::class.java)

                    if (eliminatedName != null) {
                        summaryBuilder.append("Night: $eliminatedName (${getRoleDisplayName(eliminatedRole)}) was eliminated by the Mafia.\n")
                    } else {
                        summaryBuilder.append("Night: No one was eliminated.\n")
                    }
                }

                // Add day result
                if (state == GameState.EXECUTION_RESULT) {
                    val eliminatedName = result.child("eliminatedPlayerName").getValue(String::class.java)
                    val eliminatedRole = result.child("eliminatedPlayerRole").getValue(String::class.java)

                    if (eliminatedName != null) {
                        summaryBuilder.append("Day: $eliminatedName (${getRoleDisplayName(eliminatedRole)}) was executed by the town.\n")
                    } else {
                        summaryBuilder.append("Day: The town couldn't reach a consensus. No one was executed.\n")
                    }
                }
            }

            // Final check if binding is still valid before updating UI
            if (_binding != null) {
                // Display summary
                binding.textViewRoundSummary.text = summaryBuilder.toString()
                binding.roundSummarySection.visibility = View.VISIBLE
            }
        }.addOnFailureListener { exception ->
            Log.e("ResultsFragment", "Failed to load round summary: ${exception.message}")
        }
    }

    /**
     * Show detailed game over information
     */
    private fun showGameOverDetails(winningTeam: Team?, snapshot: DataSnapshot) {
        val playersSnapshot = snapshot.child("players")
        val summaryBuilder = StringBuilder()

        // Set game over title
        binding.textViewResultTitle.text = "GAME OVER"

        // Add winning team information
        if (winningTeam != null) {
            val teamName = if (winningTeam == Team.MAFIA) "MAFIA" else "CITIZENS"
            binding.textViewWinningTeam.text = "Winner: $teamName"

            summaryBuilder.append("The $teamName have won the game!\n\n")

            if (winningTeam == Team.MAFIA) {
                summaryBuilder.append("The Mafia has taken control of the town. The citizens have failed to identify and eliminate the threats among them.\n\n")
            } else {
                summaryBuilder.append("The citizens have successfully identified and eliminated all mafia members. The town is now safe again!\n\n")
            }
        }

        // Add player details
        summaryBuilder.append("FINAL ROLES:\n")
        val playersList = mutableListOf<Pair<String, DataSnapshot>>()
        for (playerSnapshot in playersSnapshot.children) {
            playersList.add(Pair(playerSnapshot.key ?: "", playerSnapshot))
        }

        // First show mafia members
        summaryBuilder.append("\nMafia Members:\n")
        for (pair in playersList) {
            val player = pair.second
            val name = player.child("name").getValue(String::class.java) ?: continue
            val role = player.child("role").getValue(String::class.java) ?: continue
            val isAlive = player.child("isAlive").getValue(Boolean::class.java) ?: true
            val status = if (isAlive) "Survived" else "Eliminated"

            if (role == Role.MAFIOSO.name) {
                summaryBuilder.append("- $name ($status)\n")
            }
        }

        // Then show citizens with special roles
        summaryBuilder.append("\nSpecial Roles:\n")
        for (pair in playersList) {
            val player = pair.second
            val name = player.child("name").getValue(String::class.java) ?: continue
            val role = player.child("role").getValue(String::class.java) ?: continue
            val isAlive = player.child("isAlive").getValue(Boolean::class.java) ?: true
            val status = if (isAlive) "Survived" else "Eliminated"

            if (role != Role.MAFIOSO.name && role != Role.PAESANO.name) {
                summaryBuilder.append("- $name: ${getRoleDisplayName(role)} ($status)\n")
            }
        }

        // Then show regular citizens
        summaryBuilder.append("\nCitizens:\n")
        for (pair in playersList) {
            val player = pair.second
            val name = player.child("name").getValue(String::class.java) ?: continue
            val role = player.child("role").getValue(String::class.java) ?: continue
            val isAlive = player.child("isAlive").getValue(Boolean::class.java) ?: true
            val status = if (isAlive) "Survived" else "Eliminated"

            if (role == Role.PAESANO.name) {
                summaryBuilder.append("- $name ($status)\n")
            }
        }

        // Update UI with game over details
        binding.textViewResultMessage.text = summaryBuilder.toString()
        binding.gameOverSection.visibility = View.VISIBLE
        binding.eliminatedSection.visibility = View.GONE // Hide eliminated section for cleaner UI
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
    }
}