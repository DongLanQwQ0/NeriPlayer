package moe.ouom.neriplayer.testing

import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Capability check only: no notification, playback or application data is changed. */
@ManualInstrumentedTest
@RequiresApi(35)
@RunWith(AndroidJUnit4::class)
class VivoRemoteDrawingDeviceTest {
    @Test
    fun platformCanInflateDrawingInstructionsWithoutAnXmlLayout() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVivoRemoteDrawing") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        assumeTrue(Build.MANUFACTURER.equals("vivo", ignoreCase = true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val version = RemoteViews.DrawInstructions.getSupportedVersion()
            val remote = RemoteViews(RemoteViews.DrawInstructions.Builder(emptyList()).build())
            val view = remote.apply(instrumentation.targetContext, null)
            println("Remote drawing: version=$version, renderer=${view.javaClass.name}")
            assertTrue("The firmware did not create a RemoteCompose renderer", view.javaClass.name.contains("RemoteCompose"))
        }
    }
}
