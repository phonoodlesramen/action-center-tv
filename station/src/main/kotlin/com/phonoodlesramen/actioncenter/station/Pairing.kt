package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.station.PairRequest
import com.phonoodlesramen.actioncenter.guide.station.PairStarted
import com.phonoodlesramen.actioncenter.guide.station.PairState
import com.phonoodlesramen.actioncenter.guide.station.PairStatus
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.guide.station.StationDevice
import com.phonoodlesramen.actioncenter.safety.summary
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** A call the station turns down, with the sentence a TV shows ([com.phonoodlesramen.actioncenter.guide.station.ApiError]). */
class ApiException(val status: Int, message: String) : Exception(message)

/**
 * Pairing (N3, `docs/design/guide-station-api.md`): a TV sends its public key and shows a
 * code; the station logs the same code. Once someone who can see both approves it, the
 * station signs the TV's certificate.
 *
 * - **In memory only:** a request waits [StationApi.PAIRING_MILLIS] for approval, and is
 *   forgotten an hour after that. A request the station doesn't know reads as expired.
 * - **At most [MAX_PER_MINUTE] requests a minute**, counted before anything is read.
 * - **Approving:** a file or folder named after the code in `approve/` (the first TV, from
 *   File Station or `approve <CODE>`), or a paired TV's Devices list.
 * - **Refusing:** unpairing a TV that's still waiting.
 */
class Pairing(
    private val ca: StationCa,
    private val devices: DeviceStore,
    private val approveDir: Path,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Request(
        val requestId: String,
        val deviceId: String,
        val name: String,
        val model: String,
        val publicKey: PublicKey,
        val code: String,
        val expiresAtMillis: Long,
    ) {
        var state = PairState.PENDING
        var certificate: String? = null
    }

    private val lock = Any()
    private val requests = LinkedHashMap<String, Request>()
    private val recent = ArrayDeque<Long>()

    fun start(body: PairRequest): PairStarted {
        synchronized(lock) {
            val now = clock()
            purge(now)
            while (recent.isNotEmpty() && now - recent.first() >= RATE_WINDOW_MILLIS) recent.removeFirst()
            if (recent.size >= MAX_PER_MINUTE) throw ApiException(429, "Too many TVs asked to pair just now. Wait a minute and try again.")
            recent.addLast(now)

            val name = tidy(body.name)
            if (name.isEmpty() || name.length > MAX_NAME) throw ApiException(400, "Give this TV a room name of up to $MAX_NAME characters.")
            val model = tidy(body.model).take(MAX_MODEL).ifEmpty { "TV" }
            val spki = try {
                Base64.getDecoder().decode(body.publicKey.trim())
            } catch (e: IllegalArgumentException) {
                throw ApiException(400, BAD_KEY)
            }
            val key = try {
                StationCa.ecP256(spki)
            } catch (e: IllegalArgumentException) {
                throw ApiException(400, BAD_KEY)
            }
            // Over the key exactly as the TV sent it, which is what the TV works its code out from.
            val code = StationApi.pairingCode(ca.caDer, spki)
            val request = Request(newRequestId(), newDeviceId(), name, model, key, code, now + StationApi.PAIRING_MILLIS)
            requests[request.requestId] = request
            log("Pairing: \"${name.replace('"', '\'')}\" code $code")
            return PairStarted(request.requestId, request.expiresAtMillis)
        }
    }

    fun status(requestId: String): PairStatus {
        synchronized(lock) {
            val now = clock()
            purge(now)
            val request = requests[requestId] ?: return PairStatus(PairState.EXPIRED)
            expire(request, now)
            return if (request.state == PairState.APPROVED) {
                PairStatus(PairState.APPROVED, certificate = request.certificate, caCertificate = ca.caPem)
            } else {
                PairStatus(request.state)
            }
        }
    }

    /** The TVs waiting for approval, with their codes, for `GET /v1/devices`. */
    fun pending(): List<StationDevice> {
        synchronized(lock) {
            val now = clock()
            return requests.values.onEach { expire(it, now) }.filter { it.state == PairState.PENDING }
                .map { StationDevice(id = it.deviceId, name = it.name, model = it.model, pending = true, code = it.code) }
        }
    }

    /** Approves the waiting TV [deviceId] (from a paired TV). False when no TV is waiting with that id. */
    fun approveDevice(deviceId: String): Boolean {
        synchronized(lock) {
            val request = waiting().firstOrNull { it.deviceId == deviceId } ?: return false
            approve(request)
            return true
        }
    }

    /** Approves every TV waiting with [code]; returns how many. */
    fun approveCode(code: String): Int {
        synchronized(lock) {
            val matching = waiting().filter { it.code == code }
            matching.forEach(::approve)
            return matching.size
        }
    }

    /** Refuses the waiting TV [deviceId]. False when no TV is waiting with that id. */
    fun refuseDevice(deviceId: String): Boolean {
        synchronized(lock) {
            val request = waiting().firstOrNull { it.deviceId == deviceId } ?: return false
            request.state = PairState.REFUSED
            log("Refused \"${request.name.replace('"', '\'')}\" (code ${request.code})")
            return true
        }
    }

    /**
     * Approves each code dropped into `approve/` as a file or a folder (File Station can make
     * either; `approve <CODE>` writes a file), then removes it. Run about once a second.
     */
    fun scanApprovals() {
        val entries = try {
            Files.list(approveDir).use { it.toList() }
        } catch (e: IOException) {
            return
        }
        for (entry in entries) {
            if (!entry.isRegularFile() && !entry.isDirectory()) continue
            val code = codeOf(entry.name) ?: continue
            if (approveCode(code) == 0) log("No TV is waiting to pair with code $code (codes last 5 minutes)")
            try {
                if (entry.isDirectory()) {
                    Files.walk(entry).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                } else {
                    Files.deleteIfExists(entry)
                }
            } catch (e: IOException) {
                log("Couldn't remove approve/${entry.name}: ${e.summary()}")
            }
        }
    }

    /** Under [lock]. */
    private fun waiting(): List<Request> {
        val now = clock()
        return requests.values.onEach { expire(it, now) }.filter { it.state == PairState.PENDING }
    }

    /** Under [lock]: the TV's certificate, and the TV in the paired list. */
    private fun approve(request: Request) {
        val now = clock()
        val certificate = ca.issueClient(request.deviceId, request.publicKey, now)
        devices.add(PairedDevice(request.deviceId, request.name, request.model, certificate.serialHex, now))
        request.certificate = StationCa.pem(certificate)
        request.state = PairState.APPROVED
        log("Paired \"${request.name.replace('"', '\'')}\" (code ${request.code}) as ${request.deviceId}")
    }

    private fun expire(request: Request, now: Long) {
        if (request.state == PairState.PENDING && now >= request.expiresAtMillis) request.state = PairState.EXPIRED
    }

    private fun purge(now: Long) {
        requests.values.removeIf { now >= it.expiresAtMillis + KEEP_MILLIS }
    }

    private fun newRequestId(): String = hex(16)

    private fun newDeviceId(): String {
        val taken = requests.values.mapTo(HashSet()) { it.deviceId } + devices.all().map { it.id }
        while (true) {
            val id = "tv-${hex(6)}"
            if (id !in taken) return id
        }
    }

    private fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_PER_MINUTE = 5
        const val MAX_NAME = 40
        private const val MAX_MODEL = 80
        private const val RATE_WINDOW_MILLIS = 60_000L

        /** How long a request is remembered after it expires, so an approved TV can still collect its certificate. */
        private const val KEEP_MILLIS = 60 * 60_000L

        private const val BAD_KEY = "This TV's key isn't an EC P-256 public key."

        /** Crockford base32, six characters: [StationApi.pairingCode]'s alphabet. */
        private val CODE = Regex("[0-9A-HJKMNP-TV-Z]{6}")
        private val CONTROL = Regex("\\p{Cntrl}")
        private val SPACES = Regex("\\s+")

        /**
         * The code a dropped name stands for: "abc123", "ABC123.txt" and a folder "ABC123" all
         * do. As Crockford decodes, O is 0 and I and L are 1. Null for anything else.
         */
        fun codeOf(name: String): String? {
            val code = name.substringBefore('.').trim().uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
            return code.takeIf(CODE::matches)
        }

        private fun tidy(text: String) = text.replace(CONTROL, " ").trim().replace(SPACES, " ")
    }
}
