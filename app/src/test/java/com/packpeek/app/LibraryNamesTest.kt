package com.packpeek.app

import com.packpeek.app.engine.risk.DexScanner
import org.junit.Assert.*
import org.junit.Test

/**
 * SDK classes are attributed to their library, not to the app. From a report on HOT51, where
 * Alipay, ZEGO and Drake code was listed as the app's own.
 */
class LibraryNamesTest {
    @Test
    fun liveStreamingAppSdksAreRecognized() {
        assertEquals("Alipay SDK", DexScanner.libraryName("Lcom/alipay/android/phone/mrpc/core/q;"))
        assertEquals("ZEGO SDK", DexScanner.libraryName("Lim/zego/internal/screencapture/ZegoScreenCaptureService;"))
        assertEquals("ZEGO SDK", DexScanner.libraryName("Lcom/zego/zegoavkit2/screencapture/ZegoScreenCaptureDevice;"))
        assertEquals("Drake libraries", DexScanner.libraryName("Lcom/drake/engine/utils/f;"))
    }

    @Test
    fun theAppsOwnCodeStaysTheApps() {
        assertNull(DexScanner.libraryName("Lcom/example/obs/player/ui/activity/mine/BecomeFamilyActivity;"))
        assertNull(DexScanner.libraryName("Lcom/sagadsg/user/Main;"))
    }
}
