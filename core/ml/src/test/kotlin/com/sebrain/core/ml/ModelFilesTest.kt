package com.sebrain.core.ml

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ModelFilesTest {
    private val dir: File = Files.createTempDirectory("models").toFile()
    private val models = ModelFiles(dir)

    @Test
    fun unknownContentIsRejectedAndNothingIsKept() {
        assertNull(models.import("not a model".byteInputStream()))
        assertEquals(0, dir.listFiles()!!.size)
        assertFalse(models.isInstalled(ModelFiles.EMBEDDING_NPU))
    }

    @Test
    fun aFileWithTheRightNameButWrongContentDoesNotVerify() {
        dir.mkdirs()
        models.file(ModelFiles.EMBEDDING_CPU).writeText("tampered")
        assertFalse(models.verify(ModelFiles.EMBEDDING_CPU))
    }

    @Test
    fun hashIsLowercaseHexSha256() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ModelFiles.sha256("abc".byteInputStream()),
        )
    }
}
