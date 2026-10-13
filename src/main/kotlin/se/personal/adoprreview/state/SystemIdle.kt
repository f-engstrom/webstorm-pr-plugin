package se.personal.adoprreview.state

import com.intellij.openapi.util.SystemInfo
import com.sun.jna.Library
import com.sun.jna.Native
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long since the last keyboard or mouse input anywhere on the computer (not just in the IDE). */
object SystemIdle {

    @Suppress("FunctionName")
    private interface CoreGraphics : Library {
        fun CGEventSourceSecondsSinceLastEventType(stateId: Int, eventType: Int): Double
    }

    private val coreGraphics: CoreGraphics? by lazy {
        if (SystemInfo.isMac) runCatching { Native.load("CoreGraphics", CoreGraphics::class.java) }.getOrNull() else null
    }

    /** Idle time, or zero where it can't be measured (non-macOS), so callers treat the user as present. */
    fun idleTime(): Duration {
        // kCGEventSourceStateCombinedSessionState = 0, kCGAnyInputEventType = ~0
        val seconds = coreGraphics?.let { runCatching { it.CGEventSourceSecondsSinceLastEventType(0, -1) }.getOrNull() } ?: 0.0
        return seconds.seconds
    }
}
