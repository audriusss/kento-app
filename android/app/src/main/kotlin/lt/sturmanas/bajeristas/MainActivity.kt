package lt.sturmanas.bajeristas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.sturmanas.bajeristas.navigation.NavigationController
import lt.sturmanas.bajeristas.personality.PersonaPrompts
import lt.sturmanas.bajeristas.personality.SessionConfig
import lt.sturmanas.bajeristas.safety.SafetyController
import lt.sturmanas.bajeristas.ui.NavigationScreen
import lt.sturmanas.bajeristas.ui.StartScreen
import lt.sturmanas.bajeristas.ui.theme.SturmanasTheme
import lt.sturmanas.bajeristas.voice.AudioController
import lt.sturmanas.bajeristas.voice.VoiceSessionController

class MainActivity : ComponentActivity() {

    // Controllers created here; no DI framework in V1.
    private val navigationController = NavigationController()
    private val safetyController = SafetyController()
    private val voiceSessionController = VoiceSessionController()
    private val audioController = AudioController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SturmanasTheme {
                SturmanasApp(
                    navigationController = navigationController,
                    safetyController = safetyController,
                    voiceSessionController = voiceSessionController,
                    audioController = audioController,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioController.release()
        voiceSessionController.stopSession()
    }
}

// ── Root composable ───────────────────────────────────────────────────────────

@Composable
private fun SturmanasApp(
    navigationController: NavigationController,
    safetyController: SafetyController,
    voiceSessionController: VoiceSessionController,
    audioController: AudioController,
) {
    val navState by navigationController.state.collectAsStateWithLifecycle()
    var isNavigating by remember { mutableStateOf(false) }
    var sessionConfig by remember { mutableStateOf(SessionConfig()) }
    var isMuted by remember { mutableStateOf(false) }
    var aiStatusMessage by remember { mutableStateOf("") }

    val permission = safetyController.getPermission(navState)

    // Interrupt AI audio whenever safety rules change — safety always wins.
    if (safetyController.shouldInterruptAudio(navState) && audioController.isAiPlaying) {
        audioController.interruptAiAudio()
        aiStatusMessage = "Navigacija perėmė garsą"
    }

    if (!isNavigating) {
        StartScreen(
            onStartNavigation = { destination, config ->
                sessionConfig = config
                navigationController.startNavigation(destination)
                // Phase 3: voiceSessionController.startSession(
                //     PersonaPrompts.systemPrompt(config)
                // )
                isNavigating = true
                aiStatusMessage = ""
            },
        )
    } else {
        NavigationScreen(
            navigationState = navState,
            conversationPermission = permission,
            aiStatusMessage = aiStatusMessage,
            isMuted = isMuted,
            onMicPress = {
                // Phase 3: start AudioRecord capture → voiceSessionController.sendAudio(…)
                aiStatusMessage = "Klausau… (3 fazė)"
            },
            onMicRelease = {
                // Phase 3: voiceSessionController.commitAndRespond()
                aiStatusMessage = ""
            },
            onMuteToggle = {
                isMuted = !isMuted
                if (isMuted) {
                    audioController.interruptAiAudio()
                    aiStatusMessage = "AI nutildytas"
                } else {
                    aiStatusMessage = ""
                }
            },
            onEnableStandardVoice = {
                // Emergency fallback — always functional regardless of AI state.
                navigationController.enableStandardVoice()
                voiceSessionController.stopSession()
                audioController.interruptAiAudio()
                isMuted = true
                aiStatusMessage = "Įprastas navigacijos balsas įjungtas"
            },
            onStopNavigation = {
                navigationController.stopNavigation()
                voiceSessionController.stopSession()
                audioController.release()
                isNavigating = false
                isMuted = false
                aiStatusMessage = ""
            },
        )
    }
}
