package com.example.palermojustice.view.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.palermojustice.databinding.FragmentNightPhaseBinding
import com.example.palermojustice.firebase.FirebaseManager
import com.example.palermojustice.model.ActionType
import com.example.palermojustice.model.Role
import com.example.palermojustice.view.activities.RoleActionActivity
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError

/**
 * Fragment for the night phase of the game.
 * Shows different UI based on player role and allows night actions.
 */
class NightPhaseFragment : Fragment() {
    private var _binding: FragmentNightPhaseBinding? = null
    private val binding get() = _binding!!

    private var gameId: String = ""
    private var playerId: String = ""
    private var playerRole: String? = null
    private var canAct: Boolean = false
    private var gameListener: ValueEventListener? = null
    private var hasPerformedAction: Boolean = false

    companion object {
        fun newInstance(
            gameId: String,
            playerId: String,
            canAct: Boolean,
            role: String?
        ): NightPhaseFragment {
            val fragment = NightPhaseFragment()
            val args = Bundle().apply {
                putString("GAME_ID", gameId)
                putString("PLAYER_ID", playerId)
                putBoolean("CAN_ACT", canAct)
                putString("PLAYER_ROLE", role)
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
            canAct = it.getBoolean("CAN_ACT", false)
            playerRole = it.getString("PLAYER_ROLE")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNightPhaseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup UI based on role
        setupUI()

        // Check if player has already performed an action
        checkExistingActions()
    }

    private fun setupUI() {
        // Set night ambiance and general description
        binding.textViewNightPhaseTitle.text = "Night Phase"
        binding.textViewNightPhaseDescription.text =
            "The town sleeps while special roles perform their actions."

        // Set role-specific UI
        val roleEnum = try {
            playerRole?.let { Role.valueOf(it) }
        } catch (e: IllegalArgumentException) {
            null
        }

        if (roleEnum != null && canAct) {
            // Player has a special night action
            binding.textViewRoleInstructions.text = roleEnum.getNightActionDescription()
            binding.buttonPerformAction.text = "Perform ${roleEnum.displayName} Action"
            binding.actionSection.visibility = View.VISIBLE

            // Set click listener for action button
            binding.buttonPerformAction.setOnClickListener {
                performRoleAction(roleEnum)
            }
        } else {
            // Player does not have a night action
            if (!canAct && playerRole != null) {
                if (playerRole == Role.PAESANO.name) {
                    binding.textViewRoleInstructions.text =
                        "As a Paesano, you sleep during the night. Wait for the day to begin."
                } else {
                    binding.textViewRoleInstructions.text =
                        "You cannot perform any actions at night. Wait for the day to begin."
                }
            } else if (!canAct) {
                binding.textViewRoleInstructions.text =
                    "You are dead and cannot perform any actions. Wait for the next phase."
            }
            binding.actionSection.visibility = View.GONE
        }
    }

    private fun checkExistingActions() {
        if (!canAct || playerRole == null) return

        // Get the action type based on role
        val actionType = try {
            when (Role.valueOf(playerRole!!)) {
                Role.MAFIOSO -> ActionType.KILL
                Role.ISPETTORE -> ActionType.INVESTIGATE
                Role.SGARRISTA -> ActionType.PROTECT
                Role.IL_PRETE -> ActionType.BLESS
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            null
        } ?: return

        // Check if player has already performed this action
        val database = FirebaseDatabase.getInstance()
        val actionsRef = database.getReference("games")
            .child(gameId)
            .child("actions")
            .child("night")
            .child(getCurrentPhaseNumber().toString())
            .child(playerId)

        actionsRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                // Player has already performed an action
                hasPerformedAction = true
                binding.buttonPerformAction.isEnabled = false
                binding.buttonPerformAction.text = "Action Submitted"
                binding.textViewActionStatus.text = "You have already performed your night action."
                binding.textViewActionStatus.visibility = View.VISIBLE
            }
        }
    }

    private fun performRoleAction(role: Role) {
        // Determine action type
        val actionType = when (role) {
            Role.MAFIOSO -> ActionType.KILL
            Role.ISPETTORE -> ActionType.INVESTIGATE
            Role.SGARRISTA -> ActionType.PROTECT
            Role.IL_PRETE -> ActionType.BLESS
            else -> return
        }

        // Start RoleActionActivity
        val intent = Intent(requireContext(), RoleActionActivity::class.java).apply {
            putExtra("GAME_ID", gameId)
            putExtra("PLAYER_ID", playerId)
            putExtra("PLAYER_ROLE", role.name)
            putExtra("ACTION_TYPE", actionType.name)
        }
        startActivity(intent)
    }

    /**
     * Get the current phase number from Firebase
     */
    private fun getCurrentPhaseNumber(): Int {
        var phaseNumber = 1 // Default to 1

        try {
            // Use synchronous approach for simplicity
            val database = FirebaseDatabase.getInstance()
            val gameRef = database.getReference("games").child(gameId)

            // Check for existing actions in a different way
            val actionsRef = gameRef.child("actions")
                .child("night")
                .child(playerId)

            // Check for current phase in game data
            gameRef.child("currentPhase").get()
                .addOnSuccessListener { dataSnapshot ->
                    if (dataSnapshot.exists()) {
                        val value = dataSnapshot.getValue(Long::class.java)
                        if (value != null) {
                            phaseNumber = value.toInt()
                        }
                    }
                }
        } catch (e: Exception) {
            // Log error and return default phase number
            e.printStackTrace()
        }

        return phaseNumber
    }

    override fun onResume() {
        super.onResume()
        // Check if action was performed when returning from RoleActionActivity
        if (canAct && playerRole != null) {
            checkExistingActions()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null

        // Remove Firebase listeners
        gameListener?.let { FirebaseManager.removeGameListener(gameId, it) }
    }
}