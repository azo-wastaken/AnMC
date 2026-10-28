package com.anmc.net

import org.junit.Assert.*
import org.junit.Test

class ConfigFilesTest {

    @Test
    fun testValidConfigPath() {
        assertTrue(validConfigPath("plugins/config.yml"))
        assertTrue(validConfigPath("config/settings.json"))
        assertTrue(validConfigPath("server.properties"))
        assertTrue(validConfigPath("defaultconfigs/mod-common.toml"))

        // Invalid paths
        assertFalse(validConfigPath(""))
        assertFalse(validConfigPath("/plugins/config.yml"))
        assertFalse(validConfigPath("plugins/config.yml/"))
        assertFalse(validConfigPath("../secret.txt"))
        assertFalse(validConfigPath("plugins/../config.yml"))
        assertFalse(validConfigPath("plugins/config.exe"))
        assertFalse(validConfigPath("-invalid/config.yml"))
    }

    @Test
    fun testConfigRootsFor() {
        assertEquals(listOf("plugins", "config"), configRootsFor("Paper"))
        assertEquals(listOf("config"), configRootsFor("Fabric"))
        assertEquals(listOf("config", "defaultconfigs"), configRootsFor("Forge"))
        assertEquals(listOf("plugins"), configRootsFor("PowerNukkitX"))
        assertEquals(emptyList<String>(), configRootsFor("Vanilla"))
    }

    @Test
    fun testConfigGroup() {
        assertEquals("Server files", configGroup("server.properties"))
        assertEquals("plugins/Essentials", configGroup("plugins/Essentials/config.yml"))
        assertEquals("config", configGroup("config/my-mod.toml"))
    }

    @Test
    fun testEncodeAndDecodeConfig() {
        val sampleText = "hello = \"world\"\noption = true"
        val encoded = encodeConfig(sampleText, crlf = false)
        val decoded = decodeConfig(encoded)

        assertNotNull(decoded)
        assertEquals(sampleText, decoded!!.text)
        assertFalse(decoded.crlf)

        val crlfText = "line1\r\nline2"
        val encodedCrlf = encodeConfig(crlfText, crlf = true)
        val decodedCrlf = decodeConfig(encodedCrlf)

        assertNotNull(decodedCrlf)
        assertEquals("line1\nline2", decodedCrlf!!.text)
        assertTrue(decodedCrlf.crlf)
    }

    @Test
    fun testConfigWarning() {
        // Empty JSON warning
        val emptyWarn = configWarning("config.json", "   ")
        assertNotNull(emptyWarn)
        assertTrue(emptyWarn!!.contains("empty"))

        // YAML tab warning
        val yamlTabWarn = configWarning("config.yml", "key:\n\tvalue: 1")
        assertNotNull(yamlTabWarn)
        assertTrue(yamlTabWarn!!.contains("tab characters"))

        // Valid YAML
        assertNull(configWarning("config.yml", "key:\n  value: 1"))
    }
}
