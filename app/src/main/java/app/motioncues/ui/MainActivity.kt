package app.motioncues.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.motioncues.ui.theme.MotionCuesTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MotionCuesTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Starting a foreground service always succeeds while the app is visible, so this
        // recovers any start the tile or boot receiver could not make from the background,
        // and starts the service right after the user grants the overlay permission.
        viewModel.syncService()
    }
}
