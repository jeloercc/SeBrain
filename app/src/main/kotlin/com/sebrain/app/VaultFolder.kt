package com.sebrain.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.sebrain.cognition.MarkdownInterop

/**
 * A folder the user picked with the system file picker, read or written as an Obsidian vault:
 * a tree of `.md` files. It uses the framework's `DocumentsContract` directly, so no library is
 * added. Files written here are outside the encrypted vault, in plain text; that is the user's
 * explicit choice for each export.
 */
class VaultFolder(private val resolver: ContentResolver, private val tree: Uri) {
    /** A Markdown file of the folder: its path under the folder, with its name, and where to read it. */
    class MarkdownFile(val relative: String, val uri: Uri, val size: Long)

    class Listing(val notes: List<MarkdownFile>, val ignored: Int)

    /**
     * Every Markdown note under the folder. Hidden folders such as `.obsidian` and `.trash` are
     * not entered; other files (images, PDFs, canvases) are counted in [Listing.ignored].
     */
    fun list(): Listing {
        val notes = ArrayList<MarkdownFile>()
        var ignored = 0
        val pending = ArrayDeque<Pair<String, String>>()
        pending += DocumentsContract.getTreeDocumentId(tree) to ""
        while (pending.isNotEmpty()) {
            val (folderId, prefix) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
            resolver.query(
                children,
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == Document.MIME_TYPE_DIR) {
                        pending += id to "$prefix$name/"
                    } else if (MarkdownInterop.notePath(prefix + name) != null) {
                        notes += MarkdownFile(prefix + name, DocumentsContract.buildDocumentUriUsingTree(tree, id), if (c.isNull(3)) 0 else c.getLong(3))
                    } else {
                        ignored++
                    }
                }
            }
        }
        return Listing(notes.sortedBy { it.relative.lowercase() }, ignored)
    }

    /** The text of a note file, or null if it is larger than [MAX_BYTES] or cannot be read. */
    fun read(file: MarkdownFile): String? {
        if (file.size > MAX_BYTES) return null
        return runCatching {
            resolver.openInputStream(file.uri)?.use { input ->
                val bytes = input.readNBytes(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) null else bytes.toString(Charsets.UTF_8).removePrefix(BOM)
            }
        }.getOrNull()
    }

    private val folders = HashMap<String, Uri>()

    /**
     * Writes [text] as the file named by [parts]: its folders, then its file name. Folders are
     * created as needed. Returns false if the provider refused.
     */
    fun write(parts: List<String>, text: String): Boolean = runCatching {
        var parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var key = ""
        for (folder in parts.dropLast(1)) {
            key += "$folder/"
            parent = folders.getOrPut(key) {
                existing(parent, folder) ?: DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, folder)
                    ?: error("Could not create the folder $folder")
            }
        }
        val file = DocumentsContract.createDocument(resolver, parent, "text/markdown", parts.last()) ?: error("Could not create ${parts.last()}")
        resolver.openOutputStream(file)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Could not write ${parts.last()}")
        true
    }.getOrDefault(false)

    /** A folder named [name] directly under [parent], if there is one already. */
    private fun existing(parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)
            ?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(2) == Document.MIME_TYPE_DIR && c.getString(1).equals(name, ignoreCase = true)) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    }
                }
            }
        return null
    }

    private companion object {
        /** A note larger than this is almost certainly not a note; it is skipped and reported. */
        const val MAX_BYTES = 2 * 1024 * 1024

        /** The byte-order mark some editors put at the start of a file; it is not part of the note. */
        const val BOM = "\uFEFF"
    }
}
