package io.github.hypercopy.clipboard.jump

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiuiSystemCopyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun missingUrlUsesAppLauncher() {
        val intent = Intent(Intent.ACTION_VIEW).setPackage("com.taobao.taobao")
        val launcher = requireNotNull(context.packageManager.getLaunchIntentForPackage("com.taobao.taobao"))
        assertTrue(MiuiSystemCopy.copy(context, PendingJump.IntentJump("淘宝", intent), "淘宝分享内容"))
        val target = requireNotNull(MiuiSystemCopy.takeTarget("淘宝分享内容"))
        val transported = roundTrip(target.intent)
        assertNull(transported.data)
        assertTrue(launcher.filterEquals(transported))
        assertEquals(launcher.flags, transported.flags)
        assertEquals("com.taobao.taobao", target.packageName)
        assertNull(MiuiSystemCopy.takeTarget("淘宝分享内容"))
    }

    @Test
    fun deepLinkRetainsActionDataAndExtras() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://video/123")).apply {
            setPackage("com.bilibili.app.in")
            putExtra("from", "copy")
        }
        assertTrue(MiuiSystemCopy.copy(context, PendingJump.IntentJump("哔哩哔哩", intent), "B站分享内容"))
        val transported = roundTrip(requireNotNull(MiuiSystemCopy.takeTarget("B站分享内容")).intent)
        assertTrue(intent.filterEquals(transported))
        assertEquals("copy", transported.getStringExtra("from"))
    }

    @Test
    fun cachedTargetDefaultsToKeepingClipboard() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/default"))
            .setPackage(context.packageName)
        val source = "default clipboard preference"
        assertTrue(MiuiSystemCopy.copy(context, PendingJump.IntentJump("Example", intent), source))
        val target = requireNotNull(MiuiSystemCopy.takeTarget(source))
        assertFalse(target.clearClipboardAfterJump)
        assertTrue(intent.filterEquals(target.intent))
        assertNull(MiuiSystemCopy.takeTarget(source))
    }

    @Test
    fun cachedTargetRetainsEnabledClipboardClearPreference() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/clear"))
            .setPackage(context.packageName)
        val source = "enabled clipboard preference"
        assertTrue(MiuiSystemCopy.copy(context, PendingJump.IntentJump("Example", intent), source, clearClipboardAfterJump = true))
        val target = requireNotNull(MiuiSystemCopy.takeTarget(source))
        assertTrue(target.clearClipboardAfterJump)
        assertTrue(intent.filterEquals(target.intent))
        assertNull(MiuiSystemCopy.takeTarget(source))
    }

    @Test
    fun cachedTargetRetainsDisabledClipboardClearPreference() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/keep"))
            .setPackage(context.packageName)
        val source = "disabled clipboard preference"
        assertTrue(MiuiSystemCopy.copy(context, PendingJump.IntentJump("Example", intent), source, clearClipboardAfterJump = false))
        val target = requireNotNull(MiuiSystemCopy.takeTarget(source))
        assertFalse(target.clearClipboardAfterJump)
        assertTrue(intent.filterEquals(target.intent))
        assertNull(MiuiSystemCopy.takeTarget(source))
    }

    private fun roundTrip(intent: Intent): Intent {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(Bundle().apply { putParcelable("intent", intent) })
            parcel.setDataPosition(0)
            return requireNotNull(parcel.readBundle(Intent::class.java.classLoader))
                .getParcelable("intent", Intent::class.java)!!
        } finally {
            parcel.recycle()
        }
    }
}
