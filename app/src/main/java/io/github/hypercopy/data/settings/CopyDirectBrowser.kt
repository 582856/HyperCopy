package io.github.hypercopy.data.settings

import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri

data class CopyDirectBrowser(val packageName: String, val label: String)

fun loadCopyDirectBrowsers(packageManager: PackageManager): List<CopyDirectBrowser> =
    listOf("http:", "https:").flatMap { scheme ->
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse(scheme)).addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.MATCH_ALL,
        )
    }.distinctBy { it.activityInfo.packageName }
        .map { CopyDirectBrowser(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
        .sortedBy { it.label.lowercase() }
