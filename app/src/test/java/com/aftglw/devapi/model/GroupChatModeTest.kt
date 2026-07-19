package com.aftglw.devapi.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupChatModeTest {
    @Test
    fun parsesPersistedKeys() {
        assertEquals(GroupChatMode.ROUND_ROBIN, GroupChatMode.fromKey("round_robin"))
        assertEquals(GroupChatMode.MENTION_ONLY, GroupChatMode.fromKey("mention_only"))
        assertEquals(GroupChatMode.FREE, GroupChatMode.fromKey("free"))
    }

    @Test
    fun unknownOrMissingKeyKeepsLegacyFreeDiscussion() {
        assertEquals(GroupChatMode.FREE, GroupChatMode.fromKey("legacy_value"))
        assertEquals(GroupChatMode.FREE, GroupChatMode.fromKey(null))
    }
}
