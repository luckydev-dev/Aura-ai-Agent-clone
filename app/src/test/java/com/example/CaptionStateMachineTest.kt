package com.example

import com.example.core.live.CaptionStateMachine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CaptionStateMachineTest {

    private lateinit var stateMachine: CaptionStateMachine

    @Before
    fun setUp() {
        stateMachine = CaptionStateMachine()
    }

    @Test
    fun testMarkdownStripping() {
        val input = "**Crafting a Response** # Header `code` and *italic* __bold2__"
        val stripped = CaptionStateMachine.stripMarkdown(input)
        assertEquals("Crafting a Response Header code and italic bold2", stripped)
    }

    @Test
    fun testTurnLifecycle() {
        // User starts speaking turn 1
        stateMachine.onInputTranscription("Hello Aura")
        var state = stateMachine.state.value
        assertEquals("Hello Aura", state.userText)
        assertEquals("", state.auraText)
        assertEquals(1L, state.turnId)

        // User speaks more in the same turn
        stateMachine.onInputTranscription("kya haal hai")
        state = stateMachine.state.value
        assertEquals("Hello Aura kya haal hai", state.userText)
        assertEquals("", state.auraText)
        assertEquals(1L, state.turnId)

        // Aura responds (new Aura turn)
        stateMachine.onOutputTranscription("Haan Lucky sab badhiya")
        state = stateMachine.state.value
        assertEquals("Hello Aura kya haal hai", state.userText) // userText preserved!
        assertEquals("Haan Lucky sab badhiya", state.auraText)
        assertTrue(state.auraSpeaking)
        assertEquals(2L, state.turnId)

        // Aura turn completes -> text must remain visible
        stateMachine.onTurnComplete()
        state = stateMachine.state.value
        assertFalse(state.auraSpeaking)
        assertEquals("Hello Aura kya haal hai", state.userText)
        assertEquals("Haan Lucky sab badhiya", state.auraText)

        // User speaks again -> new turn: clears both previous userText and auraText
        stateMachine.onInputTranscription("Dusra kaam karo")
        state = stateMachine.state.value
        assertEquals("Dusra kaam karo", state.userText)
        assertEquals("", state.auraText) // cleared on new user turn
        assertEquals(3L, state.turnId)
    }

    @Test
    fun testInterruptionMarksCutoffWithoutClearing() {
        stateMachine.onInputTranscription("Bolo")
        stateMachine.onOutputTranscription("Main bata rahi hu ek lamba dialogue")
        stateMachine.onInterrupted()

        val state = stateMachine.state.value
        assertFalse(state.auraSpeaking)
        assertTrue(state.isInterrupted)
        assertTrue(state.auraText.endsWith(" ..."))
        assertEquals("Main bata rahi hu ek lamba dialogue ...", state.auraText)
        assertEquals("Bolo", state.userText)
    }
}
