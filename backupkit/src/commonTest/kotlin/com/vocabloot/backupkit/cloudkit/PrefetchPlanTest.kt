package com.vocabloot.backupkit.cloudkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrefetchPlanTest {

    @Test
    fun every_uncached_record_is_handed_over_once_in_batches() {
        val names = (1..70).map { "r$it" } + listOf("r1", "r2")
        val batches = PrefetchPlan.batches(names, isCached = { it == "r3" }, batch = 32)

        assertEquals(listOf(32, 32, 5), batches.map { it.size })
        assertEquals(69, batches.flatten().toSet().size)
        assertFalse("r3" in batches.flatten(), "a cached record is not fetched again")
        assertEquals("r1", batches.first().first(), "plan order is kept")
    }

    @Test
    fun nothing_to_fetch_submits_nothing() {
        assertEquals(emptyList(), PrefetchPlan.batches(listOf("a", "b"), isCached = { true }))
        assertEquals(emptyList(), PrefetchPlan.batches(emptyList(), isCached = { false }))
    }

    @Test
    fun a_batch_size_must_be_positive() {
        assertFailsWith<IllegalArgumentException> { PrefetchPlan.batches(listOf("a"), isCached = { false }, batch = 0) }
    }

    @Test
    fun only_this_library_prefetches_are_reattached() {
        assertTrue(PrefetchPlan.isOwnPrefetch("backupkit.prefetch"))
        assertFalse(PrefetchPlan.isOwnPrefetch(null), "an upload has no name")
        assertFalse(PrefetchPlan.isOwnPrefetch("someone.else"))
    }
}
