package com.rigstudio.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rigstudio.app.ui.RigStudioApp
import com.rigstudio.app.ui.theme.RigStudioTheme

/**
 * The single activity. Everything else is Compose.
 *
 * Edge-to-edge is enabled so the dark shell runs to the physical edges of the screen; each screen's
 * `Scaffold` consumes the system bar insets itself.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A character sheet opened from Files / a gallery starts on the import screen with the
        // sheet already picked.
        val sheetUri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        setContent {
            RigStudioTheme {
                RigStudioApp(initialSheetUri = sheetUri)
            }
        }
    }
}
