package lt.sturmanas.bajeristas

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import lt.sturmanas.bajeristas.voice.TtsManager

/**
 * Survives Activity recreation (screen rotation, theme changes, etc.).
 *
 * Holding [TtsManager] here ensures the TTS engine is initialised exactly once per
 * user session and never torn down by a configuration change.
 *
 * [TtsManager.initialize] is called in the constructor so the engine is ready as
 * early as possible — typically before the first UI frame is drawn.
 *
 * [onCleared] is the single correct place to shut the engine down: it fires when
 * the user actually leaves the app (back-press to root), not on every rotation.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val ttsManager: TtsManager = TtsManager(application).also { it.initialize() }

    override fun onCleared() {
        super.onCleared()
        ttsManager.release()
    }
}
