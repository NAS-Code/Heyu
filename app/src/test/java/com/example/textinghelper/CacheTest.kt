package com.example.textinghelper

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class CacheTest {
    // The startup cache fails silently, so check the classes really survive a save/load round trip.
    @Test fun diagnosticRoundTrips() {
        val contacts = listOf(1L, 2L).map { ContactStats(it, "P$it", "+1", 3, 2, 10, null, true, listOf(it)) }.toMutableList()
        val d = Diagnostic(1, 2, null, 3, 4, 5, 6, mapOf("+1555" to 7).toList(), contacts.sortedByDescending { it.count })
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(d) } }.toByteArray()
        assertEquals(d, ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() })
    }
}
