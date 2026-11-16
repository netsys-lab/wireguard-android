/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.databinding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservableSortedKeyedArrayListTest {

    private data class Item(override val key: String) : Keyed<String>

    @Test
    fun testRemoveAllPredicate_doesNotThrowIndexOutOfBounds() {
        val list = ObservableSortedKeyedArrayList<String, Item>(naturalOrder())
        list.add(Item("alpha"))
        list.add(Item("bravo"))
        list.add(Item("charlie"))
        list.add(Item("delta"))

        // Removing the first element previously triggered Kotlin's filterInPlace to call set(0, "bravo"),
        // which threw IndexOutOfBoundsException("Wrong index given for element")
        val removed = list.removeAll { it.key == "alpha" }
        assertTrue(removed)
        assertEquals(3, list.size)
        assertEquals("bravo", list[0].key)
        assertEquals("charlie", list[1].key)
        assertEquals("delta", list[2].key)
    }

    @Test
    fun testRemoveAllPredicate_multipleElements() {
        val list = ObservableSortedKeyedArrayList<String, Item>(naturalOrder())
        list.add(Item("tunnel-1"))
        list.add(Item("tunnel-2"))
        list.add(Item("tunnel-3"))
        list.add(Item("tunnel-4"))

        val toKeep = setOf("tunnel-2", "tunnel-4")
        val removed = list.removeAll { it.key !in toKeep }
        assertTrue(removed)
        assertEquals(2, list.size)
        assertEquals("tunnel-2", list[0].key)
        assertEquals("tunnel-4", list[1].key)
    }

    @Test
    fun testRemoveAllPredicate_noMatch() {
        val list = ObservableSortedKeyedArrayList<String, Item>(naturalOrder())
        list.add(Item("tunnel-1"))
        list.add(Item("tunnel-2"))

        val removed = list.removeAll { it.key == "non-existent" }
        assertFalse(removed)
        assertEquals(2, list.size)
    }
}
