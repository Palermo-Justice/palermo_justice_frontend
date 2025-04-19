package com.example.palermojustice.firebase

import android.content.Context
import com.example.palermojustice.model.Game
import com.example.palermojustice.model.Player
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlin.random.Random

object FirebaseManager {
    private val database = FirebaseDatabase.getInstance()
    private val gamesRef = database.getReference("games")
    private val auth = FirebaseAuth.getInstance()

    // Get current user ID or generate a guest ID
    fun getCurrentUserId(context: Context): String {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        var userId = prefs.getString("user_id", null)

        if (userId == null) {
            userId = "guest_${Random.nextInt(100000, 999999)}"
            prefs.edit().putString("user_id", userId).apply()
        }

        return userId
    }

    // Create a new game room
    fun createGame(hostPlayer: Player, onSuccess: (gameId: String, gameCode: String) -> Unit, onFailure: (Exception) -> Unit) {
        // Generate a random 6-digit code
        val gameCode = (100000..999999).random().toString()

        // Create a new game entry
        val gameId = gamesRef.push().key ?: return onFailure(Exception("Failed to create game key"))

        // Set up the game data
        val game = HashMap<String, Any>()
        game["hostId"] = hostPlayer.id
        game["gameCode"] = gameCode
        game["status"] = "lobby"
        game["createdAt"] = ServerValue.TIMESTAMP

        // Add the host as the first player
        val players = HashMap<String, Any>()
        players[hostPlayer.id] = hostPlayer.toMap()
        game["players"] = players

        // Save to database
        gamesRef.child(gameId).setValue(game)
            .addOnSuccessListener {
                onSuccess(gameId, gameCode)
            }
            .addOnFailureListener { exception ->
                onFailure(exception)
            }
    }

    // Join a game by game code
    fun joinGameByCode(gameCode: String, player: Player, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        // Query for the game with this code
        gamesRef.orderByChild("gameCode").equalTo(gameCode)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        // Get the game ID (there should be only one game with this code)
                        val gameSnapshot = snapshot.children.first()
                        val gameId = gameSnapshot.key ?: return onFailure(Exception("Invalid game data"))

                        // Check if the game is in lobby state
                        val status = gameSnapshot.child("status").getValue(String::class.java)
                        if (status != "lobby") {
                            return onFailure(Exception("Game is no longer in lobby state"))
                        }

                        // Add the player to the game
                        gamesRef.child(gameId).child("players").child(player.id).setValue(player.toMap())
                            .addOnSuccessListener {
                                onSuccess(gameId)
                            }
                            .addOnFailureListener { exception ->
                                onFailure(exception)
                            }
                    } else {
                        onFailure(Exception("Game not found"))
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    onFailure(Exception(error.message))
                }
            })
    }

    // Listen for real-time updates to a specific game
    fun listenToGame(gameId: String, onUpdate: (Game) -> Unit, onError: (Exception) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    try {
                        val game = Game.fromSnapshot(gameId, snapshot)
                        onUpdate(game)
                    } catch (e: Exception) {
                        onError(e)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                onError(Exception(error.message))
            }
        }

        gamesRef.child(gameId).addValueEventListener(listener)
        return listener
    }

    // Leave a game
    fun leaveGame(gameId: String, playerId: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        gamesRef.child(gameId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val hostId = snapshot.child("hostId").getValue(String::class.java)
                    val playersSnapshot = snapshot.child("players")
                    val playerCount = playersSnapshot.childrenCount.toInt()

                    // If player is host and there are other players, transfer host role
                    if (hostId == playerId && playerCount > 1) {
                        // Find first player that's not the host
                        val newHostId = playersSnapshot.children
                            .map { it.key }
                            .first { it != playerId }

                        val updates = HashMap<String, Any?>()
                        updates["hostId"] = newHostId
                        updates["players/$playerId"] = null

                        gamesRef.child(gameId).updateChildren(updates)
                            .addOnSuccessListener { onSuccess() }
                            .addOnFailureListener { onFailure(it) }
                    }
                    // If player is host and no other players, delete the game
                    else if (hostId == playerId && playerCount <= 1) {
                        gamesRef.child(gameId).removeValue()
                            .addOnSuccessListener { onSuccess() }
                            .addOnFailureListener { onFailure(it) }
                    }
                    // If player is not host, just remove them from the game
                    else {
                        gamesRef.child(gameId).child("players").child(playerId).removeValue()
                            .addOnSuccessListener { onSuccess() }
                            .addOnFailureListener { onFailure(it) }
                    }
                } else {
                    onFailure(Exception("Game not found"))
                }
            }

            override fun onCancelled(error: DatabaseError) {
                onFailure(Exception(error.message))
            }
        })
    }

    // Stop listening to a game
    fun removeGameListener(gameId: String, listener: ValueEventListener) {
        gamesRef.child(gameId).removeEventListener(listener)
    }
}