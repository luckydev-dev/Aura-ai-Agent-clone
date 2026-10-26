package com.example

import com.example.core.agent.AgentLoop
import com.example.core.memory.MemoryRepository
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.core.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ToolRegistryTest {

    @Test
    fun testToolDeclarationsMeetGeminiRules() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val rootManager = RootManager(context)
        val preferences = AuraPreferences(context)
        val memoryRepository = MemoryRepository(context)
        val agentLoop = AgentLoop(rootManager)

        val registry = ToolRegistry(context, rootManager, preferences, memoryRepository, agentLoop)
        val declarations = registry.getToolDeclarationsJson()

        assertTrue("Expected non-empty tool declarations", declarations.isNotEmpty())

        val toolNames = mutableSetOf<String>()
        val nameRegex = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*$""")
        val allowedTypes = setOf("OBJECT", "STRING", "INTEGER", "NUMBER", "BOOLEAN", "ARRAY")

        for (decl in declarations) {
            val name = decl["name"] as String
            assertTrue("Tool name '$name' must match identifier regex", name.matches(nameRegex))
            assertTrue("Duplicate tool name '$name'", toolNames.add(name))

            val params = decl["parameters"] as? Map<*, *>
            if (params != null) {
                // Assert parameters has non-empty properties
                val props = params["properties"] as? Map<*, *>
                assertNotNull("Parameters schema for '$name' must have properties", props)
                assertTrue("Parameters schema for '$name' must NOT have empty properties", props!!.isNotEmpty())

                // Check types in properties
                assertRecursiveTypes(name, params, allowedTypes)

                // Check required array entries
                val required = params["required"] as? List<*>
                if (required != null) {
                    for (req in required) {
                        assertTrue(
                            "Required entry '$req' in tool '$name' must exist in properties",
                            props.containsKey(req)
                        )
                    }
                }

                // Check unsupported keys are absent
                assertFalse("Parameters schema for '$name' contains additionalProperties", params.containsKey("additionalProperties"))
                assertFalse("Parameters schema for '$name' contains default", params.containsKey("default"))
                assertFalse("Parameters schema for '$name' contains \$schema", params.containsKey("\$schema"))
            }
        }
    }

    private fun assertRecursiveTypes(toolName: String, map: Map<*, *>, allowedTypes: Set<String>) {
        for ((k, v) in map) {
            if (k == "type" && v is String) {
                assertTrue(
                    "Tool '$toolName' type '$v' must be uppercase in $allowedTypes",
                    allowedTypes.contains(v)
                )
            } else if (v is Map<*, *>) {
                assertRecursiveTypes(toolName, v, allowedTypes)
            }
        }
    }
}
