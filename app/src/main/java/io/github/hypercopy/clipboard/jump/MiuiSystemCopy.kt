package io.github.hypercopy.clipboard.jump

import android.content.Intent
import android.content.Context
import android.net.Uri
import io.github.hypercopy.HyperLog
import java.util.concurrent.ConcurrentHashMap

/** Sends the resolved target to Xiaomi AICR's native copy-direct bubble. */
object MiuiSystemCopy {
    private const val TAG = "HyperCopy"
    private const val EXPIRE_MILLIS = 10_000L
    private val targets = ConcurrentHashMap<String, Target>()

    fun copy(context: Context, jump: PendingJump, originalText: String, clearClipboardAfterJump: Boolean = false): Boolean {
        val source = originalText.trim()
        if (source.isEmpty() || jump.packageName.isBlank()) return false
        val resolvedIntent = when (jump) {
            is PendingJump.IntentJump -> Intent(jump.intent)
            is PendingJump.WebViewJump -> Intent(Intent.ACTION_VIEW, Uri.parse(jump.url))
            is PendingJump.SystemLinkJump -> Intent(Intent.ACTION_VIEW, Uri.parse(jump.url))
        }
        val intent = if (resolvedIntent.dataString.isNullOrBlank()) {
            context.packageManager.getLaunchIntentForPackage(jump.packageName) ?: return false
        } else {
            resolvedIntent
        }

        val now = System.currentTimeMillis()
        targets.entries.removeIf { it.value.expiresAt < now }
        targets[source] = Target(intent, jump.packageName, now + EXPIRE_MILLIS, clearClipboardAfterJump)
        HyperLog.d(TAG, "system copy-direct target cached for AICR")
        return true
    }

    fun takeTarget(originalText: String): Target? {
        val target = targets.remove(originalText.trim()) ?: return null
        return target.takeIf { it.expiresAt >= System.currentTimeMillis() }
    }

    data class Target(val intent: Intent, val packageName: String, val expiresAt: Long, val clearClipboardAfterJump: Boolean)
}
