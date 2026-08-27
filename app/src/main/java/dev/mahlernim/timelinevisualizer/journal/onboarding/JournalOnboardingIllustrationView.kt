package dev.mahlernim.timelinevisualizer.journal.onboarding

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import dev.mahlernim.timelinevisualizer.R

/** Displays the polished, theme-aware vector artwork supplied for each Journal introduction page. */
class JournalOnboardingIllustrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    var illustration: JournalOnboardingIllustration = JournalOnboardingIllustration.JOURNAL
        set(value) {
            field = value
            setImageResource(value.drawableRes)
        }

    init {
        scaleType = ScaleType.FIT_CENTER
        adjustViewBounds = true
        setImageResource(illustration.drawableRes)
    }

    private val JournalOnboardingIllustration.drawableRes: Int
        get() = when (this) {
            JournalOnboardingIllustration.JOURNAL -> R.drawable.onboarding_v11_journal
            JournalOnboardingIllustration.PRIVATE -> R.drawable.onboarding_v11_private
            JournalOnboardingIllustration.IMPORT -> R.drawable.onboarding_v11_import
        }
}
