package eu.neilalexander.yggdrasil

import com.wireguard.android.backend.Tunnel

class WgTunnel : Tunnel {
    override fun getName(): String = "ygg-wg"
    override fun onStateChange(newState: Tunnel.State?) {}
}
