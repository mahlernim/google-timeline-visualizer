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
    JOURNAL,
    PRIVATE,
    IMPORT,
}

object JournalOnboardingPages {
    val all = listOf(
        JournalOnboardingPage(
            R.string.onboarding_v11_journal_title,
            R.string.onboarding_v11_journal_body,
            illustration = JournalOnboardingIllustration.JOURNAL,
        ),
        JournalOnboardingPage(
            R.string.onboarding_v11_private_title,
            R.string.onboarding_v11_private_body,
            R.string.onboarding_v11_private_note,
            JournalOnboardingIllustration.PRIVATE,
        ),
        JournalOnboardingPage(
            R.string.onboarding_v11_start_title,
            R.string.onboarding_v11_start_body,
            illustration = JournalOnboardingIllustration.IMPORT,
        ),
    )
}
