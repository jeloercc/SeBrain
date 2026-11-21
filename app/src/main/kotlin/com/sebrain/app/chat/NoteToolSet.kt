package com.sebrain.app.chat

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * The tools the model may call to work with the vault (LiteRT-LM 0.18.1, Gemma 4 function
 * calling). Only their names, descriptions and parameters reach the model: calls run in manual
 * mode, and the app executes each one itself ([com.sebrain.core.ml.ChatEngine.replyWithTools]),
 * so every read stays in the vault's own brain and every write goes through the user's review.
 * The methods below therefore never run.
 *
 * LiteRT-LM names the tools from the methods in snake case: search_notes, read_note, create_note.
 */
class NoteToolSet : ToolSet {
    @Tool(
        description = "Search the user's own notes. Use it when the message is about the user's life, plans, people, " +
            "things or anything they may have written down. Do not use it for general knowledge.",
    )
    fun searchNotes(@ToolParam(description = "What to look for, in a few words") query: String): String = NOT_RUN

    @Tool(description = "Read one of the user's notes in full, by its exact title.")
    fun readNote(@ToolParam(description = "The exact title of the note") title: String): String = NOT_RUN

    @Tool(
        description = "Draft a new note for the user to review before it is saved. Use it only when the user asks to " +
            "save, write down, note or remember something.",
    )
    fun createNote(
        @ToolParam(description = "A short title for the note") title: String,
        @ToolParam(description = "The note's content, in Markdown") content: String,
    ): String = NOT_RUN

    companion object {
        const val SEARCH = "search_notes"
        const val READ = "read_note"
        const val CREATE = "create_note"
        private const val NOT_RUN = "The app runs this tool itself."
    }
}
