package com.minavigatering.probe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.minavigatering.probe.transport.ProbeRuntime

/**
 * Single activity: the daily navigation page, with the connection diagnostics page one tap
 * behind its footer link.
 *
 * The activity owns only the transport lifecycle: one transport per process, shared with the
 * foreground service so both use the same refreshed node and the same inbound listener
 * registration. Every diagnostic block lives in [ProbeScreen].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One transport per process, shared with the foreground service so both use the
        // same refreshed node and the same inbound listener registration.
        val transport = ProbeRuntime.transport(applicationContext)
        // Diagnostics flow into the shared session so the page can read host service,
        // node, permission and the last SDK error (work order W-625).
        transport.attachDiagnostics(ProbeRuntime.session)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NavigationScreen(transport)
                }
            }
        }
    }

    override fun onDestroy() {
        // Leaving the page must not leave a stacked service-connection listener behind.
        ProbeRuntime.transport(applicationContext).unregisterDiagnostics()
        super.onDestroy()
    }
}
