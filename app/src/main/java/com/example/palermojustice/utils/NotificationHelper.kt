package com.example.palermojustice.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.palermojustice.R
import com.example.palermojustice.model.GameState
import com.example.palermojustice.view.activities.GameActivity

/**
 * Helper class to manage notifications for game events.
 * Handles creating notification channels and sending notifications to players.
 */
class NotificationHelper(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "palermo_justice_channel"
        private const val NOTIFICATION_ID = 1001
    }

    init {
        // Create notification channel for Android 8.0+
        createNotificationChannel()
    }

    /**
     * Create notification channel for Android Oreo and above
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Palermo Justice"
            val descriptionText = "Notifications for Mafia game events"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            // Register the channel with the system
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Send notification for a game event
     *
     * @param gameId ID of the game
     * @param title Notification title
     * @param message Notification message
     * @param gameState Current game state
     */
    fun sendGameNotification(gameId: String, title: String, message: String, gameState: GameState) {
        // Create intent to open the game activity when notification is tapped
        val intent = Intent(context, GameActivity::class.java).apply {
            putExtra("GAME_ID", gameId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        // Create pending intent
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build notification
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        // Send notification if permissions are granted
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // Handle permission not granted
            // In a real app, you should request notification permissions
        }
    }

    fun notifyYourTurn(gameId: String, gameState: GameState) {
        val title = when (gameState) {
            GameState.NIGHT -> "Night Phase"
            GameState.DAY_VOTING -> "Voting Phase"
            else -> "Your Turn"
        }

        val message = when (gameState) {
            GameState.NIGHT -> "It's time to perform your night action!"
            GameState.DAY_VOTING -> "Time to vote on who to eliminate!"
            else -> "It's your turn to act!"
        }

        sendGameNotification(gameId, title, message, gameState)
    }

    fun notifyPhaseChange(gameId: String, gameState: GameState) {
        val title = when (gameState) {
            GameState.DAY_DISCUSSION -> "Day Discussion"
            GameState.NIGHT -> "Night Has Fallen"
            GameState.DAY_VOTING -> "Voting Time"
            GameState.EXECUTION_RESULT -> "Execution Results"
            GameState.NIGHT_RESULTS -> "Night Results"
            GameState.GAME_OVER -> "Game Over"
            else -> "Phase Change"
        }

        val message = when (gameState) {
            GameState.DAY_DISCUSSION -> "Time to discuss who might be the Mafia!"
            GameState.NIGHT -> "Night has fallen. Special roles can perform their actions."
            GameState.DAY_VOTING -> "Time to vote on who to eliminate!"
            GameState.EXECUTION_RESULT -> "See the results of the town's vote."
            GameState.NIGHT_RESULTS -> "See what happened during the night."
            GameState.GAME_OVER -> "The game has ended. Check who won!"
            else -> "A new phase has begun."
        }

        sendGameNotification(gameId, title, message, gameState)
    }

    fun notifyPlayerEliminated(gameId: String, playerName: String) {
        sendGameNotification(
            gameId,
            "Player Eliminated",
            "$playerName has been eliminated from the game!",
            GameState.EXECUTION_RESULT
        )
    }

    fun notifyGameOver(gameId: String, winningTeam: String) {
        val message = if (winningTeam == "MAFIA") {
            "The Mafia has taken control of the town!"
        } else {
            "The Citizens have eliminated all Mafia members!"
        }

        sendGameNotification(gameId, "Game Over", message, GameState.GAME_OVER)
    }
}