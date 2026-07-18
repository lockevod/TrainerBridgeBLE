package com.enderthor.trainerbridgeble

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorrectedFeedTest {
    @After fun tearDown() = CorrectedFeed.clear()

    @Test fun push_then_read() = runBlocking {
        CorrectedFeed.push(120, 8.0, 90, now = 1000L)
        assertEquals(120, CorrectedFeed.power.first())
        assertEquals(8.0, CorrectedFeed.speedMps.first()!!, 1e-9)
        assertEquals(90, CorrectedFeed.cadence.first())
        assertEquals(1000L, CorrectedFeed.lastMs.first())
    }

    @Test fun clear_resets_to_null() = runBlocking {
        CorrectedFeed.push(120, 8.0, 90, now = 1000L)
        CorrectedFeed.clear()
        assertNull(CorrectedFeed.power.first())
        assertNull(CorrectedFeed.speedMps.first())
        assertNull(CorrectedFeed.cadence.first())
        assertEquals(0L, CorrectedFeed.lastMs.first())
    }

    @Test fun push_updates_timestamp_each_time() = runBlocking {
        CorrectedFeed.push(100, null, null, now = 1000L)
        CorrectedFeed.push(105, null, null, now = 1250L)
        assertEquals(105, CorrectedFeed.power.first())
        assertEquals(1250L, CorrectedFeed.lastMs.first())
    }
}
