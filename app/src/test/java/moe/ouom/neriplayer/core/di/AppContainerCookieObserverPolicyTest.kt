package moe.ouom.neriplayer.core.di

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContainerCookieObserverPolicyTest {
    @Test
    fun `cookie observer resolves the lazy client before acquiring the account lock`() {
        val directory = File(System.getProperty("user.dir") ?: ".")
        val sourceFile = generateSequence(directory) { it.parentFile }
            .map { File(it, "app/src/main/java/moe/ouom/neriplayer/core/di/AppContainer.kt") }
            .first { it.isFile }
        val observer = sourceFile.readText()
            .substringAfter("private fun startCookieObserver() {")
            .substringBefore("private fun startYouTubeAuthObserver()")
        val capture = observer.indexOf("val client = neteaseClient")
        val collection = observer.indexOf(".onEach")

        // The lazy initializer takes the account lock itself. Resolving it under that lock
        // reverses the order used by HomeViewModel and can deadlock the first frame.
        assertTrue(capture >= 0 && capture < collection)
        assertFalse(observer.substring(collection).contains("neteaseClient"))
        assertTrue(observer.contains("repository.withCurrentCookiesIfMatches(cookies)"))
        assertTrue(observer.contains("client.setPersistedCookies(currentCookies)"))
    }
}
