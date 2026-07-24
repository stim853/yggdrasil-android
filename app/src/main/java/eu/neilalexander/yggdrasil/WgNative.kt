package eu.neilalexander.yggdrasil

object WgNative {
    private var wgTurnOn: java.lang.reflect.Method? = null
    private var wgTurnOff: java.lang.reflect.Method? = null
    private var loaded = false

    fun init() {
        if (loaded) return
        try {
            System.loadLibrary("wg-go")
            loaded = true
            val c = Class.forName("com.wireguard.android.backend.GoBackend")
            wgTurnOn = c.getDeclaredMethod("wgTurnOn", String::class.java, Integer.TYPE, String::class.java)
            wgTurnOn?.isAccessible = true
            wgTurnOff = c.getDeclaredMethod("wgTurnOff", Integer.TYPE)
            wgTurnOff?.isAccessible = true
            android.util.Log.i("WG", "Native WG lib loaded")
        } catch (e: Throwable) {
            android.util.Log.w("WG", "WG native unavailable: ${e.message}")
        }
    }

    fun start(fd: Int, priv: String, pub: String, ep: String): Boolean {
        val m = wgTurnOn ?: return false
        return try {
            val cfg = "private_key=$priv\npublic_key=$pub\nendpoint=$ep\nallowed_ip=0.0.0.0/0\npersistent_keepalive_interval=25\n"
            val h = m.invoke(null, "wg0", fd, cfg) as Int
            android.util.Log.i("WG", "WG started handle=$h")
            h >= 0
        } catch (e: Throwable) {
            android.util.Log.w("WG", "WG start error: ${e.message}")
            false
        }
    }

    fun stop(handle: Int) {
        wgTurnOff?.let { try { it.invoke(null, handle) } catch (_: Throwable) {} }
    }

    fun isAvailable(): Boolean = loaded && wgTurnOn != null
}
