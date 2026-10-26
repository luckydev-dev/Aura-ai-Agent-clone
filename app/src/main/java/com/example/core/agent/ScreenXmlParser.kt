package com.example.core.agent

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class ScreenNode(
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val className: String,
    val bounds: String,
    val centerX: Int,
    val centerY: Int,
    val isClickable: Boolean,
    val isFocusable: Boolean,
    val index: Int = 0,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val packageName: String = ""
)

object ScreenXmlParser {

    /**
     * Parses uiautomator dump XML into a compact list of interactive or informative nodes.
     * Supports both Android XmlPullParser and fallback regex parser for local JVM testing.
     */
    fun parse(xmlContent: String): List<ScreenNode> {
        val nodes = mutableListOf<ScreenNode>()
        if (xmlContent.isBlank()) return nodes

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tagName = parser.name ?: ""
                    if (tagName.equals("node", ignoreCase = true)) {
                        val text = getAttr(parser, "text")
                        val contentDesc = getAttr(parser, "content-desc")
                        val resourceId = getAttr(parser, "resource-id")
                        val className = getAttr(parser, "class")
                        val bounds = getAttr(parser, "bounds")
                        val clickable = getAttr(parser, "clickable").equals("true", ignoreCase = true)
                        val focusable = getAttr(parser, "focusable").equals("true", ignoreCase = true)

                        val (cx, cy) = parseCenter(bounds)

                        if (text.isNotBlank() || contentDesc.isNotBlank() || resourceId.isNotBlank() || clickable || focusable) {
                            nodes.add(
                                ScreenNode(
                                    text = text.trim(),
                                    contentDescription = contentDesc.trim(),
                                    resourceId = resourceId.substringAfterLast("/"),
                                    className = className.substringAfterLast("."),
                                    bounds = bounds,
                                    centerX = cx,
                                    centerY = cy,
                                    isClickable = clickable,
                                    isFocusable = focusable
                                )
                            )
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Throwable) {
            android.util.Log.w("Aura", "XmlPullParser failed, falling back to regex: ${e.message}")
            return parseFallback(xmlContent)
        }

        if (nodes.isEmpty() && xmlContent.contains("<node")) {
            return parseFallback(xmlContent)
        }

        return nodes
    }

    private fun parseFallback(xmlContent: String): List<ScreenNode> {
        val nodes = mutableListOf<ScreenNode>()
        val nodeMatches = Regex("""<node\s+([^>]*)/?>""", RegexOption.DOT_MATCHES_ALL).findAll(xmlContent)
        for (match in nodeMatches) {
            val attrStr = match.groupValues[1]
            val attrs = mutableMapOf<String, String>()
            Regex("""([a-zA-Z0-9_\-:]+)="([^"]*)"""").findAll(attrStr).forEach {
                attrs[it.groupValues[1]] = it.groupValues[2]
            }
            val text = attrs["text"] ?: ""
            val contentDesc = attrs["content-desc"] ?: ""
            val resourceId = attrs["resource-id"] ?: ""
            val className = attrs["class"] ?: ""
            val bounds = attrs["bounds"] ?: ""
            val clickable = (attrs["clickable"] ?: "false").equals("true", ignoreCase = true)
            val focusable = (attrs["focusable"] ?: "false").equals("true", ignoreCase = true)
            val (cx, cy) = parseCenter(bounds)

            if (text.isNotBlank() || contentDesc.isNotBlank() || resourceId.isNotBlank() || clickable || focusable) {
                nodes.add(
                    ScreenNode(
                        text = text.trim(),
                        contentDescription = contentDesc.trim(),
                        resourceId = resourceId.substringAfterLast("/"),
                        className = className.substringAfterLast("."),
                        bounds = bounds,
                        centerX = cx,
                        centerY = cy,
                        isClickable = clickable,
                        isFocusable = focusable
                    )
                )
            }
        }
        return nodes
    }

    private fun getAttr(parser: XmlPullParser, name: String): String {
        for (i in 0 until parser.attributeCount) {
            val attrName = parser.getAttributeName(i) ?: ""
            if (attrName.equals(name, ignoreCase = true) || attrName.endsWith(":$name")) {
                return parser.getAttributeValue(i) ?: ""
            }
        }
        return parser.getAttributeValue(null, name)
            ?: parser.getAttributeValue("", name)
            ?: ""
    }

    /**
     * Parses "[x1,y1][x2,y2]" into center (x, y) coordinates.
     */
    fun parseCenter(bounds: String): Pair<Int, Int> {
        return try {
            val parts = bounds.replace("[", "").split("]")
            if (parts.size >= 2) {
                val p1 = parts[0].split(",")
                val p2 = parts[1].split(",")
                val x1 = p1[0].toInt()
                val y1 = p1[1].toInt()
                val x2 = p2[0].toInt()
                val y2 = p2[1].toInt()
                Pair((x1 + x2) / 2, (y1 + y2) / 2)
            } else {
                Pair(0, 0)
            }
        } catch (e: Exception) {
            android.util.Log.e("Aura", "Failed to parse bounds: $bounds", e)
            Pair(0, 0)
        }
    }

    /**
     * Formats screen nodes into a compact summary string for the LLM.
     */
    fun toCompactSummary(nodes: List<ScreenNode>): String {
        if (nodes.isEmpty()) return "Screen is empty or blank."
        val sb = StringBuilder("Current Screen Content:\n")
        nodes.forEachIndexed { index, node ->
            val label = buildString {
                if (node.text.isNotBlank()) append("\"${node.text}\" ")
                if (node.contentDescription.isNotBlank()) append("(desc: \"${node.contentDescription}\") ")
                if (node.resourceId.isNotBlank()) append("[id: ${node.resourceId}] ")
                if (node.isClickable) append("[clickable at (${node.centerX}, ${node.centerY})]")
            }.trim()

            if (label.isNotBlank()) {
                sb.append("${index + 1}. $label\n")
            }
        }
        return sb.toString().trim()
    }
}
