package com.codehub.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.content.pm.ServiceInfo
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** DNS-only local VPN. AdGuard DNS filters ads/trackers upstream; CodeHub never routes general traffic. */
class CodeHubDnsVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.get()) return START_STICKY
        try {
            startAsForeground()
            tunnel = Builder()
                .setSession("CodeHub Shield · DNS")
                .setMtu(1500)
                .addAddress(TUN_ADDRESS, 32)
                .addDnsServer(LOCAL_DNS)
                .addRoute(LOCAL_DNS, 32)
                .setBlocking(true)
                .establish()
            val descriptor = tunnel ?: error("Android no creó el túnel VPN.")
            running.set(true)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply()
            worker = Thread({ runDnsTunnel(descriptor) }, "CodeHub-DNS-filter").apply { start() }
        } catch (error: Exception) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val channelId = "codehub_dns_vpn"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "CodeHub Shield", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(this, 7101, Intent(this, CodeHubPrivacyActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notificationBuilder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        val notification = notificationBuilder
            .setSmallIcon(R.drawable.ic_home_boost)
            .setContentTitle("CodeHub Shield está activo")
            .setContentText("Filtrado DNS de anuncios y rastreadores")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun runDnsTunnel(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(32767)
        while (running.get()) {
            val size = try { input.read(buffer) } catch (_: Exception) { break }
            if (size < 28) continue
            val response = try { handleIpv4UdpDns(buffer, size) } catch (_: Exception) { null }
            if (response != null) try { synchronized(output) { output.write(response) } } catch (_: Exception) { break }
        }
        running.set(false)
        stopSelf()
    }

    private fun handleIpv4UdpDns(packet: ByteArray, size: Int): ByteArray? {
        val version = (packet[0].toInt() ushr 4) and 0xF
        if (version != 4) return null
        val headerLength = (packet[0].toInt() and 0xF) * 4
        if (headerLength < 20 || size < headerLength + 8) return null
        if ((packet[9].toInt() and 0xFF) != 17) return null
        val sourcePort = u16(packet, headerLength)
        val destinationPort = u16(packet, headerLength + 2)
        if (destinationPort != 53) return null
        val dnsLength = u16(packet, headerLength + 4) - 8
        val dnsOffset = headerLength + 8
        if (dnsLength < 12 || dnsOffset + dnsLength > size) return null
        val query = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        val domain = dnsQuestionName(query)?.lowercase(Locale.ROOT)
        val reply = if (domain != null && blockedDomain(domain)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_BLOCKED_COUNT,
                getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_BLOCKED_COUNT, 0) + 1).apply()
            blockedDnsResponse(query)
        } else forwardToAdGuard(query) ?: dnsErrorResponse(query)
        val sourceAddress = packet.copyOfRange(16, 20)
        val destinationAddress = packet.copyOfRange(12, 16)
        return ipv4UdpReply(sourceAddress, destinationAddress, destinationPort, sourcePort, packet, reply)
    }

    private fun forwardToAdGuard(query: ByteArray): ByteArray? {
        return try {
            var lastError: Exception? = null
            for (address in ADGUARD_DNS_IPS) {
                try {
                    val raw = Socket()
                    try {
                        if (!protect(raw)) continue
                        raw.connect(InetSocketAddress(address, 853), 3_000)
                        raw.soTimeout = 5_000
                        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        val tls = (factory.createSocket(raw, ADGUARD_DNS_HOST, 853, true) as SSLSocket).apply {
                            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                            startHandshake()
                        }
                        return tls.use {
                            val output = DataOutputStream(it.outputStream)
                            output.writeShort(query.size)
                            output.write(query)
                            output.flush()
                            val input = DataInputStream(it.inputStream)
                            val response = ByteArray(input.readUnsignedShort())
                            input.readFully(response)
                            response
                        }
                    } finally {
                        runCatching { raw.close() }
                    }
                } catch (error: Exception) { lastError = error }
            }
            throw lastError ?: IllegalStateException("AdGuard DNS no responde")
        } catch (_: Exception) { null }
    }

    private fun blockedDomain(domain: String): Boolean = BLOCKED_DOMAINS.any { domain == it || domain.endsWith(".$it") }

    private fun dnsQuestionName(dns: ByteArray): String? {
        return try {
            var index = 12
            val labels = mutableListOf<String>()
            while (index < dns.size) {
                val length = dns[index++].toInt() and 0xFF
                if (length == 0) break
                if (length > 63 || index + length > dns.size) return null
                labels += String(dns, index, length, Charsets.US_ASCII)
                index += length
            }
            labels.takeIf { it.isNotEmpty() }?.joinToString(".")
        } catch (_: Exception) { null }
    }

    private fun blockedDnsResponse(query: ByteArray): ByteArray {
        val response = query.copyOf()
        val flags = u16(response, 2) or 0x8000 or 0x0080 or 0x0003 // response + recursion available + NXDOMAIN
        put16(response, 2, flags)
        put16(response, 6, 0); put16(response, 8, 0); put16(response, 10, 0)
        return response
    }

    private fun dnsErrorResponse(query: ByteArray): ByteArray {
        val response = query.copyOf()
        put16(response, 2, u16(response, 2) or 0x8000 or 0x0080 or 0x0002) // SERVFAIL: resolver unreachable
        put16(response, 6, 0); put16(response, 8, 0); put16(response, 10, 0)
        return response
    }

    private fun ipv4UdpReply(src: ByteArray, dst: ByteArray, sourcePort: Int, destinationPort: Int, request: ByteArray, dns: ByteArray): ByteArray {
        val packet = ByteArray(28 + dns.size)
        packet[0] = 0x45
        packet[1] = 0
        put16(packet, 2, packet.size)
        packet[4] = request[4]; packet[5] = request[5]
        packet[6] = 0x40; packet[7] = 0
        packet[8] = 64; packet[9] = 17
        System.arraycopy(src, 0, packet, 12, 4); System.arraycopy(dst, 0, packet, 16, 4)
        put16(packet, 10, checksum(packet, 0, 20))
        put16(packet, 20, sourcePort); put16(packet, 22, destinationPort); put16(packet, 24, 8 + dns.size)
        System.arraycopy(dns, 0, packet, 28, dns.size)
        return packet
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length) {
            sum += (((bytes[i].toInt() and 0xFF) shl 8) or (if (i + 1 < offset + length) bytes[i + 1].toInt() and 0xFF else 0)).toLong()
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    private fun u16(bytes: ByteArray, offset: Int): Int = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    private fun put16(bytes: ByteArray, offset: Int, value: Int) { bytes[offset] = (value ushr 8).toByte(); bytes[offset + 1] = value.toByte() }

    override fun onRevoke() { stopSelf(); super.onRevoke() }
    override fun onDestroy() {
        running.set(false)
        runCatching { tunnel?.close() }
        tunnel = null
        worker = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.codehub.app.action.DNS_VPN_START"
        const val ACTION_STOP = "com.codehub.app.action.DNS_VPN_STOP"
        const val PREFS = "codehub_privacy_vpn"
        const val KEY_ACTIVE = "active"
        const val KEY_BLOCKED_COUNT = "blocked_count"
        const val NOTIFICATION_ID = 7102
        private const val LOCAL_DNS = "10.73.0.1"
        private const val TUN_ADDRESS = "10.73.0.2"
        private val ADGUARD_DNS_IPS = arrayOf("94.140.14.14", "94.140.15.15")
        private const val ADGUARD_DNS_HOST = "dns.adguard-dns.com"
        private val BLOCKED_DOMAINS = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "ads.yahoo.com", "adnxs.com", "adform.net", "taboola.com", "outbrain.com",
            "scorecardresearch.com", "app-measurement.com", "tracking.miui.com",
        )
    }
}
