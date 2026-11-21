package com.sebrain.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpTest {
    // The answer from the Pixel on 2026-11-19, before "explain all key points start for the first one".
    private val linux = """
        # What is Linux

        Linux is a family of free and open-source Unix-like operating systems.

        ## Key points
        - Open-source and free to use
        - Based on the Linux kernel
        - **Highly customizable**
        - Used in servers and embedded systems
        - Strong community support.

        ## Details
        Linux is a powerful operating system.

        Tags: linux, os, open-source, kernel
    """.trimIndent()

    @Test
    fun `the points of an answer are its list items, cleaned`() {
        assertEquals(
            listOf(
                "Open-source and free to use",
                "Based on the Linux kernel",
                "Highly customizable",
                "Used in servers and embedded systems",
                "Strong community support",
            ),
            FollowUp.points(linux),
        )
    }

    @Test
    fun `the first one names the first point`() {
        val target = FollowUp.of("explain all key points start for the first one create a note", linux)

        assertEquals("Open-source and free to use", target?.point)
    }

    @Test
    fun `ordinals and point numbers pick their point, in English and Spanish`() {
        assertEquals("Based on the Linux kernel", FollowUp.of("make a note about the second point", linux)?.point)
        assertEquals("Highly customizable", FollowUp.of("haz una nota del punto 3", linux)?.point)
        assertEquals("Strong community support", FollowUp.of("haz una nota del último", linux)?.point)
    }

    @Test
    fun `a number past the end of the list is not a point`() {
        val target = FollowUp.of("make a note about point 9", linux)

        assertNotNull(target)
        assertNull(target?.point)
    }

    @Test
    fun `each key point asks for one note per point`() {
        val target = FollowUp.of("make a note for each key point", linux)

        assertTrue(target!!.everyPoint)
        assertNull(target.point)
    }

    @Test
    fun `a note with a topic of its own is not a follow-up`() {
        assertNull(FollowUp.of("make a note in English about Linux", linux))
        assertNull(FollowUp.of("haz una nota sobre Docker", linux))
    }

    @Test
    fun `a note without a topic is about the previous answer`() {
        val target = FollowUp.of("make a note", linux)

        assertNotNull(target)
        assertNull(target?.point)
        assertFalse(target!!.everyPoint)
    }

    @Test
    fun `without a previous answer there is nothing to follow`() {
        assertNull(FollowUp.of("make a note of the first one", null))
    }

    @Test
    fun `the note prompt names the chosen point and asks for it alone`() {
        val prompt = Assistant.prompt(
            "explain all key points start for the first one create a note", emptyList(), asNote = true, tags = emptyList(),
            previousAnswer = linux,
        )

        assertTrue(prompt.contains("\"Open-source and free to use\""))
        assertTrue(prompt.contains("only about that point"))
    }

    @Test
    fun `the note prompt for every point lists them in order`() {
        val prompt = Assistant.prompt("make a note for each key point", emptyList(), asNote = true, tags = emptyList(), previousAnswer = linux)

        assertTrue(prompt.contains("1. Open-source and free to use"))
        assertTrue(prompt.contains("5. Strong community support"))
    }
}
