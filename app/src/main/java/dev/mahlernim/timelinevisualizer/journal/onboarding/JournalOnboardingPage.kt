package dev.mahlernim.timelinevisualizer.journal.onboarding

import androidx.annotation.StringRes
import dev.mahlernim.timelinevisualizer.R

data class JournalOnboardingPage(
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
    @param:StringRes val noteRes: Int? = null,
    val illustration: JournalOnboardingIllustration,
)

enum class JournalOnboardingIllustration {
    VIDEO,
    HANDOFF,
    JOURNAL,
    PRIVATE,
    IMPORT,
}

object JournalOnboardingPages {
    val all = listOf(
        JournalOnboardingPage(
            R.string.onboarding_video_title,
            R.string.onboarding_video_body,
            illustration = JournalOnboardingIllustration.VIDEO,
        ),
        JournalOnboardingPage(
            R.string.onboarding_import_title,
            R.string.onboarding_import_body,
            illustration = JournalOnboardingIllustration.HANDOFF,
        ),
    )
}
