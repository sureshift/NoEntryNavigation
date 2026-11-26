package app.noentrynav

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    private val vm: NavViewModel by viewModels()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) vm.startLocation()
    }

    private fun handleIntent(i: android.content.Intent?) {
        if (i?.action == android.content.Intent.ACTION_SEND) {
            i.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { vm.handleShared(it) }
            i.action = null
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent { AppTheme { MainScreen(vm) } }
        handleIntent(intent)
        permLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }
}
