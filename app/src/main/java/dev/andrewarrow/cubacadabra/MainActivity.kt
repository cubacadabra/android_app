package dev.andrewarrow.cubacadabra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import dev.andrewarrow.cubacadabra.game.GameViewModel
import dev.andrewarrow.cubacadabra.ui.CubacadabraApp
import dev.andrewarrow.cubacadabra.ui.CubacadabraTheme

class MainActivity : ComponentActivity() {
    private lateinit var gameModel: GameViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameModel = ViewModelProvider(this)[GameViewModel::class.java]
        gameModel.attachActivity(this)
        enableEdgeToEdge()
        setContent { CubacadabraTheme { CubacadabraApp(gameModel) } }
    }

    override fun onResume() {
        super.onResume()
        if (::gameModel.isInitialized) gameModel.refreshAuthentication()
    }

    override fun onDestroy() {
        if (::gameModel.isInitialized) gameModel.detachActivity(this)
        super.onDestroy()
    }
}
