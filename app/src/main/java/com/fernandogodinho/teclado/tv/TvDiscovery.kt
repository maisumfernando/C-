package com.fernandogodinho.teclado.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.fernandogodinho.teclado.core.LocalNetwork
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Finds TVs on the same Wi-Fi: Google TV / Android TV through mDNS ("_androidtvremote2._tcp"),
 * and Samsung, LG and Roku TVs through SSDP. Only answers with local addresses are kept.
 */
class TvDiscovery(private val context: Context, private val onChange: (List<FoundTv>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val found = LinkedHashMap<String, FoundTv>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    @Synchronized
    fun start() {
        if (running) return
        running = true
        startNsd()
        Thread({ ssdpLoop() }, "teclado-ssdp").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        running = false
        val l = listener ?: return
        listener = null
        runCatching { nsd?.stopServiceDiscovery(l) }
    }

    // --- Samsung, LG, Roku (SSDP) ----------------------------------------------------------

    private fun ssdpLoop() {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = runCatching { wifi?.createMulticastLock("teclado-tv")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try {
            var round = 0
            while (running && round < 4) {
                val replies = runCatching { Ssdp.search(SSDP_TARGETS) }.getOrDefault(emptyList())
                for (r in replies) {
                    val tv = classify(r) ?: continue
                    val key = "${tv.platform}|${tv.host.hostAddress}"
                    val known = synchronized(this) { found.values.any { "${it.platform}|${it.host.hostAddress}" == key } }
                    if (known) continue
                    val named = tv.copy(name = nameOf(tv, r) ?: tv.name)
                    synchronized(this) { found["${named.platform}:${named.name}"] = named }
                    publish()
                }
                round++
            }
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun classify(r: SsdpReply): FoundTv? {
        val st = r.st.lowercase()
        val server = r.server.lowercase()
        val platform = when {
            "samsung" in st || ("samsung" in server && "tizen" in server) -> TvPlatform.SAMSUNG
            "lge-com" in st || "webos" in server -> TvPlatform.LG
            st == "roku:ecp" || "roku" in server -> TvPlatform.ROKU
            else -> return null
        }
        val port = when (platform) {
            TvPlatform.SAMSUNG -> 8002
            TvPlatform.LG -> 3001
            TvPlatform.ROKU -> 8060
            TvPlatform.ANDROID_TV -> AtvProtocol.REMOTE_PORT
        }
        return FoundTv(platform.label.substringBefore(' ') + " TV", r.address, port, platform)
    }

    private fun nameOf(tv: FoundTv, r: SsdpReply): String? = runCatching {
        when (tv.platform) {
            TvPlatform.SAMSUNG -> TvNames.samsung(tv.host)
            TvPlatform.ROKU -> TvNames.roku(tv.host)
            TvPlatform.LG -> TvNames.upnp(r.location)?.let { if (it.contains("LG", true)) it else "LG $it" }
            else -> null
        }
    }.getOrNull()

    // --- Google TV / Android TV (mDNS) -----------------------------------------------------

    private fun startNsd() {
        if (listener != null || nsd == null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(this@TvDiscovery) { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(this@TvDiscovery) { found.remove("${TvPlatform.ANDROID_TV}:${info.serviceName}") }
                publish()
            }
        }
        listener = l
        runCatching { nsd.discoverServices(AtvProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener = null }
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
                    synchronized(this@TvDiscovery) {
                        found["${TvPlatform.ANDROID_TV}:${info.serviceName}"] = FoundTv(info.serviceName, host, info.port, TvPlatform.ANDROID_TV)
                    }
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

    companion object {
        val SSDP_TARGETS = listOf(
            "urn:samsung.com:device:RemoteControlReceiver:1",
            "urn:lge-com:service:webos-second-screen:1",
            "roku:ecp",
            "urn:dial-multiscreen-org:service:dial:1",
        )
    }
}
