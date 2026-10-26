package com.calegix.mumblevideo.client

import com.calegix.mumblevideo.client.NativeMumbleCore.Outcome
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.DisconnectReason
import com.calegix.mumblevideo.core.RejectReason

/** Reconnect decisions, kept apart from the sockets so they can be tested. */
internal object Reconnects {
    /** The wait before reconnect [attempt] (1-based): 2, 4, 8, 16, then 30 s. */
    fun backoffMillis(attempt: Int): Long = (2_000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(30_000L)

    /**
     * While reconnecting, "username in use" means the server still holds our old session: its
     * socket died with the old network and the server has not noticed yet (it does within 30 s).
     * That is worth waiting out, where a first connect is told at once.
     */
    fun retriable(outcome: Outcome, reconnecting: Boolean): Outcome {
        val rejected = (outcome as? Outcome.Final)?.state as? ConnectionState.Rejected ?: return outcome
        if (!reconnecting || rejected.reason != RejectReason.UsernameInUse) return outcome
        return Outcome.Dropped(
            DisconnectReason.ConnectionLost(rejected.message),
            "The server still holds the old session",
            wasConnected = false,
        )
    }
}
