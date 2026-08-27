package dev.mahlernim.timelinevisualizer.journal

import org.junit.Assert.assertEquals
import org.junit.Test

class JournalSetupNavigationTest {
    @Test
    fun emptyUnseenJournalLabLaunchesOnboarding() {
        assertEquals(
            JournalEntryDestination.JOURNAL_ONBOARDING,
            JournalSetupNavigation.defaultDestination(
                isJournalLab = true,
                hasJournal = false,
                onboardingCompleted = false,
            ),
        )
    }

    @Test
    fun existingJournalSkipsLaunchOnboarding() {
        assertEquals(
            JournalEntryDestination.VIDEOS,
            JournalSetupNavigation.defaultDestination(
                isJournalLab = true,
                hasJournal = true,
                onboardingCompleted = false,
            ),
        )
    }

    @Test
    fun completedOnboardingOpensLibraryWithoutAJournal() {
        assertEquals(
            JournalEntryDestination.VIDEOS,
            JournalSetupNavigation.defaultDestination(
                isJournalLab = true,
                hasJournal = false,
                onboardingCompleted = true,
            ),
        )
    }

    @Test
    fun createUsesSetupOnlyWhenJournalIsUnavailable() {
        assertEquals(
            JournalEntryDestination.JOURNAL_SETUP,
            JournalSetupNavigation.createDestination(isJournalLab = true, hasUsableJournal = false),
        )
        assertEquals(
            JournalEntryDestination.CREATE,
            JournalSetupNavigation.createDestination(isJournalLab = true, hasUsableJournal = true),
        )
    }

    @Test
    fun productionNavigationIsUnchanged() {
        assertEquals(
            JournalEntryDestination.VIDEOS,
            JournalSetupNavigation.defaultDestination(
                isJournalLab = false,
                hasJournal = false,
                onboardingCompleted = false,
            ),
        )
        assertEquals(
            JournalEntryDestination.CREATE,
            JournalSetupNavigation.createDestination(isJournalLab = false, hasUsableJournal = false),
        )
    }
}
