package com.example.open_script

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.open_script.ui.theme.Open_scriptTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setOnExitAnimationListener { provider ->
            provider.view.animate().alpha(0f).setDuration(180L).withEndAction { provider.remove() }.start()
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(15, 23, 42)),
        )
        setContent {
            Open_scriptTheme { OpenScriptStartup(showIntroduction = savedInstanceState == null) }
        }
    }
}
