package com.fernandogodinho.teclado.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.fernandogodinho.teclado.core.LocalNetwork
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.Executors

/** A TV announced on the local network. */
data class FoundTv(val name: String, val host: InetAddress, val port: Int)

/**
 * Finds Android TV / Google TV devices on the same Wi-Fi through mDNS/DNS-SD (the TVs announce
 * the "_androidtvremote2._tcp" service). Only answers with local addresses are kept.
 */
class TvDiscovery(context: Context, private val onChange: (List<FoundTv>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val found = LinkedHashMap<String, FoundTv>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private val executor = Executors.newSingleThreadExecutor()

    @Synchronized
    fun start() {
        if (listener != null || nsd == null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = stop()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(this@TvDiscovery) { found.remove(info.serviceName) }
                publish()
            }
        }
        listener = l
        runCatching { nsd.discoverServices(AtvProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener = null }
    }

    @Synchronized
    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd?.stopServiceDiscovery(l) }
    }

    private fun enqueue(info: NsdServiceInfo) {
        synchronized(this) {
            pending.add(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    /** NsdManager resolves one service at a time on older Android versions. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(this) {
            pending.pollFirst() ?: run { resolving = false; null }
        } ?: return
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveNext()
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull() else info.host
                if (host != null && LocalNetwork.isLocal(host)) {
                    synchronized(this@TvDiscovery) { found[info.serviceName] = FoundTv(info.serviceName, host, info.port) }
                    publish()
                }
                resolveNext()
            }
        }
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 34) nsd!!.resolveService(next, executor, callback) else nsd!!.resolveService(next, callback)
        }.isSuccess
        if (!started) resolveNext()
    }

    private fun publish() = onChange(synchronized(this) { found.values.toList() })
}
