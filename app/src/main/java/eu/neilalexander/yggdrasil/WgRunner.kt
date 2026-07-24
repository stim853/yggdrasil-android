package eu.neilalexander.yggdrasil

import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.*

class WgRunner(private val ctx: android.content.Context) : Tunnel {
    private val backend = GoBackend(ctx)
    private var started = false

    override fun getName() = "yggwg"
    override fun onStateChange(newState: Tunnel.State?) {
        android.util.Log.i("WgRunner", "State: $newState")
    }

    fun start(endpoint: String): Boolean {
        if (started) return true
        return try {
            val intent = GoBackend.VpnService.prepare(ctx)
            if (intent != null) {
                android.util.Log.w("WgRunner", "Need VPN permission")
                return false
            }
            val cfg = Config.Builder()
                .setInterface(Interface.Builder()
                    .parsePrivateKey("oGmPby5pu8/vMivvXSvoCaR/umJ6AnN86YcHqkDjO3A=")
                    .addAddress(InetNetwork.parse("10.0.0.2/32"))
                    .build())
                .addPeer(Peer.Builder()
                    .parsePublicKey("KpoDU1El5vXjdHX/muvHzjfm7IxxrZ+yZYCW6oGyux8=")
                    .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
                    .setEndpoint(InetEndpoint.parse(endpoint))
                    .build())
                .build()
            backend.setState(this, Tunnel.State.UP, cfg)
            started = true
            true
        } catch (e: Exception) {
            android.util.Log.e("WgRunner", "WG start: $e")
            false
        }
    }

    fun stop() {
        if (!started) return
        try { backend.setState(this, Tunnel.State.DOWN, null) } catch (_: Exception) {}
        started = false
    }
}
