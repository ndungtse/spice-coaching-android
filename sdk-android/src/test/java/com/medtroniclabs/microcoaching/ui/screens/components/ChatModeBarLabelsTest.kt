package com.medtroniclabs.microcoaching.ui.screens.components

import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ai.model.DownloadPhase
import com.medtroniclabs.microcoaching.domain.decision.AnswerMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** The mode bar must name what will answer, and what is downloading. */
class ChatModeBarLabelsTest {

    @Test
    fun `direct mode names smarter search only when it will run`() {
        assertEquals(
            R.string.chat_mode_bar_on_device_search,
            modeLabelRes(AnswerMode.ON_DEVICE_DIRECT, blockedByConnectivity = false, smarterSearch = true),
        )
        assertEquals(
            R.string.chat_mode_bar_on_device_direct,
            modeLabelRes(AnswerMode.ON_DEVICE_DIRECT, blockedByConnectivity = false, smarterSearch = false),
        )
        // Online, and an online choice blocked by connectivity, keep their own labels.
        assertEquals(R.string.chat_mode_bar_online, modeLabelRes(AnswerMode.ONLINE, false, smarterSearch = true))
        assertEquals(
            R.string.chat_mode_bar_on_device_online_unavailable,
            modeLabelRes(AnswerMode.ON_DEVICE_DIRECT, blockedByConnectivity = true, smarterSearch = true),
        )
    }

    /** States with no phase default to the language model, which this build never downloads. */
    @Test
    fun `with the rewrite off every download state names smarter search`() {
        assertEquals(R.string.chat_mode_bar_search_preparing, progressLabelRes(DownloadItemUiState.Preparing, rewriteAvailable = false))
        assertEquals(R.string.chat_mode_bar_search_progress, progressLabelRes(DownloadItemUiState.Downloading(40), rewriteAvailable = false))
        assertEquals(R.string.chat_mode_bar_search_paused, progressLabelRes(DownloadItemUiState.Paused(40), rewriteAvailable = false))
        assertEquals(
            R.string.chat_mode_bar_search_waiting_wifi,
            progressLabelRes(DownloadItemUiState.WaitingForNetwork(wifiOnly = true), rewriteAvailable = false),
        )
    }

    @Test
    fun `with the rewrite on the phase picks the label, as before`() {
        assertEquals(R.string.chat_mode_bar_upgrade_preparing, progressLabelRes(DownloadItemUiState.Preparing, rewriteAvailable = true))
        assertEquals(R.string.chat_mode_bar_upgrade_progress, progressLabelRes(DownloadItemUiState.Downloading(40), rewriteAvailable = true))
        assertEquals(
            R.string.chat_mode_bar_search_progress,
            progressLabelRes(DownloadItemUiState.Downloading(80, phase = DownloadPhase.EMBEDDINGS), rewriteAvailable = true),
        )
        assertEquals(R.string.chat_mode_bar_upgrade_preparing, progressLabelRes(DownloadItemUiState.Downloading(-1), rewriteAvailable = true))
    }
}
