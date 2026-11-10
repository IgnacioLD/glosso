package com.glosso.app

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackSoundsTest {

    @Test
    fun `feedback cues route to the media stream`() {
        val attributes = feedbackAttributes()

        assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
    }

    @Test
    fun `feedback cues stay short sonification cues`() {
        val attributes = feedbackAttributes()

        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, attributes.contentType)
    }
}
