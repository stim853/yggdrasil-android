package eu.neilalexander.yggdrasil

import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.*

class WgRunner(private val ctx: android.content.Context) : Tunnel {
    private val backend = GoBackend(ctx)
    private var running = false

    override fun getName() = "yggwg"
    override fun onStateChange(newState: Tunnel.State?) {}

    fun start(ep: String): Boolean {
        if (running) return true
        return try {
            val i = GoBackend.VpnService.prepare(ctx)
            if (i != null) return false
            val cfg = Config.Builder()
                .setInterface(Interface.Builder()
                    .parsePrivateKey("oGmPby5pu8/vMivvXSvoCaR/umJ6AnN86YcHqkDjO3A=")
                    .addAddress(InetNetwork.parse("10.0.0.2/32"))
                    .build())
                .addPeer(Peer.Builder()
                    .parsePublicKey("KpoDU1El5vXjdHX/muvHzjfm7IxxrZ+yZYCW6oGyux8=")
                    .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
                    .setEndpoint(InetEndpoint.parse(ep))
                    .build())
                .build()
            backend.setState(this, Tunnel.State.UP, cfg)
            running = true
            true
        } catch (e: Exception) { android.util.Log.e("WG", "start: $e"); false }
    }

    fun stop() {
        if (!running) return
        try { backend.setState(this, Tunnel.State.DOWN, null) } catch (_: Exception) {}
        running = false
    }

    fun isOk(): Boolean {
        if (!running) return false
        return try {
            backend.getState(this) == Tunnel.State.UP
        } catch (_: Exception) { false }
    }
}
