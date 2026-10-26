package com.example

import com.example.core.agent.ScreenXmlParser
import org.junit.Assert.*
import org.junit.Test

class ScreenXmlParserTest {

    private val sampleUiXml = """<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0"><node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.android.settings" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2400]"><node index="0" text="Settings" resource-id="android:id/title" class="android.widget.TextView" package="com.android.settings" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[72,120][450,220]" /><node index="1" text="" resource-id="com.android.settings:id/search_action_bar" class="android.widget.Button" package="com.android.settings" content-desc="Search settings" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[880,110][1008,230]" /><node index="2" text="Network &amp; internet" resource-id="android:id/title" class="android.widget.TextView" package="com.android.settings" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[100,300][980,450]" /></node></hierarchy>""".trimIndent()

    @Test
    fun testParseXmlNodes() {
        val nodes = ScreenXmlParser.parse(sampleUiXml)
        assertTrue("Nodes should not be empty", nodes.isNotEmpty())

        val settingsNode = nodes.find { it.text == "Settings" }
        assertNotNull("Settings node should be present", settingsNode)
        assertEquals("title", settingsNode?.resourceId)

        val searchNode = nodes.find { it.contentDescription == "Search settings" }
        assertNotNull("Search node should be present", searchNode)
        assertTrue(searchNode?.isClickable == true)
        assertEquals(Pair(944, 170), Pair(searchNode?.centerX ?: 0, searchNode?.centerY ?: 0))

        val networkNode = nodes.find { it.text.contains("Network") }
        assertNotNull("Network node should be present", networkNode)
    }

    @Test
    fun testParseCenterBounds() {
        val bounds = "[100,200][300,400]"
        val (cx, cy) = ScreenXmlParser.parseCenter(bounds)
        assertEquals(200, cx)
        assertEquals(300, cy)
    }

    @Test
    fun testToCompactSummary() {
        val nodes = ScreenXmlParser.parse(sampleUiXml)
        val summary = ScreenXmlParser.toCompactSummary(nodes)
        assertTrue(summary.contains("Settings"))
        assertTrue(summary.contains("Search settings"))
    }
}
