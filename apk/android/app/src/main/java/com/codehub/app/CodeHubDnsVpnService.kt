package com.codehub.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.content.pm.ServiceInfo
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Locale
import java.util.LinkedHashSet
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** DNS-only local VPN. General app traffic is never routed through CodeHub. */
class CodeHubDnsVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private data class DomainRules(val blocked: Set<String>, val allowed: Set<String>)
    @Volatile private var domainRules = DomainRules(emptySet(), emptySet())
    private val blockedCount = AtomicInteger(0)
    private val dnsWorkers = Executors.newFixedThreadPool(4) { task -> Thread(task, "CodeHub-DNS-worker").apply { isDaemon = true } }

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
            blockedCount.set(getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_BLOCKED_COUNT, 0))
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply()
            domainRules = loadPrivateBlocklist()
            Thread({
                refreshBlocklistFromServer()
            }, "CodeHub-adblock-list-update").start()
            worker = Thread({ runDnsTunnel(descriptor) }, "CodeHub-DNS-filter").apply { start() }
        } catch (error: Exception) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
            dnsWorkers.shutdownNow()
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
            val packet = buffer.copyOf(size)
            try {
                dnsWorkers.execute {
                    val response = try { handleIpv4UdpDns(packet, packet.size) } catch (_: Exception) { null }
                    if (response != null) try { synchronized(output) { output.write(response) } } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
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
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_BLOCKED_COUNT, blockedCount.incrementAndGet()).apply()
            blockedDnsResponse(query)
        } else forwardDnsWithRecovery(query) ?: dnsErrorResponse(query)
        val sourceAddress = packet.copyOfRange(16, 20)
        val destinationAddress = packet.copyOfRange(12, 16)
        return ipv4UdpReply(sourceAddress, destinationAddress, destinationPort, sourcePort, packet, reply)
    }

    /** AdGuard DoT first, then its filtered plain DNS, then the device network resolver as fail-open. */
    private fun forwardDnsWithRecovery(query: ByteArray): ByteArray? {
        forwardToAdGuardDot(query)?.let { recordResolverMode("AdGuard DNS cifrado · DNS-over-TLS"); return it }
        forwardToAdGuardUdp(query)?.let { recordResolverMode("AdGuard DNS · respaldo DNS sin cifrar"); return it }
        forwardToNetworkDns(query)?.let { recordResolverMode("DNS de la red · respaldo sin filtro"); return it }
        recordResolverMode("Sin resolver DNS disponible")
        return null
    }

    private fun forwardToAdGuardDot(query: ByteArray): ByteArray? {
        return try {
            var lastError: Exception? = null
            for (address in ADGUARD_DNS_IPS.take(1)) {
                try {
                    val raw = Socket()
                    try {
                        if (!protect(raw)) continue
                        raw.connect(InetSocketAddress(address, 853), 1_200)
                        raw.soTimeout = 1_800
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
                            response.takeIf { isUsableDnsResponse(query, it) }
                        }
                    } finally {
                        runCatching { raw.close() }
                    }
                } catch (error: Exception) { lastError = error }
            }
            throw lastError ?: IllegalStateException("AdGuard DNS no responde")
        } catch (_: Exception) { null }
    }

    private fun forwardToAdGuardUdp(query: ByteArray): ByteArray? = forwardUdp(query, ADGUARD_DNS_IPS.mapNotNull(::parseIpv4))

    private fun forwardToNetworkDns(query: ByteArray): ByteArray? {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val servers = mutableListOf<InetAddress>()
        for (network in connectivity.allNetworks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val properties: LinkProperties = connectivity.getLinkProperties(network) ?: continue
            servers += properties.dnsServers.filter { it.address.size == 4 && it.hostAddress != LOCAL_DNS }
        }
        return forwardUdp(query, servers.distinct())
    }

    private fun forwardUdp(query: ByteArray, servers: List<InetAddress>): ByteArray? {
        for (server in servers) {
            try {
                var answer: ByteArray? = null
                DatagramSocket().use { socket ->
                    if (!protect(socket)) return null
                    socket.soTimeout = 1_500
                    socket.send(DatagramPacket(query, query.size, server, 53))
                    val buffer = ByteArray(4096)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    if (response.length >= 12 && isUsableDnsResponse(query, buffer.copyOf(response.length))) {
                        answer = buffer.copyOf(response.length)
                    }
                }
                if (answer != null) return answer
            } catch (_: Exception) { }
        }
        return null
    }

    private fun isUsableDnsResponse(query: ByteArray, response: ByteArray): Boolean =
        response.size >= 12 && response[0] == query[0] && response[1] == query[1] &&
            (response[2].toInt() and 0x80) != 0 && (u16(response, 2) and 0x000F) != 2

    private fun parseIpv4(address: String): InetAddress? = runCatching { InetAddress.getByName(address) }.getOrNull()

    private fun recordResolverMode(mode: String) {
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (preferences.getString(KEY_RESOLVER_MODE, null) != mode) preferences.edit().putString(KEY_RESOLVER_MODE, mode).apply()
    }

    /** The downloaded rules live in app-private internal storage and are parsed only by CodeHub. */
    private fun loadPrivateBlocklist(): DomainRules {
        val file = File(filesDir, PRIVATE_BLOCKLIST_FILE)
        val rules = if (file.isFile) runCatching { parseBlocklist(file.readText()) }.getOrDefault(DomainRules(emptySet(), emptySet())) else DomainRules(emptySet(), emptySet())
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_RULE_COUNT, rules.blocked.size)
            .putString(KEY_LIST_STATUS, if (rules.blocked.isEmpty()) "Sin reglas locales · esperando adsblock.txt" else "Lista local activa · ${rules.blocked.size} dominios")
            .apply()
        return rules
    }

    private fun refreshBlocklistFromServer() {
        val localFile = File(filesDir, PRIVATE_BLOCKLIST_FILE)
        val lastUpdated = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_LIST_UPDATED_AT, 0L)
        if (localFile.isFile && localFile.length() > 0 && System.currentTimeMillis() - lastUpdated < BLOCKLIST_REFRESH_INTERVAL_MS) return
        val connection = (URL(BLOCKLIST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_000
            readTimeout = 15_000
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", "CodeHub-Android-DNS/1")
        }
        try {
            val statusCode = connection.responseCode
            if (statusCode != HttpURLConnection.HTTP_OK) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(
                    KEY_LIST_STATUS,
                    if (statusCode == HttpURLConnection.HTTP_NOT_FOUND) "Falta adsblock.txt en Supabase (HTTP 404)"
                    else "No se pudo actualizar adsblock.txt (HTTP $statusCode)"
                ).apply()
                return
            }
            val body = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BLOCKLIST_BYTES) throw IllegalStateException("adsblock.txt excede el límite de tamaño.")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            val parsed = parseBlocklist(body)
            if (parsed.blocked.isEmpty()) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_LIST_STATUS, "adsblock.txt no contiene reglas DNS compatibles")
                    .apply()
                return // Keep the last known good local file.
            }
            val target = File(filesDir, PRIVATE_BLOCKLIST_FILE)
            val temporary = File(filesDir, "$PRIVATE_BLOCKLIST_FILE.tmp")
            temporary.writeText(body, Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                target.writeText(body, Charsets.UTF_8)
                temporary.delete()
            }
            domainRules = parsed
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_RULE_COUNT, parsed.blocked.size)
                .putLong(KEY_LIST_UPDATED_AT, System.currentTimeMillis())
                .putString(KEY_LIST_STATUS, "Lista activa · ${parsed.blocked.size} dominios")
                .apply()
        } catch (error: Exception) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(
                KEY_LIST_STATUS,
                "No se pudo cargar adsblock.txt: ${error.localizedMessage ?: error.javaClass.simpleName}"
            ).apply()
            // Preserve the last known good list and keep DNS fail-open behavior.
        } finally {
            connection.disconnect()
        }
    }

    /** Supports hosts format and domain-only/AdGuard `||domain^` rules; path and cosmetic rules are ignored. */
    private fun parseBlocklist(contents: String): DomainRules {
        val blocked = LinkedHashSet<String>()
        val allowed = LinkedHashSet<String>()
        for (rawLine in contents.lineSequence()) {
            var line = rawLine.trim().lowercase(Locale.ROOT)
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("#") || line.startsWith("[") || line.startsWith("/")) continue
            if (line.contains("##") || line.contains("#@#") || line.contains("#?#")) continue // cosmetic rules do not map to DNS
            line = line.substringBefore(" #").substringBefore("\t#").trim()
            if (line.isEmpty()) continue
            val exception = line.startsWith("@@")
            if (exception) line = line.removePrefix("@@")
            if (line.startsWith("||")) {
                line = line.removePrefix("||").removePrefix("*.").substringBefore('^').substringBefore('$')
                if ('/' in line || '*' in line) continue
                if (!isValidDomain(line)) continue
                if (exception) allowed += line.trimEnd('.') else blocked += line.trimEnd('.')
                continue
            }
            if (exception) continue
            val columns = line.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
            val domainCandidates = if (columns.size > 1 && isIpv4OrLocal(columns.first())) columns.drop(1) else columns.take(1)
            domainCandidates.forEach { rawDomain ->
                val domain = rawDomain.removePrefix("*.").trimEnd('.')
                if (isValidDomain(domain)) blocked += domain
            }
        }
        return DomainRules(blocked, allowed)
    }

    private fun isValidDomain(domain: String): Boolean = domain.length in 4..253 &&
        domain.split('.').size >= 2 && domain.split('.').all { label ->
            label.length in 1..63 && label.firstOrNull()?.isLetterOrDigit() == true &&
                label.lastOrNull()?.isLetterOrDigit() == true && label.all { it.isLetterOrDigit() || it == '-' }
        }

    private fun isIpv4OrLocal(value: String): Boolean = value == "0.0.0.0" || value == "127.0.0.1" || value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))

    private fun blockedDomain(domain: String): Boolean {
        if (matchesSuffix(domain, domainRules.allowed)) return false
        return matchesSuffix(domain, domainRules.blocked)
    }

    private fun matchesSuffix(domain: String, rules: Set<String>): Boolean {
        var candidate = domain
        while (true) {
            if (rules.contains(candidate)) return true
            val separator = candidate.indexOf('.')
            if (separator < 0) return false
            candidate = candidate.substring(separator + 1)
        }
    }

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
        dnsWorkers.shutdownNow()
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
        const val KEY_RULE_COUNT = "local_rule_count"
        const val KEY_RESOLVER_MODE = "resolver_mode"
        const val KEY_LIST_UPDATED_AT = "list_updated_at"
        const val KEY_LIST_STATUS = "blocklist_status"
        const val NOTIFICATION_ID = 7102
        private const val LOCAL_DNS = "10.73.0.1"
        private const val TUN_ADDRESS = "10.73.0.2"
        private val ADGUARD_DNS_IPS = arrayOf("94.140.14.14", "94.140.15.15")
        private const val ADGUARD_DNS_HOST = "dns.adguard-dns.com"
        private const val PRIVATE_BLOCKLIST_FILE = "adsblock.txt"
        private const val BLOCKLIST_URL = "https://codehub-98s6.onrender.com/api/security/adsblock.txt"
        private const val MAX_BLOCKLIST_BYTES = 15 * 1024 * 1024
        private const val BLOCKLIST_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
