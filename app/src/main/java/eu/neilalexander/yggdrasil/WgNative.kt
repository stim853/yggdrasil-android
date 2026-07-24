package eu.neilalexander.yggdrasil

import dalvik.system.DexClassLoader
import java.io.File

object WgNative {
    private var wgTurnOnMethod: java.lang.reflect.Method? = null
    private var wgTurnOffMethod: java.lang.reflect.Method? = null
    private var nativeLoaded = false

    fun init(context: android.content.Context) {
        try {
            // Load native library
            System.loadLibrary("wg-go")
            nativeLoaded = true
            android.util.Log.i("WgNative", "libwg-go loaded")

            // Get GoBackend native methods via reflection
            val cls = Class.forName("com.wireguard.android.backend.GoBackend")
            wgTurnOnMethod = cls.getDeclaredMethod("wgTurnOn", Int::class.javaPrimitiveType, String::class.java)
            wgTurnOnMethod?.isAccessible = true
            wgTurnOffMethod = cls.getDeclaredMethod("wgTurnOff", Int::class.javaPrimitiveType)
            wgTurnOffMethod?.isAccessible = true
            android.util.Log.i("WgNative", "WG native methods ready")
        } catch (e: Exception) {
            android.util.Log.w("WgNative", "WG native init failed: $e")
        }
    }

    fun start(tunFd: Int, privateKey: String, publicKey: String, endpoint: String, allowedIPs: String = "0.0.0.0/0"): Boolean {
        val method = wgTurnOnMethod ?: return false
        try {
            val settings = buildString {
                appendLine("private_key=$privateKey")
                appendLine("public_key=$publicKey")
                appendLine("endpoint=$endpoint")
                appendLine("allowed_ip=$allowedIPs")
                appendLine("persistent_keepalive_interval=25")
            }
            val result = method.invoke(null, tunFd, settings) as Int
            android.util.Log.i("WgNative", "WG started: handle=$result")
            return result >= 0
        } catch (e: Exception) {
            android.util.Log.e("WgNative", "WG start failed: $e")
            return false
        }
    }

    fun stop(handle: Int) {
        val method = wgTurnOffMethod ?: return
        try {
            method.invoke(null, handle)
            android.util.Log.i("WgNative", "WG stopped")
        } catch (_: Exception) {}
    }

    fun isAvailable(): Boolean = nativeLoaded && wgTurnOnMethod != null
}
