package com.blackclaw.android.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNoiseFilterUtilsTest {
    private val rootLeft = 0
    private val rootTop = 0
    private val rootRight = 1000
    private val rootBottom = 2000

    @Test
    fun `timestamp-like labels are filtered without English words`() {
        assertTrue(ChatNoiseFilterUtils.isLikelyTimestampLike("7:47"))
        assertTrue(ChatNoiseFilterUtils.isLikelyTimestampLike("07.47"))
        assertTrue(ChatNoiseFilterUtils.isLikelyTimestampLike("23/04"))
        assertFalse(ChatNoiseFilterUtils.isLikelyTimestampLike("bring 2 bottles"))
    }

    @Test
    fun `centered short labels are treated as system separators`() {
        assertTrue(ChatNoiseFilterUtils.isLikelyCenteredSystemLabel(380, 420, 620, 470, rootLeft, rootTop, rootRight, rootBottom, "Today"))
        assertTrue(ChatNoiseFilterUtils.isLikelyCenteredSystemLabel(380, 420, 620, 470, rootLeft, rootTop, rootRight, rootBottom, "今天"))
    }

    @Test
    fun `left aligned chat bubble is not treated as system label`() {
        assertFalse(ChatNoiseFilterUtils.isLikelyCenteredSystemLabel(40, 700, 500, 840, rootLeft, rootTop, rootRight, rootBottom, "Bring wine"))
        assertFalse(ChatNoiseFilterUtils.isLikelyNonMessageLabel(40, 700, 500, 840, rootLeft, rootTop, rootRight, rootBottom, "Bring wine"))
    }

    @Test
    fun `wide upper banner is filtered as non-message noise`() {
        assertTrue(ChatNoiseFilterUtils.isLikelyNonMessageLabel(120, 360, 900, 470, rootLeft, rootTop, rootRight, rootBottom, "Messages are end-to-end encrypted"))
    }
}
