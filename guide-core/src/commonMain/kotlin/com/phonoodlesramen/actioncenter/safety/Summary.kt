package com.phonoodlesramen.actioncenter.safety

/** The longest a [summary] gets: it goes in logs and on the safe-mode screen (`CrashGuard`). */
const val SUMMARY_MAX_LENGTH = 200

/** One line for people: "IllegalStateException: boom", capped in length. */
fun Throwable.summary(): String {
    val type = javaClass.simpleName
    val message = message?.takeIf { it.isNotBlank() }
    return (if (message == null) type else "$type: $message").take(SUMMARY_MAX_LENGTH)
}
