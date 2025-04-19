package com.example.palermojustice.controller

import com.example.palermojustice.model.ActionType
import com.example.palermojustice.model.RoleAction
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
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
                    executedId = null
                }

                callback(voteCounts, executedId)
            } else {
                // No votes
                callback(emptyMap(), null)
            }
        }.addOnFailureListener {
            // Return empty result on failure
            callback(emptyMap(), null)
        }
    }

    /**
     * Check if all alive players have voted
     */
    fun checkAllVoted(
        phaseNumber: Int,
        callback: (Boolean, Int, Int) -> Unit
    ) {
        // Get all alive players
        gameRef.child("players").get().addOnSuccessListener { playersSnapshot ->
            // Count alive players
            var alivePlayers = 0
            playersSnapshot.children.forEach { playerSnapshot ->
                val isAlive = playerSnapshot.child("isAlive").getValue(Boolean::class.java) ?: true
                if (isAlive) {
                    alivePlayers++
                }
            }

            // Count votes
            votesRef.child(phaseNumber.toString()).get().addOnSuccessListener { votesSnapshot ->
                val voteCount = votesSnapshot.childrenCount.toInt()
                callback(voteCount >= alivePlayers, voteCount, alivePlayers)
            }.addOnFailureListener {
                callback(false, 0, alivePlayers)
            }
        }.addOnFailureListener {
            callback(false, 0, 0)
        }
    }

    /**
     * Listen for vote changes in real-time
     */
    fun listenForVotes(
        phaseNumber: Int,
        onVotesChanged: (Map<String, String>) -> Unit
    ) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val votes = mutableMapOf<String, String>()

                if (snapshot.exists()) {
                    for (voteSnapshot in snapshot.children) {
                        val voterId = voteSnapshot.key ?: continue
                        val targetId = voteSnapshot.child("targetPlayerId").getValue(String::class.java) ?: continue
                        votes[voterId] = targetId
                    }
                }

                onVotesChanged(votes)
            }

            override fun onCancelled(error: DatabaseError) {
                // Handle error
            }
        }

        votesRef.child(phaseNumber.toString()).addValueEventListener(listener)
        listeners.add(listener)
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