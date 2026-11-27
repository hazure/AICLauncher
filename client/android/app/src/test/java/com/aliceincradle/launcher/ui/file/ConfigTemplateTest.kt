package com.aliceincradle.launcher.ui.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTemplateTest {

    @Test
    fun matchesDoorstopTemplateAndSerializesFields() {
        val tpl = ConfigTemplateRegistry.matchTemplate("doorstop_config.ini")
        assertNotNull(tpl)
        val parsed = ConfigTemplateRegistry.parseIniConfig("[General]\nenabled=true\n", tpl)
        assertEquals(1, parsed.fields.size)
        assertEquals("enabled", parsed.fields.first().key)
        assertTrue(parsed.fields.first().comment.isNotBlank())

        val serialized = ConfigTemplateRegistry.serializeIniConfig(parsed)
        assertTrue(serialized.contains("[General]"))
        assertTrue(serialized.contains("enabled = true"))
    }

    @Test
    fun unmatchedConfigBuildsAllowlistedSearchUrlWithoutNetworkIo() {
        assertNull(ConfigTemplateRegistry.matchTemplate("unknown_mod.conf"))
        val url = ConfigTemplateRegistry.buildAllowlistedSearchUrl("unknown_mod.conf")
        assertTrue(url.startsWith("https://docs.bepinex.dev/search.html?q="))
    }
}
