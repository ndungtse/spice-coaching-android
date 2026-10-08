package com.medtroniclabs.microcoaching.ai.model

import com.medtroniclabs.microcoaching.ai.embedding.EncoderVerdict
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the one on-device opt-in unlocks. The failures these guard against are the ones a CHW
 * would notice: a language model loaded on a build that switched it off, dense retrieval
 * running after an opt-out, or an offer for a download that cannot happen.
 */
class OnDeviceSetupTest {

    private val mib = 1024L * 1024L

    @Test
    fun `the language model never runs with the switch off`() {
        assertTrue(OnDeviceSetup.rewriteActive(true, isLowEndDevice = false, choice = LocalModelChoice.ENABLED))
        assertFalse(OnDeviceSetup.rewriteActive(false, isLowEndDevice = false, choice = LocalModelChoice.ENABLED))
    }

    @Test
    fun `smarter search follows consent, not files on disk`() {
        assertTrue(OnDeviceSetup.smarterSearchActive(true, deviceEligible = true, choice = LocalModelChoice.ENABLED))
        // Opted out, download kept: the user asked for standard search.
        assertFalse(OnDeviceSetup.smarterSearchActive(true, deviceEligible = true, choice = LocalModelChoice.DISABLED))
        assertFalse(OnDeviceSetup.smarterSearchActive(false, deviceEligible = true, choice = LocalModelChoice.ENABLED))
        assertFalse(OnDeviceSetup.smarterSearchActive(true, deviceEligible = false, choice = LocalModelChoice.ENABLED))
    }

    @Test
    fun `a phone sold as 2 GB can run the encoder, a smaller one cannot`() {
        // Such phones report about 1.7–1.9 GiB once the kernel has taken its share.
        assertTrue(OnDeviceSetup.encoderDeviceEligible(null, is64BitProcess = true, totalMemBytes = 1800 * mib, minRamMb = 1536))
        assertTrue(OnDeviceSetup.encoderDeviceEligible(null, is64BitProcess = true, totalMemBytes = 1536 * mib, minRamMb = 1536))
        assertFalse(OnDeviceSetup.encoderDeviceEligible(null, is64BitProcess = true, totalMemBytes = 1200 * mib, minRamMb = 1536))
    }

    @Test
    fun `a 32-bit process cannot, whatever its RAM or the QA override`() {
        // The SDK ships arm64-v8a native code only, so the encoder would download and never load.
        assertFalse(OnDeviceSetup.encoderDeviceEligible(null, is64BitProcess = false, totalMemBytes = 8192 * mib, minRamMb = 1536))
        assertFalse(OnDeviceSetup.encoderDeviceEligible(false, is64BitProcess = false, totalMemBytes = 8192 * mib, minRamMb = 1536))
        // On a 64-bit process the override wins either way.
        assertFalse(OnDeviceSetup.encoderDeviceEligible(true, is64BitProcess = true, totalMemBytes = 8192 * mib, minRamMb = 1536))
        assertTrue(OnDeviceSetup.encoderDeviceEligible(false, is64BitProcess = true, totalMemBytes = 1024 * mib, minRamMb = 1536))
    }

    @Test
    fun `with the rewrite off, only a fetchable encoder is offered`() {
        assertTrue(OnDeviceSetup.setupAvailable(false, isLowEndDevice = true, encoderVerdict = EncoderVerdict.DOWNLOAD))
        assertTrue(OnDeviceSetup.setupAvailable(false, isLowEndDevice = true, encoderVerdict = EncoderVerdict.READY))
        assertFalse(OnDeviceSetup.setupAvailable(false, isLowEndDevice = false, encoderVerdict = EncoderVerdict.BLOCKED_NO_TOKEN))
        assertFalse(OnDeviceSetup.setupAvailable(false, isLowEndDevice = false, encoderVerdict = EncoderVerdict.SKIPPED_FLAG_OFF))
        assertFalse(OnDeviceSetup.setupAvailable(false, isLowEndDevice = false, encoderVerdict = EncoderVerdict.SKIPPED_LOW_END))
        // With the rewrite on, the language model's tier alone decides, as before.
        assertTrue(OnDeviceSetup.setupAvailable(true, isLowEndDevice = false, encoderVerdict = EncoderVerdict.BLOCKED_NO_TOKEN))
        assertFalse(OnDeviceSetup.setupAvailable(true, isLowEndDevice = true, encoderVerdict = EncoderVerdict.DOWNLOAD))
    }

    @Test
    fun `chat open fetches the encoder only for a consented phone with nothing in flight`() {
        assertTrue(OnDeviceSetup.scheduleOnChatOpen(false, setupEnabled = true, state = ModelState.Idle, cancelledByUser = false))
        assertFalse(OnDeviceSetup.scheduleOnChatOpen(false, setupEnabled = false, state = ModelState.Idle, cancelledByUser = false))
        // The rewrite path keeps its own flow: the user starts it from the offer or the sheet.
        assertFalse(OnDeviceSetup.scheduleOnChatOpen(true, setupEnabled = true, state = ModelState.Idle, cancelledByUser = false))
        assertFalse(OnDeviceSetup.scheduleOnChatOpen(false, setupEnabled = true, state = ModelState.Ready(File("x")), cancelledByUser = false))
        assertFalse(OnDeviceSetup.scheduleOnChatOpen(false, setupEnabled = true, state = ModelState.Downloading(10), cancelledByUser = false))
        // A download the user cancelled is not restarted behind their back.
        assertFalse(OnDeviceSetup.scheduleOnChatOpen(false, setupEnabled = true, state = ModelState.Idle, cancelledByUser = true))
        // A failure waits for the user's retry rather than looping on every open.
        assertFalse(OnDeviceSetup.scheduleOnChatOpen(false, setupEnabled = true, state = ModelState.DownloadFailed("x"), cancelledByUser = false))
    }
}
