package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.safety.summary
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  (no arguments)   run the guide station
  approve <CODE>   approve the TV showing <CODE> (the code in the log's "Pairing:" line)"""

/**
 * The guide station's entry point (the image's ENTRYPOINT). With `approve <CODE>` it approves
 * a pairing from the container's terminal instead (`docker exec`, as `station/README.md`
 * shows: the same `java` command with `approve ABC123` on the end).
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        null -> serve()
        "approve" -> exitProcess(approve(args.drop(1), StationConfig.fromEnv(), ::println))
        else -> {
            System.err.println(USAGE)
            exitProcess(2)
        }
    }
}

private fun serve() {
    val station = try {
        Station(StationConfig.fromEnv(log = StationLog::line)).start()
    } catch (e: Exception) {
        StationLog.line("The guide station couldn't start: ${e.summary()}")
        exitProcess(1)
    }
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            StationLog.line("Stopping")
            station.close()
            stopped.countDown()
        },
    )
    stopped.await()
}

/**
 * `approve <CODE>`: leaves the code in `approve/` for the running station, which approves the
 * TV waiting with it within a second, then waits up to [waitMillis] to see it picked up.
 * Returns the exit status: 0 once picked up, 1 if not, 2 for a bad code.
 */
internal fun approve(args: List<String>, config: StationConfig, out: (String) -> Unit, waitMillis: Long = 5_000L): Int {
    val given = args.singleOrNull()
    if (given == null) {
        out(USAGE)
        return 2
    }
    val code = Pairing.codeOf(given)
    if (code == null) {
        out("\"${given.take(20)}\" isn't a pairing code: it's six letters and digits, as the TV shows it.")
        return 2
    }
    val file = config.approveDir.resolve(code)
    try {
        Files.createDirectories(config.approveDir)
        Files.write(file, ByteArray(0))
    } catch (e: IOException) {
        out("Couldn't leave the code in ${config.approveDir}: ${e.summary()}")
        return 1
    }
    val until = System.currentTimeMillis() + waitMillis
    while (System.currentTimeMillis() < until) {
        if (!Files.exists(file)) {
            out("The station took code $code. Its log says \"Paired\" if a TV was waiting with it.")
            return 0
        }
        Thread.sleep(100)
    }
    out("Left code $code for the station, but it hasn't taken it yet. Is the station running?")
    return 1
}
