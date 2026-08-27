package dev.mahlernim.timelinevisualizer.journal

enum class JournalEntryDestination {
    VIDEOS,
    CREATE,
    JOURNAL_SETUP,
    JOURNAL_ONBOARDING,
}

/** Keeps Journal onboarding decisions separate from video customization Settings. */
object JournalSetupNavigation {
    fun defaultDestination(
        isJournalLab: Boolean,
        hasJournal: Boolean,
        onboardingCompleted: Boolean,
    ): JournalEntryDestination = when {
        !isJournalLab || hasJournal || onboardingCompleted -> JournalEntryDestination.VIDEOS
        else -> JournalEntryDestination.JOURNAL_ONBOARDING
    }

    fun createDestination(isJournalLab: Boolean, hasUsableJournal: Boolean): JournalEntryDestination =
        if (isJournalLab && !hasUsableJournal) {
            JournalEntryDestination.JOURNAL_SETUP
        } else {
            JournalEntryDestination.CREATE
        }
}
