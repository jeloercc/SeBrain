package com.sebrain.core.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetTest {
    private fun tailnet(address: String) = TailnetAddresses.isTailnet(InetAddress.getByName(address))

    @Test
    fun recognizesTailscaleAddressesOnly() {
        assertTrue(tailnet("100.64.0.1"))
        assertTrue(tailnet("100.101.102.103"))
        assertTrue(tailnet("100.127.255.254"))
        assertTrue(tailnet("fd7a:115c:a1e0::1"))
        assertFalse(tailnet("100.63.255.255"))
        assertFalse(tailnet("100.128.0.1"))
        assertFalse(tailnet("192.168.1.10"))
        assertFalse(tailnet("10.0.0.1"))
        assertFalse(tailnet("8.8.8.8"))
        assertFalse(tailnet("fd7a:115c:a1e1::1"))
        assertFalse(tailnet("::1"))
    }

    @Test
    fun parsesSearchResults() {
        val models = HuggingFaceClient.parseModels(
            """[{"id":"a/b","downloads":12,"likes":3,"gated":false},{"id":"c/d","gated":"manual"},{"id":"e/f","gated":null}]""",
        )
        assertEquals(listOf("a/b", "c/d", "e/f"), models.map { it.repo })
        assertEquals(listOf(false, true, false), models.map { it.gated })
        assertEquals(12L, models[0].downloads)
    }

    @Test
    fun listsOnlyModelFilesWithAPublishedHash() {
        val hash = "a".repeat(64)
        val files = HuggingFaceClient.parseFiles(
            "a/b",
            """[{"path":"README.md","size":10},
                {"path":"model.litertlm","size":2000,"lfs":{"oid":"$hash"}},
                {"path":"sub/other.litertlm","size":5,"lfs":{"oid":"short"}},
                {"path":"weights.bin","size":5,"lfs":{"oid":"$hash"}}]""",
        )
        assertEquals(1, files.size)
        assertEquals("model.litertlm", files[0].fileName)
        assertEquals(2000L, files[0].sizeBytes)
        assertEquals(hash, files[0].sha256)
    }
}
