package com.sebrain.app.ui

/** A short user guide, written in Markdown and shown with the app's own renderer. */
data class Guide(val title: String, val summary: String, val body: String)

/** The guides in Profile › Guides. Keep them true to what the app does today. */
val GUIDES = listOf(
    Guide(
        "Getting started",
        "The four tabs and the first steps",
        """
        SeBrain is a private memory with an AI that runs on this phone.

        - **Notes**: write and read your notes. Link one note to another with `[[Note title]]` and tag it with `#tag`.
        - **Graph**: see how your notes connect.
        - **Ask**: ask about anything you wrote. Answers cite the notes they used, as `[1]`, `[2]`.
        - **Profile**: settings, privacy, backup, these guides and the models on this phone.

        Ask needs two models: one for search and one for chat. Profile › Device & models suggests the right ones for this phone.
        """.trimIndent(),
    ),
    Guide(
        "How your memory works",
        "Links, tags, properties, dates and daily notes",
        """
        Every note is Markdown. The AI finds notes by meaning and by words, then follows their links.

        - **Links**: `[[Another note]]` connects two notes. Notes that are linked a lot count as more central.
        - **Aliases**: other names for a note, in its properties as `aliases: [Name, Other name]`.
        - **Properties**: lines like `status: done` or `due: 2026-12-01` at the top of a note, between `---` lines. Ask "notes with status: done" or "notes due before December".
        - **Dates**: ask about "last week" or "in March", and the AI looks at notes written then.
        - **Daily notes**: in Ask, write `add to today's note: …` and it is saved under today's date, in the `Daily` folder.
        - **Templates**: notes in the `Templates` folder are not used as memory. A `Templates/Daily` note shapes each new daily note.

        The AI changes a note only when you ask, and you approve every change before it is saved.
        """.trimIndent(),
    ),
    Guide(
        "Saving from other apps",
        "Share text and links into SeBrain",
        """
        In any app, tap **Share › SeBrain**. It works even when the phone is locked.

        What you share is encrypted at once and waits in an inbox. It joins your notes the next time you open SeBrain with the phone unlocked. The same text shared twice is saved once.

        Shared text is treated as information, never as instructions: lines in it that speak to an AI are removed before the AI reads it.
        """.trimIndent(),
    ),
    Guide(
        "Brains",
        "Separate memories in one app",
        """
        A brain is a separate set of notes. Pick it at the top of Notes, Graph and Ask: each one answers only from its own notes.

        Use brains to keep work and personal memory apart, or to give a project its own memory.
        """.trimIndent(),
    ),
    Guide(
        "Privacy and external AI",
        "What leaves the phone, and when",
        """
        Your notes, chats and search index are encrypted on this phone. SeBrain has no analytics and no crash reporting.

        You can add an external AI provider in Profile › Settings. By default only what you type is sent to it. A note is sent only when you attach it, and you see the request before it leaves.

        **Require Tailscale** makes every connection go through your tailnet, and nothing connects while Tailscale is off.
        """.trimIndent(),
    ),
    Guide(
        "Backup and restore",
        "Keep a copy of your memory",
        """
        Profile › Backup & restore exports everything into one encrypted file. Choose a passphrase you will remember: without it the backup cannot be opened.

        Restoring replaces the current memory. Until you delete the previous one, **Undo restore** brings it back.
        """.trimIndent(),
    ),
    Guide(
        "Models and hardware",
        "What runs where on this phone",
        """
        - **Search model**: finds notes by meaning. On a Tensor G5 it runs on the NPU.
        - **Chat model**: writes the answers. It runs on the GPU.

        Every model is checked against a SHA-256 fingerprint before it is used. You can also import a model file you already have.
        """.trimIndent(),
    ),
)
