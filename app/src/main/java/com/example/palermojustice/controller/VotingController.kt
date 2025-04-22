package com.example.palermojustice.controller

import android.util.Log
import com.example.palermojustice.model.ActionType
import com.example.palermojustice.model.RoleAction
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Controller for managing voting during day phases.
 * Handles vote submission, tallying, and determining execution results.
 */
class VotingController(private val gameId: String) {

    // Firebase references
    private val database = FirebaseDatabase.getInstance()
    private val gameRef = database.getReference("games").child(gameId)
    private val votesRef = gameRef.child("actions").child("day")
    private val listeners = mutableListOf<ValueEventListener>()

    /**
     * Submit a vote
     */
    fun submitVote(
        voterId: String,
        targetId: String,
        phaseNumber: Int,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        // Create a vote action
        val voteAction = RoleAction(
            actionType = ActionType.VOTE,
            sourcePlayerId = voterId,
            targetPlayerId = targetId,
            phaseNumber = phaseNumber
        )

        // Store vote in Firebase
        votesRef.child(phaseNumber.toString()).child(voterId).setValue(voteAction.toMap())
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { exception ->
                onFailure(exception)
            }
    }

    /**
     * Tally votes for a specific phase
     */
    fun tallyVotes(
        phaseNumber: Int,
        callback: (Map<String, Int>, String?) -> Unit
    ) {
        Log.d("VotingController", "Tallying votes for phase $phaseNumber")

        votesRef.child(phaseNumber.toString()).get().addOnSuccessListener { snapshot ->
            val voteCounts = mutableMapOf<String, Int>()

            if (snapshot.exists()) {
                // Count votes for each target
                for (voteSnapshot in snapshot.children) {
                    val targetId = voteSnapshot.child("targetPlayerId").getValue(String::class.java)
                    if (targetId != null) {
                        voteCounts[targetId] = (voteCounts[targetId] ?: 0) + 1
                    }
                }

                Log.d("VotingController", "Raw vote counts: $voteCounts")

                // Determine who got the most votes
                var maxVotes = 0
                var executedId: String? = null
                var tie = false

                voteCounts.forEach { (targetId, count) ->
                    when {
                        count > maxVotes -> {
                            maxVotes = count
                            executedId = targetId
                            tie = false
                        }
                        count == maxVotes -> {
                            tie = true
                        }
                    }
                }

                // In case of a tie, no one is executed
                if (tie) {
                    Log.d("VotingController", "Tied vote, no execution")
                    executedId = null
                } else {
                    Log.d("VotingController", "Most votes ($maxVotes) for player $executedId")
                }

                callback(voteCounts, executedId)
            } else {
                // No votes
                Log.d("VotingController", "No votes found for phase $phaseNumber")
                callback(emptyMap(), null)
            }
        }.addOnFailureListener { exception ->
            // Return empty result on failure
            Log.e("VotingController", "Error tallying votes: ${exception.message}")
            callback(emptyMap(), null)
        }
    }

    /**
     * Clean up listeners when game is over
     */
    fun cleanup() {
        listeners.forEach { listener ->
            votesRef.removeEventListener(listener)
        }
        listeners.clear()
    }
}