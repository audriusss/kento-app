package lt.sturmanas.bajeristas.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import lt.sturmanas.bajeristas.BuildConfig
import lt.sturmanas.bajeristas.voice.pipeline.ContinuousMicrophonePoc
import lt.sturmanas.bajeristas.voice.pipeline.OpenAiTranscriptionClient

/**
 * DEBUG-only floating panel for starting/stopping [ContinuousMicrophonePoc].
 *
 * Rendered only when [BuildConfig.DEBUG] is `true`.  The panel sits in the
 * bottom-right corner and does not interfere with the production driving UI.
 *
 * This composable owns the [ContinuousMicrophonePoc] lifecycle: it is created
 * on first composition and stopped via [DisposableEffect] when the composable
 * leaves the composition.
 *
 * ## How to use
 * 1. Build a debug APK.
 * 2. Tap "▶ Start PoC" to begin continuous listening.
 * 3. Speak in Lithuanian.
 * 4. Watch Logcat with filter `MicPoc` for the full event sequence.
 * 5. Tap "■ Stop PoC" to tear down cleanly.
 */
@Composable
fun PocDebugOverlay() {
    // Guard: release builds must not contain any PoC references.
    if (!BuildConfig.DEBUG) return

    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }

    val poc = remember {
        ContinuousMicrophonePoc(
            context = context.applicationContext,
            transcriptionClient = OpenAiTranscriptionClient(
                apiKey = BuildConfig.OPENAI_API_KEY,
            ),
        )
    }

    // Stop the PoC when this composable is removed from the composition tree.
    DisposableEffect(poc) {
        onDispose { poc.stop() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .background(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = "VAD PoC  [DEBUG]",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFCC00),
            )

            Spacer(modifier = Modifier.height(6.dp))

            Button(
                onClick = {
                    if (running) {
                        poc.stop()
                        running = false
                    } else {
                        poc.start()
                        running = true
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Color(0xFFCC2222) else Color(0xFF22AA44),
                ),
            ) {
                Text(
                    text = if (running) "■ Stop PoC" else "▶ Start PoC",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (running) "● Recording" else "○ Idle",
                style = MaterialTheme.typography.labelSmall,
                color = if (running) Color(0xFF88FF88) else Color.Gray,
            )
        }
    }
}
