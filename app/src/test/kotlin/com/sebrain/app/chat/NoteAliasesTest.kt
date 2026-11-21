package com.sebrain.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteAliasesTest {
    private val nothingTaken = NoteAliases.Brain(isTaken = { false }, tagsInUse = emptySet(), isGeneric = { false })

    @Test
    fun aLeadingArticleIsDropped() {
        assertEquals(listOf("shell"), NoteAliases.derive("The shell"))
        assertEquals(listOf("memoria virtual"), NoteAliases.derive("La memoria virtual"))
    }

    @Test
    fun aTrailingParenthesisIsSplitOut() {
        assertEquals(listOf("Transmission Control Protocol", "TCP"), NoteAliases.derive("Transmission Control Protocol (TCP)"))
    }

    @Test
    fun aPlainTitleDerivesNothing() {
        assertEquals(emptyList<String>(), NoteAliases.derive("systemd"))
        // "The" alone is not a title with an article.
        assertEquals(emptyList<String>(), NoteAliases.derive("The"))
    }

    @Test
    fun theModelsAliasesComeFirstThenTheDerivedOnes() {
        val chosen = NoteAliases.choose("The shell", listOf("bash"), nothingTaken)
        assertEquals(listOf("bash", "shell"), chosen)
    }

    @Test
    fun theOwnTitleShortNamesAndDuplicatesAreDropped() {
        val chosen = NoteAliases.choose("Cron Jobs", listOf("cron jobs", "os", "cron", "Cron", "  "), nothingTaken)
        assertEquals(listOf("cron"), chosen)
    }

    @Test
    fun aNameAnotherNoteAnswersToIsDropped() {
        val brain = nothingTaken.copy(isTaken = { it.equals("kernel", ignoreCase = true) })
        assertEquals(listOf("linux kernel core"), NoteAliases.choose("Linux kernel", listOf("kernel", "linux kernel core"), brain))
    }

    @Test
    fun aTagInUseOrAGenericWordIsDropped() {
        val brain = nothingTaken.copy(tagsInUse = setOf("linux"), isGeneric = { it == "system" })
        assertEquals(listOf("apt"), NoteAliases.choose("Package Management with apt", listOf("Linux", "system", "apt"), brain))
    }

    @Test
    fun theNotesOwnTagsAreDropped() {
        // Seen on the phone: "core" and "dir" were the note's own tags, not yet in the brain.
        val chosen = NoteAliases.choose("The Linux Kernel", listOf("kernel", "core", "kmod"), nothingTaken, ownTags = listOf("linux", "core", "os"))
        assertEquals(listOf("kernel", "kmod", "Linux Kernel"), chosen)
    }

    @Test
    fun anOwnTagThatIsAWordOfTheTitleIsKept() {
        // Seen on the phone: tagged #kernel and #shell, the notes lost their best aliases.
        val kernel = NoteAliases.choose("The Linux Kernel", listOf("kernel", "core"), nothingTaken, ownTags = listOf("kernel", "core"))
        assertEquals(listOf("kernel", "Linux Kernel"), kernel)
        assertEquals(listOf("Shell"), NoteAliases.choose("The Shell", emptyList(), nothingTaken, ownTags = listOf("shell")))
        // A tag of other notes in the brain stays out, title word or not.
        val brain = nothingTaken.copy(tagsInUse = setOf("kernel"))
        assertEquals(listOf("Linux Kernel"), NoteAliases.choose("The Linux Kernel", listOf("kernel"), brain))
    }

    @Test
    fun atMostThreeAliasesAndLongOnesAreDropped() {
        val long = "a".repeat(41)
        val chosen = NoteAliases.choose("Secure Shell (SSH)", listOf(long, "ssh client", "openssh", "remote shell", "sshd"), nothingTaken)
        assertEquals(listOf("ssh client", "openssh", "remote shell"), chosen)
    }

    @Test
    fun theGenericCheckRunsOnlyForSurvivors() {
        val checked = ArrayList<String>()
        val brain = nothingTaken.copy(isGeneric = { checked += it; false })
        NoteAliases.choose("Cron Jobs", listOf("cron jobs", "os", "cron", "crontab", "anacron", "at"), brain)
        // The own title and "os"/"at" (too short) never reach the database; it stops at three.
        assertEquals(listOf("cron", "crontab", "anacron"), checked)
    }
}
