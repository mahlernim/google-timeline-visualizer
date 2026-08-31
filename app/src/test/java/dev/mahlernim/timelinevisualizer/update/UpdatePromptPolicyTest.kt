package dev.mahlernim.timelinevisualizer.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePromptPolicyTest {
    @Test
    fun checksOnFirstRunAndOncePerDay() {
        val now = 10L * UpdatePromptPolicy.CHECK_INTERVAL_MILLIS

        assertTrue(UpdatePromptPolicy.shouldCheck(now, 0L, 0L))
        assertFalse(
            UpdatePromptPolicy.shouldCheck(
                now,
                now - UpdatePromptPolicy.CHECK_INTERVAL_MILLIS + 1L,
                now - UpdatePromptPolicy.CHECK_INTERVAL_MILLIS + 1L,
            ),
        )
        assertTrue(
            UpdatePromptPolicy.shouldCheck(
                now,
                now - UpdatePromptPolicy.CHECK_INTERVAL_MILLIS,
                now - UpdatePromptPolicy.CHECK_INTERVAL_MILLIS,
            ),
        )
    }

    @Test
    fun failedChecksUseAShortBoundedRetryInterval() {
        val now = 10L * UpdatePromptPolicy.CHECK_INTERVAL_MILLIS
        val lastSuccess = now - UpdatePromptPolicy.CHECK_INTERVAL_MILLIS

        assertFalse(
            UpdatePromptPolicy.shouldCheck(
                now,
                lastSuccess,
                now - UpdatePromptPolicy.FAILURE_RETRY_INTERVAL_MILLIS + 1L,
            ),
        )
        assertTrue(
            UpdatePromptPolicy.shouldCheck(
                now,
                lastSuccess,
                now - UpdatePromptPolicy.FAILURE_RETRY_INTERVAL_MILLIS,
            ),
        )
    }

    @Test
    fun clockMovingBackwardDoesNotSuppressChecksIndefinitely() {
        assertTrue(
            UpdatePromptPolicy.shouldCheck(
                nowMillis = 1_000L,
                lastSuccessfulCheckMillis = 2_000L,
                lastAttemptMillis = 2_000L,
            ),
        )
    }

    @Test
    fun onlyNewerVersionsArePrompted() {
        assertFalse(shouldPrompt(availableVersionCode = 50, installedVersionCode = 50))
        assertFalse(shouldPrompt(availableVersionCode = 49, installedVersionCode = 50))
        assertTrue(shouldPrompt(availableVersionCode = 51, installedVersionCode = 50))
    }

    @Test
    fun dismissedVersionReturnsAfterSevenDays() {
        val dismissedAt = 1_000L
        val update = AvailableAppUpdate(versionCode = 51)

        assertFalse(
            UpdatePromptPolicy.shouldPrompt(
                update,
                installedVersionCode = 50,
                dismissedVersionCode = 51,
                dismissedAtMillis = dismissedAt,
                nowMillis = dismissedAt + UpdatePromptPolicy.DISMISSAL_INTERVAL_MILLIS - 1L,
            ),
        )
        assertTrue(
            UpdatePromptPolicy.shouldPrompt(
                update,
                installedVersionCode = 50,
                dismissedVersionCode = 51,
                dismissedAtMillis = dismissedAt,
                nowMillis = dismissedAt + UpdatePromptPolicy.DISMISSAL_INTERVAL_MILLIS,
            ),
        )
    }

    @Test
    fun newerVersionOverridesAnEarlierDismissal() {
        assertTrue(
            UpdatePromptPolicy.shouldPrompt(
                AvailableAppUpdate(versionCode = 52),
                installedVersionCode = 50,
                dismissedVersionCode = 51,
                dismissedAtMillis = 1_000L,
                nowMillis = 2_000L,
            ),
        )
    }

    private fun shouldPrompt(availableVersionCode: Int, installedVersionCode: Int): Boolean =
        UpdatePromptPolicy.shouldPrompt(
            update = AvailableAppUpdate(versionCode = availableVersionCode),
            installedVersionCode = installedVersionCode,
            dismissedVersionCode = 0,
            dismissedAtMillis = 0L,
            nowMillis = 1_000L,
        )
}
