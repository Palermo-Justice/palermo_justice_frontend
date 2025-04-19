package com.example.palermojustice.controller

import android.util.Log
import com.example.palermojustice.model.ActionType
import com.example.palermojustice.model.Game
import com.example.palermojustice.model.Role
import com.example.palermojustice.model.RoleAction
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Controller responsible for managing role-specific actions.
 * Handles the submission and validation of role actions during night phase.
 */
class RoleController(private val gameId: String) {

    // Firebase references
    private val database = FirebaseDatabase.getInstance()
    private val gameRef = database.getReference("games").child(gameId)
    private val actionsRef = gameRef.child("actions")
    private val listeners = mutableListOf<ValueEventListener>()

    /**
     * Submit a role action to Firebase
     */
    fun submitAction(
        action: RoleAction,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        // First check if player has already performed an action this phase
        checkExistingAction(action.sourcePlayerId, action.phaseNumber, action.actionType) { hasExistingAction ->
            if (hasExistingAction) {
                onFailure(Exception("You have already performed an action this phase"))
                return@checkExistingAction
            }

            // Continue with action validation
            validateAction(action) { isValid, message ->
                if (isValid) {
                    // Store the action in the appropriate location based on phase
                    val phaseType = if (action.actionType == ActionType.VOTE) "day" else "night"
                    val actionPath = "$phaseType/${action.phaseNumber}/${action.sourcePlayerId}"

                    Log.d("RoleController", "Storing action: $action at path: $actionPath")

                    actionsRef.child(actionPath).setValue(action.toMap())
                        .addOnSuccessListener {
                            Log.d("RoleController", "Action stored successfully")
                            onSuccess()
                        }
                        .addOnFailureListener { exception ->
                            Log.e("RoleController", "Failed to store action: ${exception.message}")
                            onFailure(exception)
                        }
                } else {
                    Log.e("RoleController", "Action validation failed: $message")
                    onFailure(Exception(message))
                }
            }
        }
    }

    /**
     * Check if player has already performed an action this phase
     */
    private fun checkExistingAction(
        playerId: String,
        phaseNumber: Int,
        actionType: ActionType,
        callback: (Boolean) -> Unit
    ) {
        // Determine action path based on action type
        val phaseType = if (actionType == ActionType.VOTE) "day" else "night"
        val actionPath = "$phaseType/$phaseNumber/$playerId"

        // Check if action exists
        actionsRef.child(actionPath).get().addOnSuccessListener { snapshot ->
            val hasExistingAction = snapshot.exists()
            Log.d("RoleController", "Player $playerId has existing $actionType action: $hasExistingAction")
            callback(hasExistingAction)
        }.addOnFailureListener { exception ->
            Log.e("RoleController", "Failed to check existing action: ${exception.message}")
            // Default to false on error to avoid blocking player actions
            callback(false)
        }
    }

    /**
     * Validate that the action is appropriate for the player's role
     */
    private fun validateAction(action: RoleAction, callback: (Boolean, String) -> Unit) {
        // Get the player data
        gameRef.child("players").child(action.sourcePlayerId).get()
            .addOnSuccessListener { playerSnapshot ->
                if (playerSnapshot.exists()) {
                    val role = playerSnapshot.child("role").getValue(String::class.java)
                    val isAlive = playerSnapshot.child("isAlive").getValue(Boolean::class.java) ?: true

                    Log.d("RoleController", "Validating action for player ${action.sourcePlayerId}, role=$role, alive=$isAlive")

                    // Check if player is alive
                    if (!isAlive) {
                        Log.d("RoleController", "Player ${action.sourcePlayerId} is dead and cannot perform actions")
                        callback(false, "Dead players cannot perform actions")
                        return@addOnSuccessListener
                    }

                    // Check if action type matches role
                    val validRoles = action.actionType.getValidRoles().map { it.name }
                    if (role != null && role in validRoles) {
                        // Validate target is not self (except for certain roles like Protector)
                        if (action.sourcePlayerId == action.targetPlayerId &&
                            action.actionType != ActionType.PROTECT &&
                            action.actionType != ActionType.BLESS) {
                            Log.d("RoleController", "Player cannot target self with this action")
                            callback(false, "You cannot target yourself with this action")
                            return@addOnSuccessListener
                        }

                        // Check if target is alive
                        gameRef.child("players").child(action.targetPlayerId).get()
                            .addOnSuccessListener { targetSnapshot ->
                                if (targetSnapshot.exists()) {
                                    val targetIsAlive = targetSnapshot.child("isAlive").getValue(Boolean::class.java) ?: true

                                    Log.d("RoleController", "Target player ${action.targetPlayerId} alive=$targetIsAlive")

                                    if (!targetIsAlive) {
                                        callback(false, "You cannot target a dead player")
                                        return@addOnSuccessListener
                                    }

                                    // All checks passed, action is valid
                                    Log.d("RoleController", "Action is valid")
                                    callback(true, "Action is valid")
                                } else {
                                    Log.d("RoleController", "Target player does not exist")
                                    callback(false, "Target player does not exist")
                                }
                            }
                            .addOnFailureListener { exception ->
                                Log.e("RoleController", "Failed to validate target: ${exception.message}")
                                callback(false, "Failed to validate target: ${exception.message}")
                            }
                    } else {
                        Log.d("RoleController", "Action type ${action.actionType} not valid for role $role")
                        callback(false, "This action is not valid for your role")
                    }
                } else {
                    Log.d("RoleController", "Player ${action.sourcePlayerId} does not exist")
                    callback(false, "Player does not exist")
                }
            }
            .addOnFailureListener { exception ->
                Log.e("RoleController", "Failed to get player data: ${exception.message}")
                callback(false, "Failed to get player data: ${exception.message}")
            }
    }
}