package dev.andrewarrow.cubacadabra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.andrewarrow.cubacadabra.ui.CubacadabraApp
import dev.andrewarrow.cubacadabra.ui.CubacadabraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CubacadabraTheme { CubacadabraApp() } }
    }
}
