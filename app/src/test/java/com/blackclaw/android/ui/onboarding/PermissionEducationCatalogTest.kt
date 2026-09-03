package com.blackclaw.android.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionEducationCatalogTest {

    @Test
    fun everyPermissionTopicHasExactlyOneExplanation() {
        val entries = PermissionEducationCatalog.all

        assertEquals(PermissionTopic.entries.size, entries.size)
        assertEquals(PermissionTopic.entries.toSet(), entries.map { it.topic }.toSet())
        assertEquals(entries.size, entries.map { it.topic }.distinct().size)
    }

    @Test
    fun explanationsAreCompleteForUserFacingRationale() {
        PermissionEducationCatalog.all.forEach { item ->
            assertTrue("title missing for ${item.topic}", item.title.isNotBlank())
            assertTrue("short reason missing for ${item.topic}", item.shortReason.isNotBlank())
            assertTrue("why-needed missing for ${item.topic}", item.whyNeeded.isNotBlank())
            assertTrue("denial impact missing for ${item.topic}", item.withoutIt.isNotBlank())
            assertTrue("privacy explanation missing for ${item.topic}", item.privacy.isNotBlank())
        }
    }

    @Test
    fun technicalEntriesAreNotPresentedAsRuntimeRequests() {
        val technical = PermissionEducationCatalog.all.filter { it.systemManaged }

        assertTrue(technical.isNotEmpty())
        assertTrue(technical.all { !it.optional })
    }
}
