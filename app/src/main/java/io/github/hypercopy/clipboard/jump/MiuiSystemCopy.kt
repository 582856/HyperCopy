package io.github.hypercopy.clipboard.jump

import android.content.Intent
import io.github.hypercopy.HyperLog
import java.util.concurrent.ConcurrentHashMap

/** Sends the resolved target to Xiaomi AICR's native copy-direct bubble. */
object MiuiSystemCopy {
    private const val TAG = "HyperCopy"
    private const val EXPIRE_MILLIS = 10_000L
    private val targets = ConcurrentHashMap<String, Target>()

    fun copy(jump: PendingJump, originalText: String): Boolean {
        val text = when (jump) {
            is PendingJump.IntentJump -> jump.intent.dataString
                ?: jump.intent.getStringExtra(Intent.EXTRA_TEXT)
            is PendingJump.WebViewJump -> jump.url
            is PendingJump.SystemLinkJump -> jump.url
        }?.trim().orEmpty()
        if (text.isEmpty()) {
            HyperLog.d(TAG, "system copy jump has no URL/text")
            return false
        }
        val source = originalText.trim()
        if (source.isEmpty() || jump.packageName.isBlank()) return false

        val now = System.currentTimeMillis()
        targets.entries.removeIf { it.value.expiresAt < now }
        targets[source] = Target(text, jump.packageName, now + EXPIRE_MILLIS)
        HyperLog.d(TAG, "system copy-direct target cached for AICR")
        return true
    }

    fun takeTarget(originalText: String): Target? {
        val target = targets.remove(originalText.trim()) ?: return null
        return target.takeIf { it.expiresAt >= System.currentTimeMillis() }
    }

    data class Target(val text: String, val packageName: String, val expiresAt: Long)
}
