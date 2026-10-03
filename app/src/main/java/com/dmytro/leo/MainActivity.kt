package com.dmytro.leo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dmytro.leo.ui.ChatScreen
import com.dmytro.leo.ui.theme.LeoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LeoTheme {
                ChatScreen()
            }
        }
    }
}
