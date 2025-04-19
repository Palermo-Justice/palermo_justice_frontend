package com.example.palermojustice

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.palermojustice.view.activities.CreateGameActivity
import com.example.palermojustice.view.activities.JoinGameActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Button to create game
        findViewById<Button>(R.id.buttonCreateGame).setOnClickListener {
            startActivity(Intent(this, CreateGameActivity::class.java))
        }

        // Button to join game
        findViewById<Button>(R.id.buttonJoinGame).setOnClickListener {
            startActivity(Intent(this, JoinGameActivity::class.java))
        }
    }
}