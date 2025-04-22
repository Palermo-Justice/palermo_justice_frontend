package com.example.palermojustice.view.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
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

        // Add debug log to check fragment parameters
        Log.d("NightPhaseFragment", "Fragment created with canAct=$canAct, playerRole=$playerRole")

        // Check player status directly from Firebase to ensure accuracy
        checkPlayerStatus()
    }

    private fun checkPlayerStatus() {
        if (gameId.isEmpty() || playerId.isEmpty()) {
            Log.e("NightPhaseFragment", "Invalid gameId or playerId")
            return
        }

        val database = FirebaseDatabase.getInstance()
        val playerRef = database.getReference("games")
            .child(gameId)
            .child("players")
            .child(playerId)

        playerRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                val role = snapshot.child("role").getValue(String::class.java)
                val isAlive = snapshot.child("isAlive").getValue(Boolean::class.java) ?: true

                Log.d("NightPhaseFragment", "Player status from Firebase: role=$role, alive=$isAlive")

                // Update local variables with accurate data from Firebase
                playerRole = role

                // Can act only if player is alive AND has a role with night actions
                canAct = isAlive && role?.let {
                    try {
                        Role.valueOf(it).canActAtNight()
                    } catch (e: IllegalArgumentException) {
                        false
                    }
                } ?: false

                Log.d("NightPhaseFragment", "Updated canAct=$canAct after checking Firebase")

                // Setup UI with the most current data
                setupUI()

                // Now check if the player has already performed an action
                checkExistingActions()
            } else {
                Log.e("NightPhaseFragment", "Player snapshot does not exist")
                Toast.makeText(context, "Error: Player data not found", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener { exception ->
            Log.e("NightPhaseFragment", "Failed to get player data: ${exception.message}")
            Toast.makeText(context, "Error loading player data", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupUI() {
        // Set night ambiance and general description
        binding.textViewNightPhaseTitle.text = "Night Phase"
        binding.textViewNightPhaseDescription.text =
            "The town sleeps while special roles perform their actions."

        // Debug log
        Log.d("NightPhaseFragment", "Setting up UI with playerRole=$playerRole, canAct=$canAct")

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
            if (playerRole == null) {
                binding.textViewRoleInstructions.text =
                    "Role not assigned yet. Please wait."
            } else if (!canAct) {
                // Check if player is dead
                val database = FirebaseDatabase.getInstance()
                val playerRef = database.getReference("games")
                    .child(gameId)
                    .child("players")
                    .child(playerId)
                    .child("isAlive")

                playerRef.get().addOnSuccessListener { snapshot ->
                    val isAlive = snapshot.getValue(Boolean::class.java) ?: true

                    if (!isAlive) {
                        binding.textViewRoleInstructions.text =
                            "You are dead and cannot perform any actions."
                    } else if (playerRole == Role.PAESANO.name) {
                        binding.textViewRoleInstructions.text =
                            "As a Paesano, you sleep during the night. Wait for the day to begin."
                    } else if (roleEnum?.canActAtNight() == true) {
                        binding.textViewRoleInstructions.text =
                            "You have a night role but cannot perform actions. Please check game state."
                    } else {
                        binding.textViewRoleInstructions.text =
                            "You cannot perform any actions at night. Wait for the day to begin."
                    }
                }
            }

            // Hide action section if player can't act
            binding.actionSection.visibility = View.GONE
        }
    }

    /**
     * Check if player has already performed an action
     */
    private fun checkExistingActions() {
        if (!canAct || playerRole == null) {
            Log.d("NightPhaseFragment", "Skipping action check: canAct=$canAct, playerRole=$playerRole")
            return
        }

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
        }

        if (actionType == null) {
            Log.d("NightPhaseFragment", "No action type for role $playerRole")
            return
        }

        // Check if player has already performed this action - get phase number FIRST
        val database = FirebaseDatabase.getInstance()
        val gameRef = database.getReference("games").child(gameId)

        // First get the current phase number, THEN check for actions
        gameRef.child("currentPhase").get().addOnSuccessListener { phaseSnapshot ->
            val currentPhaseNumber = phaseSnapshot.getValue(Long::class.java)?.toInt() ?: 1
            Log.d("NightPhaseFragment", "Checking existing actions for phase $currentPhaseNumber")

            val actionsRef = gameRef
                .child("actions")
                .child("night")
                .child(currentPhaseNumber.toString())
                .child(playerId)

            actionsRef.get().addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    // Player has already performed an action
                    hasPerformedAction = true
                    binding.buttonPerformAction.isEnabled = false
                    binding.buttonPerformAction.text = "Action Submitted"
                    binding.textViewActionStatus.text = "You have already performed your night action."
                    binding.textViewActionStatus.visibility = View.VISIBLE

                    // Log that the action was already performed
                    Log.d("NightPhaseFragment", "Player $playerId has already performed a $actionType action")
                } else {
                    // Reset in case the button was disabled previously
                    hasPerformedAction = false
                    binding.buttonPerformAction.isEnabled = true
                    binding.textViewActionStatus.visibility = View.GONE

                    Log.d("NightPhaseFragment", "Player $playerId has not yet performed a $actionType action")
                }
            }.addOnFailureListener { exception ->
                Log.e("NightPhaseFragment", "Failed to check existing action: ${exception.message}")
            }
        }.addOnFailureListener { exception ->
            Log.e("NightPhaseFragment", "Failed to get current phase number: ${exception.message}")
        }
    }

    private fun performRoleAction(role: Role) {
        // Get current phase number for accurate action submission
        val currentPhase = getCurrentPhaseNumber()
        Log.d("NightPhaseFragment", "Performing role action for phase $currentPhase")

        // Determine action type
        val actionType = when (role) {
            Role.MAFIOSO -> ActionType.KILL
            Role.ISPETTORE -> ActionType.INVESTIGATE
            Role.SGARRISTA -> ActionType.PROTECT
            Role.IL_PRETE -> ActionType.BLESS
            else -> {
                Log.e("NightPhaseFragment", "Invalid role for night action: $role")
                Toast.makeText(context, "Cannot perform action with this role", Toast.LENGTH_SHORT).show()
                return
            }
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

        // Read directly from Firebase to get the latest phase number
        val database = FirebaseDatabase.getInstance()
        val gameRef = database.getReference("games").child(gameId)

        gameRef.child("currentPhase").get().addOnSuccessListener { dataSnapshot ->
            if (dataSnapshot.exists()) {
                val value = dataSnapshot.getValue(Long::class.java)
                if (value != null) {
                    phaseNumber = value.toInt()
                    Log.d("NightPhaseFragment", "Current phase number from Firebase: $phaseNumber")
                }
            }
        }.addOnFailureListener { exception ->
            Log.e("NightPhaseFragment", "Error getting phase number: ${exception.message}")
        }

        return phaseNumber
    }

    /**
     * On resume, always check if an action was performed when returning from RoleActionActivity
     */
    override fun onResume() {
        super.onResume()
        // Always recheck player status and existing actions when returning to the fragment
        checkPlayerStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null

        // Remove Firebase listeners
        gameListener?.let { FirebaseManager.removeGameListener(gameId, it) }
    }
}