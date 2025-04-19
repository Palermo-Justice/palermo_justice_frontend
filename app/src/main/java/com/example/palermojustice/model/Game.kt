package com.example.palermojustice.model

import com.google.firebase.database.DataSnapshot

data class Game(
    val id: String = "",
    val hostId: String = "",
    val gameCode: String = "",
    val status: String = "lobby", // lobby, day, night, finished
    val players: Map<String, Player> = mapOf()
) {
    companion object {
        // Create Game object from Firebase Realtime Database snapshot
        fun fromSnapshot(id: String, snapshot: DataSnapshot): Game {
            val hostId = snapshot.child("hostId").getValue(String::class.java) ?: ""
            val gameCode = snapshot.child("gameCode").getValue(String::class.java) ?: ""
            val status = snapshot.child("status").getValue(String::class.java) ?: "lobby"

            val players = mutableMapOf<String, Player>()
            val playersSnapshot = snapshot.child("players")

            for (playerSnapshot in playersSnapshot.children) {
                val playerId = playerSnapshot.key ?: continue
                val name = playerSnapshot.child("name").getValue(String::class.java) ?: continue
                val role = playerSnapshot.child("role").getValue(String::class.java)
                val isAlive = playerSnapshot.child("isAlive").getValue(Boolean::class.java) ?: true

                players[playerId] = Player(
                    id = playerId,
                    name = name,
                    role = role,
                    isAlive = isAlive
                )
            }

            return Game(
                id = id,
                hostId = hostId,
                gameCode = gameCode,
                status = status,
                players = players
            )
        }
    }
}