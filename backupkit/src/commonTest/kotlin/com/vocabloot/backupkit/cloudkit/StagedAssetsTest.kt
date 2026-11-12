package com.vocabloot.backupkit.cloudkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StagedAssetsTest {

    @Test
    fun a_staged_file_moves_into_the_cache() {
        val moves = mutableListOf<Pair<String, String>>()
        val moved = StagedAssets.moveIntoCache("staged/a", "cache/a", exists = { true }, move = { from, to -> moves += from to to })

        assertTrue(moved)
        assertEquals(listOf("staged/a" to "cache/a"), moves)
    }

    @Test
    fun a_replayed_record_whose_staged_file_is_gone_is_skipped_not_moved() {
        var moveCalls = 0
        val moved = StagedAssets.moveIntoCache("staged/a", "cache/a", exists = { false }, move = { _, _ -> moveCalls++ })

        assertFalse(moved, "the record is fetched again later")
        assertEquals(0, moveCalls)
    }

    @Test
    fun a_move_that_throws_is_skipped_instead_of_escaping_the_callback() {
        val moved = StagedAssets.moveIntoCache(
            staged = "staged/a",
            target = "cache/a",
            exists = { true },
            move = { _, _ -> throw IllegalStateException("Source does not exist: staged/a") },
        )

        assertFalse(moved)
    }

    @Test
    fun an_exists_check_that_throws_is_skipped_too() {
        val moved = StagedAssets.moveIntoCache("staged/a", "cache/a", exists = { error("no access") }, move = { _, _ -> })

        assertFalse(moved)
    }
}
