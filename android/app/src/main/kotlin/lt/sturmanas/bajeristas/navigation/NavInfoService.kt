package lt.sturmanas.bajeristas.navigation

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service that receives real-time Turn-by-Turn (TBT) updates from the Navigation SDK.
 *
 * The SDK binds to this service and sends [NavInfo] bundles via a [Messenger].
 * We decode these bundles and expose the latest [NavInfo] through a static [StateFlow]
 * for [GoogleNavigationEngine] to consume.
 */
class NavInfoService : Service() {

    companion object {
        private const val TAG = "KentasNavInfoService"

        private val _navInfoFlow = MutableStateFlow<NavInfo?>(null)
        /** Observable stream of the latest TBT information from the SDK. */
        val navInfoFlow: StateFlow<NavInfo?> = _navInfoFlow.asStateFlow()
    }

    private val tbtManager = TurnByTurnManager.createInstance()

    /**
     * Handler for messages from the Navigation SDK.
     * Receives [TurnByTurnManager.MSG_NAV_INFO] with a bundle containing [NavInfo].
     */
    private val incomingHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == TurnByTurnManager.MSG_NAV_INFO) {
                val bundle = msg.data ?: return
                try {
                    val navInfo = tbtManager.readNavInfoFromBundle(bundle)
                    Log.d(TAG, "NavInfo received: maneuver=${navInfo.currentStep?.maneuver} " +
                               "dist=${navInfo.distanceToCurrentStepMeters}m")
                    _navInfoFlow.value = navInfo
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode NavInfo bundle", e)
                }
            }
        }
    }

    private val messenger = Messenger(incomingHandler)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NavInfoService created")
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "SDK bound to NavInfoService")
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "SDK unbound from NavInfoService")
        return super.onUnbind(intent)
    }
}
