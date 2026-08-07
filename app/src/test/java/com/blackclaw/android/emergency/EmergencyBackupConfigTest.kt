package com.blackclaw.android.emergency

import org.junit.Assert.assertEquals
import org.junit.Test

class EmergencyBackupConfigTest {
    @Test fun `only encrypted WebDAV destinations are accepted`() {
        assertEquals("https://cloud.example/remote.php/dav/files/a", EmergencyBackupConfig.normalizeUrl(
            " HTTPS://cloud.example/remote.php/dav/files/a/ "))
        assertEquals("", EmergencyBackupConfig.normalizeUrl("http://cloud.example/files"))
        assertEquals("", EmergencyBackupConfig.normalizeUrl("file:///tmp/evidence"))
    }
}
