package com.example.palermojustice.controller

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
        // Validate the action is appropriate for the role
        validateAction(action) { isValid, message ->
            if (isValid) {
                // Store the action in the appropriate location based on phase
                val phaseType = if (action.actionType == ActionType.VOTE) "day" else "night"
                val actionPath = "$phaseType/${action.phaseNumber}/${action.sourcePlayerId}"

                actionsRef.child(actionPath).setValue(action.toMap())
                    .addOnSuccessListener {
                        onSuccess()
                    }
                    .addOnFailureListener { exception ->
                        onFailure(exception)
                    }
            } else {
                onFailure(Exception(message))
            }
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

                    // Check if player is alive
                    if (!isAlive) {
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
                            callback(false, "You cannot target yourself with this action")
                            return@addOnSuccessListener
                        }

                        // Check if target is alive
                        gameRef.child("players").child(action.targetPlayerId).get()
                            .addOnSuccessListener { targetSnapshot ->
                                if (targetSnapshot.exists()) {
                                    val targetIsAlive = targetSnapshot.child("isAlive").getValue(Boolean::class.java) ?: true

                                    if (!targetIsAlive) {
                                        callback(false, "You cannot target a dead player")
                                        return@addOnSuccessListener
                                    }

                                    // All checks passed, action is valid
                                    callback(true, "Action is valid")
                                } else {
                                    callback(false, "Target player does not exist")
                                }
                            }
                            .addOnFailureListener { exception ->
                                callback(false, "Failed to validate target: ${exception.message}")
                            }
                    } else {
                        callback(false, "This action is not valid for your role")
                    }
                }
            }
    }
}