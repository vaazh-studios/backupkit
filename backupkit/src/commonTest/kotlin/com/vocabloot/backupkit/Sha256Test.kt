package com.vocabloot.backupkit

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun sha256Hex_is_64_lowercase_hex_chars() {
        val hex = sha256Hex("hello".encodeToByteArray())
        assertEquals(64, hex.length)
        assertEquals(hex, hex.lowercase())
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hex)
    }

    @Test
    fun sha256Hex_of_empty_input() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
    }

    @Test
    fun defaultMimeType_by_extension() {
        assertEquals("application/json", defaultMimeType("backup.json"))
        assertEquals("image/jpeg", defaultMimeType("items/a.jpg"))
        assertEquals("image/jpeg", defaultMimeType("items/a.JPEG"))
        assertEquals("image/png", defaultMimeType("a.png"))
        assertEquals("image/webp", defaultMimeType("a.webp"))
        assertEquals("text/plain", defaultMimeType("notes.txt"))
        assertEquals("application/octet-stream", defaultMimeType("blob"))
    }
}
