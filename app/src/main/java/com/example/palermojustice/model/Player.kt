package com.example.palermojustice.model

data class Player(
    val id: String,
    val name: String,
    var role: String? = null,
    var isAlive: Boolean = true
) {
    // Convert Player object to Map for Firebase
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "name" to name,
            "role" to role,
            "isAlive" to isAlive
        )
    }
}