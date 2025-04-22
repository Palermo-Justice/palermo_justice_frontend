package com.example.palermojustice.view.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.palermojustice.databinding.ActivityTutorialBinding

class TutorialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTutorialBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTutorialBinding.inflate(layoutInflater)
        setContentView(binding.root)

    }
}
