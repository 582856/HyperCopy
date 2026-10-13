package io.github.hypercopy.data.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CopyDirectBrowserTest {
    @Test
    fun selectionPersistsAndCanBeDisabled() {
        val repository = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = repository.readCopyDirectBrowser()
        try {
            repository.persistCopyDirectBrowser("com.android.chrome")
            assertEquals("com.android.chrome", repository.readCopyDirectBrowser())
            repository.persistCopyDirectBrowser("")
            assertEquals("", repository.readCopyDirectBrowser())
        } finally {
            repository.persistCopyDirectBrowser(original)
        }
    }
}
