package io.github.hypercopy.hook

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import androidx.core.graphics.drawable.toBitmap
import io.github.hypercopy.Config
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge

/** Browser-only hooks located by the AICR string anchors documented by HyperCeiler. */
object AicrBrowserHooks {
    private const val WEBSITE_TYPE = 11
    private var installed = false
    private val websiteNotification = ThreadLocal<Boolean>()

    @JvmStatic
    fun install(module: XposedModule, context: Context) {
        if (installed) return
        try {
            System.loadLibrary("dexkit")
            DexKitBridge.create(context.applicationInfo.sourceDir).use { bridge ->
                val browserJump = bridge.findMethod {
                    matcher {
                        addUsingString("clipboard_open")
                        returnType = "void"
                        paramTypes(null, "java.lang.String")
                    }
                }.single().getMethodInstance(context.classLoader)
                require(Context::class.java.isAssignableFrom(browserJump.parameterTypes[0])) {
                    "Unsupported copy-direct browser context: $browserJump"
                }
                // Newer native bubbles serialize a browser Intent instead of calling the legacy jump method.
                val browserIntent = bridge.findMethod {
                    matcher {
                        addUsingString("clipboard_open")
                        returnType = "android.content.Intent"
                        paramTypes("java.lang.String")
                    }
                }.singleOrNull()?.getMethodInstance(context.classLoader)
                val owner = browserJump.declaringClass.name
                val targetPackage = bridge.findMethod {
                    matcher {
                        declaredClass = owner
                        returnType = "java.lang.String"
                        paramTypes("android.content.Context", "int")
                        addUsingString("com.taobao.taobao")
                        addUsingString("com.eg.android.AlipayGphone")
                    }
                }.single().getMethodInstance(context.classLoader)
                val installedCheck = bridge.findMethod {
                    matcher {
                        declaredClass = owner
                        returnType = "boolean"
                        paramTypes("android.content.Context", "int")
                        addUsingString("isInstallForApp: ///////////////////")
                    }
                }.singleOrNull()?.getMethodInstance(context.classLoader)
                val dispatch = bridge.findMethod {
                    matcher {
                        declaredClass = owner
                        returnType = "void"
                        paramTypes(null, "java.lang.String", "int")
                        addUsingString("androidamap://route?")
                    }
                }.singleOrNull()?.getMethodInstance(context.classLoader)
                val showNotification = bridge.findMethod {
                    matcher { addUsingString("phrase_channel_id") }
                }.singleOrNull()?.getMethodInstance(context.classLoader)

                showNotification?.takeIf { it.parameterTypes.getOrNull(2) == Int::class.javaPrimitiveType }?.let { method ->
                    module.hook(method).setId("hypercopy_aicr_browser_notification_type").intercept { chain ->
                        val previous = websiteNotification.get()
                        websiteNotification.set(chain.getArg(2) == WEBSITE_TYPE)
                        try {
                            chain.proceed()
                        } finally {
                            if (previous == null) websiteNotification.remove() else websiteNotification.set(previous)
                        }
                    }
                }

                module.hook(targetPackage).setId("hypercopy_aicr_browser_package").intercept { chain ->
                    val browser = selectedBrowser(module, context)
                    if (chain.getArg(1) == WEBSITE_TYPE && browser != null) browser else chain.proceed()
                }
                installedCheck?.let { method ->
                    module.hook(method).setId("hypercopy_aicr_browser_available").intercept { chain ->
                        if (chain.getArg(1) == WEBSITE_TYPE && selectedBrowser(module, context) != null) true
                        else chain.proceed()
                    }
                }
                module.hook(dispatch ?: browserJump).setId("hypercopy_aicr_browser_open").intercept { chain ->
                    if (dispatch != null && chain.getArg(2) != WEBSITE_TYPE) return@intercept chain.proceed()
                    val browser = selectedBrowser(module, context)
                    val launchContext = chain.getArg(0) as? Context
                    val url = chain.getArg(1) as? String
                    if (browser == null || launchContext == null || url.isNullOrBlank()) {
                        chain.proceed()
                    } else {
                        val uri = Uri.parse(if (url.startsWith("http://", true) || url.startsWith("https://", true)) url else "https://$url")
                        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(browser)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val launched = runCatching {
                            launchContext.startActivity(intent)
                        }.onFailure { HookLog.w(module, "HyperCopy", "selected copy-direct browser could not open link", it) }.isSuccess
                        if (launched) null else chain.proceed()
                    }
                }
                browserIntent?.let { method ->
                    module.hook(method).setId("hypercopy_aicr_browser_intent").intercept { chain ->
                        val result = chain.proceed()
                        val browser = selectedBrowser(module, context)
                        if (result is Intent && browser != null) {
                            HookLog.d(module, "HyperCopy", "copy-direct browser intent -> $browser")
                            Intent(result).setComponent(null).setPackage(browser)
                        } else result
                    }
                }
                HookLog.d(module, "HyperCopy", "copy-direct browser methods: legacy=$browserJump, native=$browserIntent")
            }
            val notify = NotificationManager::class.java.getDeclaredMethod("notify", Int::class.javaPrimitiveType, Notification::class.java)
            module.hook(notify).setId("hypercopy_aicr_browser_notification").intercept { chain ->
                val notification = chain.getArg(1) as? Notification
                if (websiteNotification.get() == true && chain.getArg(0) == 111 && notification?.channelId == "phrase_channel_id") {
                    val browser = selectedBrowser(module, context)
                    if (browser != null) {
                        val copy = runCatching {
                            val copy = notification.clone()
                            val pm = context.packageManager
                            val icon = Icon.createWithBitmap(pm.getApplicationIcon(browser).toBitmap())
                            val pictures = Bundle(copy.extras.getBundle("miui.focus.pics") ?: Bundle())
                            pictures.putParcelable("miui.focus.pic_image", icon)
                            pictures.putParcelable("miui.land.pic_image", icon)
                            copy.extras.putBundle("miui.focus.pics", pictures)
                            copy.extras.putParcelable("miui.appIcon", icon)
                            val label = pm.getApplicationLabel(pm.getApplicationInfo(browser, 0))
                            copy.extras.putCharSequence(Notification.EXTRA_TITLE, label)
                            copy.extras.putCharSequence(Notification.EXTRA_TITLE_BIG, label)
                            copy
                        }.onFailure { HookLog.w(module, "HyperCopy", "update copy-direct browser notification failed", it) }.getOrNull()
                        if (copy != null) {
                            return@intercept chain.proceed(chain.getArgs().toTypedArray().apply { this[1] = copy })
                        }
                    }
                }
                chain.proceed()
            }
            installed = true
            HookLog.d(module, "HyperCopy", "AICR copy-direct browser hooks installed")
        } catch (error: Throwable) {
            HookLog.w(module, "HyperCopy", "install AICR copy-direct browser hooks failed", error)
        }
    }

    @JvmStatic
    fun updateCopyCues(module: XposedModule, context: Context, value: Any?) {
        if (!installed || value !is List<*>) return
        val browser = selectedBrowser(module, context) ?: return
        for (item in value) {
            val cue = item ?: continue
            runCatching {
                if (cue.javaClass.getMethod("getCategory").invoke(cue) != "copy_jump") return@runCatching
                val cueId = cue.javaClass.getMethod("getCueId").invoke(cue)?.toString().orEmpty()
                if (cueId.startsWith("hypercopy.copy_jump#")) return@runCatching
                val display = cue.javaClass.getMethod("getDisplay").invoke(cue) ?: return@runCatching
                val targetField = display.javaClass.getField("targetPackage")
                val oldPackage = targetField.get(display) as? String ?: return@runCatching
                // Shopping apps can also handle arbitrary web URLs; that does not make their native cues browser cues.
                if (oldPackage != "com.android.browser" && oldPackage != "com.mi.globalbrowser" && oldPackage != browser) return@runCatching
                targetField.set(display, browser)
                val card = display.javaClass.getField("briefCard").get(display) ?: return@runCatching
                val title = card.javaClass.getField("title").get(card)
                val icon = card.javaClass.getField("startIcon").get(card)
                title?.javaClass?.getField("text")?.set(title, "打开" + context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(browser, 0)))
                title?.javaClass?.getField("highlights")?.set(title, emptyList<Any>())
                icon?.javaClass?.getField("type")?.set(icon, "application")
                icon?.javaClass?.getField("value")?.set(icon, browser)
                HookLog.d(module, "HyperCopy", "copy-direct browser cue: $oldPackage -> $browser")
            }.onFailure { HookLog.w(module, "HyperCopy", "update copy-direct browser cue failed", it) }
        }
    }

    private fun selectedBrowser(module: XposedModule, context: Context): String? = runCatching {
        module.getRemotePreferences(Config.PREFS_NAME).getString(Config.KEY_COPY_DIRECT_BROWSER, "")
            ?.takeIf { it.isNotBlank() && canHandleWeb(context, it) }
    }.getOrNull()

    private fun canHandleWeb(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return listOf("http:", "https:").any { scheme ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(scheme)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(packageName)
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).isNotEmpty()
        }
    }
}
