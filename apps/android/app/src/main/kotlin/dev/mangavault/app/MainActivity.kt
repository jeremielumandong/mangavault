package dev.mangavault.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.mangavault.app.ui.MangavaultRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MangavaultApp).container
        setContent { MangavaultRoot(container) }
    }

    override fun onResume() {
        super.onResume()
        (application as MangavaultApp).container.requestSync(300)
    }
}
