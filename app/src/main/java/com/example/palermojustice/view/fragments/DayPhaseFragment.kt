package com.example.palermojustice.view.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.palermojustice.controller.GameController
import com.example.palermojustice.databinding.FragmentDayPhaseBinding
import com.example.palermojustice.firebase.FirebaseManager
import com.example.palermojustice.view.adapters.VotingAdapter
import com.google.firebase.database.ValueEventListener

/**
 * Fragment for day phase gameplay.
 * Handles both discussion phase and voting phase.
 */
class DayPhaseFragment : Fragment() {
    private var _binding: FragmentDayPhaseBinding? = null
    private val binding get() = _binding!!

    private lateinit var gameController: GameController
    private lateinit var adapter: VotingAdapter

    private var gameId: String = ""
    private var playerId: String = ""
    private var isVotingPhase: Boolean = false
    private var canVote: Boolean = false
    private var selectedTargetId: String? = null
    private var gameListener: ValueEventListener? = null

    companion object {
        fun newInstance(
            gameId: String,
            playerId: String,
            isVotingPhase: Boolean,
            canVote: Boolean
        ): DayPhaseFragment {
            val fragment = DayPhaseFragment()
            val args = Bundle().apply {
                putString("GAME_ID", gameId)
                putString("PLAYER_ID", playerId)
                putBoolean("IS_VOTING_PHASE", isVotingPhase)
                putBoolean("CAN_VOTE", canVote)
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
            isVotingPhase = it.getBoolean("IS_VOTING_PHASE", false)
            canVote = it.getBoolean("CAN_VOTE", false)
        }

        // Initialize controller
        gameController = GameController.getInstance(gameId)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDayPhaseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup UI
        setupUI()

        // Listen for game updates
        listenToGameUpdates()

        // If in voting phase, listen for votes
        if (isVotingPhase) {
            listenForVotes()
        }
    }

    private fun setupUI() {
        // Update UI based on phase
        if (isVotingPhase) {
            binding.textViewDayPhaseTitle.text = "Voting Phase"
            binding.textViewDayPhaseDescription.text =
                "Vote to eliminate someone you suspect to be part of the Mafia!"
            binding.votingSection.visibility = View.VISIBLE
            binding.discussionSection.visibility = View.GONE

            // Setup recycler view
            binding.recyclerViewPlayers.layoutManager = LinearLayoutManager(requireContext())
            adapter = VotingAdapter(emptyList(), playerId) { targetId ->
                selectedTargetId = targetId
                binding.buttonVote.isEnabled = true
            }
            binding.recyclerViewPlayers.adapter = adapter

            // Setup voting button
            binding.buttonVote.isEnabled = false
            binding.buttonVote.setOnClickListener {
                submitVote()
            }

            // Disable voting if player cannot vote
            if (!canVote) {
                // Hide voting UI elements for dead players
                binding.buttonVote.visibility = View.GONE
                binding.recyclerViewPlayers.visibility = View.GONE
                binding.textViewVotingInstructions.text =
                    "You are dead and cannot vote. Watch how the living decide."
            }
        } else {
            // Discussion phase
            binding.textViewDayPhaseTitle.text = "Day Discussion"
            binding.textViewDayPhaseDescription.text =
                "Discuss with other players who might be the Mafia."
            binding.votingSection.visibility = View.GONE
            binding.discussionSection.visibility = View.VISIBLE

            if (!canVote) {
                binding.textViewDiscussionInstructions.text =
                    "You are dead. You cannot participate in the discussion, but you can observe."
            }
        }
    }

    private fun listenToGameUpdates() {
        gameListener = FirebaseManager.listenToGame(
            gameId = gameId,
            onUpdate = { game ->
                // Update player list for voting
                if (isVotingPhase && canVote) {  // Only update player list if can vote
                    val votablePlayers = game.players.values.filter {
                        it.id != playerId && it.isAlive
                    }
                    adapter.updatePlayers(votablePlayers.toList())

                    // Show message if no valid targets
                    if (votablePlayers.isEmpty()) {
                        binding.textViewNoTargets.visibility = View.VISIBLE
                        binding.recyclerViewPlayers.visibility = View.GONE
                        binding.buttonVote.isEnabled = false
                    } else {
                        binding.textViewNoTargets.visibility = View.GONE
                        binding.recyclerViewPlayers.visibility = View.VISIBLE
                    }
                }

                // Update player status text - always show this information regardless of alive status
                val alivePlayers = game.players.values.count { it.isAlive }
                val totalPlayers = game.players.size
                binding.textViewPlayerCount.text = "Players: $alivePlayers alive / $totalPlayers total"
            },
            onError = { exception ->
                Toast.makeText(requireContext(), "Error: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun listenForVotes() {
        val votingController = GameController.getInstance(gameId).let {
            try {
                val field = it.javaClass.getDeclaredField("votingController")
                field.isAccessible = true
                field.get(it)
            } catch (e: Exception) {
                null
            }
        }

        // If we couldn't get the voting controller, skip this
        if (votingController == null) return

        try {
            val method = votingController.javaClass.getDeclaredMethod(
                "listenForVotes",
                Int::class.java,
                Function1::class.java
            )
            method.isAccessible = true

            // Get current phase number
            val phaseNumberField = gameController.javaClass.getDeclaredField("currentPhaseNumber")
            phaseNumberField.isAccessible = true
            val phaseNumber = phaseNumberField.getInt(gameController)

            // Listen for votes
            method.invoke(votingController, phaseNumber, object : Function1<Map<String, String>, Unit> {
                override fun invoke(votes: Map<String, String>) {
                    updateVotesDisplay(votes)
                }
            })
        } catch (e: Exception) {
            // Couldn't set up listener, but that's ok
        }
    }

    private fun updateVotesDisplay(votes: Map<String, String>) {
        // Early return if fragment is not attached
        if (!isAdded || _binding == null) {
            return
        }

        // Count how many people have voted
        val voteCount = votes.size

        // Update vote count text
        binding.textViewVoteCount.text = "$voteCount players have voted"

        // Check if this player has voted
        if (votes.containsKey(playerId)) {
            binding.buttonVote.isEnabled = false
            binding.buttonVote.text = "Vote Submitted"
        }
    }

    private fun submitVote() {
        val targetId = selectedTargetId
        if (targetId == null) {
            Toast.makeText(requireContext(), "Please select a target", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading indicator
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonVote.isEnabled = false

        // Submit vote to controller
        gameController.submitVote(
            voterId = playerId,
            targetId = targetId,
            onSuccess = {
                // Hide loading indicator
                binding.progressBar.visibility = View.GONE
                binding.buttonVote.text = "Vote Submitted"

                // Show success message
                Toast.makeText(requireContext(), "Vote submitted successfully", Toast.LENGTH_SHORT).show()
            },
            onFailure = { exception ->
                // Hide loading indicator
                binding.progressBar.visibility = View.GONE
                binding.buttonVote.isEnabled = true

                // Show error message
                Toast.makeText(requireContext(), "Error: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    /**
     * Cleanup any listeners when the fragment is destroyed
     */
    override fun onDestroyView() {
        super.onDestroyView()

        // Remove Firebase listeners
        gameListener?.let { FirebaseManager.removeGameListener(gameId, it) }

        // Also remove the votes listener if it exists
        val votingController = GameController.getInstance(gameId).let {
            try {
                val field = it.javaClass.getDeclaredField("votingController")
                field.isAccessible = true
                field.get(it)
            } catch (e: Exception) {
                null
            }
        }

        // Clean up votes listener if possible
        try {
            if (votingController != null) {
                val cleanupMethod = votingController.javaClass.getDeclaredMethod("cleanup")
                cleanupMethod.isAccessible = true
                cleanupMethod.invoke(votingController)
            }
        } catch (e: Exception) {
            // Safely ignore if cleanup fails
            Log.e("DayPhaseFragment", "Error cleaning up voting listeners: ${e.message}")
        }

        _binding = null
    }
}