package dev.andrewarrow.cubacadabra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import dev.andrewarrow.cubacadabra.app.AppViewModel
import dev.andrewarrow.cubacadabra.game.GameViewModel
import dev.andrewarrow.cubacadabra.ui.CubacadabraApp
import dev.andrewarrow.cubacadabra.ui.CubacadabraTheme

class MainActivity : ComponentActivity() {
    private lateinit var appModel: AppViewModel
    private lateinit var gameModel: GameViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameModel = ViewModelProvider(this)[GameViewModel::class.java]
        appModel = ViewModelProvider(this)[AppViewModel::class.java]
        appModel.attachActivity(this)
        gameModel.onAccountRequested = appModel::requestSignIn
        gameModel.onSignOutRequested = appModel::signOut
        gameModel.onSessionRejected = appModel::gameSessionRejected
        enableEdgeToEdge()
        setContent { CubacadabraTheme { CubacadabraApp(gameModel, appModel) } }
    }

    override fun onResume() {
        super.onResume()
        if (::appModel.isInitialized) appModel.refreshAuthentication()
    }

    override fun onStop() {
        if (::gameModel.isInitialized) gameModel.pauseGame()
        super.onStop()
    }

    override fun onDestroy() {
        if (::appModel.isInitialized) appModel.detachActivity(this)
        super.onDestroy()
    }
}
