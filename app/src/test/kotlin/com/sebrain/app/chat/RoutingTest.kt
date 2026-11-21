package com.sebrain.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingTest {
    @Test
    fun `the questions of the 2026-11-20 screenshot are not personal`() {
        // Answered "nothing in your notes" before routing existed.
        assertNull(Routing.byRules("pero queiro que me respondas quien fue El primer fotografo"))
        assertNull(Routing.byRules("que peudes decirme de la nasa"))
        assertNull(Routing.byRules("What can you tell me about NASA?"))
        assertEquals(Route.GENERAL, Routing.route("que peudes decirme de la nasa", null, null))
    }

    @Test
    fun `possessives and what the user did are personal`() {
        listOf(
            "¿cuándo es mi cita con el dentista?", "What's my locker combination?", "¿qué apunté ayer?",
            "did I save the booking code?", "what did we decide last week?", "¿qué dicen mis notas sobre Heron?",
        ).forEach { assertEquals(it, Route.PERSONAL, Routing.byRules(it)) }
    }

    @Test
    fun `greetings and thanks are small talk, a real question is not`() {
        listOf("hola", "hola, ¿cómo estás?", "Thanks!", "gracias", "good morning", "¿qué tal?").forEach {
            assertEquals(it, Route.CHAT, Routing.byRules(it))
        }
        assertNull(Routing.byRules("hola, ¿quién inventó el teléfono?"))
    }

    @Test
    fun `asking for a note is an action`() {
        assertEquals(Route.ACTION, Routing.byRules("haz una nota sobre la fotosíntesis"))
        assertEquals(Route.ACTION, Routing.byRules("make a note about my trip"))
    }

    @Test
    fun `the semantic router picks the closest examples when they are clearly ahead`() {
        val router = SemanticRouter(
            mapOf(
                Route.GENERAL to List(3) { floatArrayOf(1f, 0f, 0f) },
                Route.PERSONAL to List(3) { floatArrayOf(0f, 1f, 0f) },
                Route.CHAT to List(3) { floatArrayOf(0f, 0f, 1f) },
            ),
        )

        assertEquals(Route.PERSONAL, router.route(floatArrayOf(0.1f, 0.9f, 0f)))
        // Halfway between two routes: too close to call.
        assertNull(router.route(floatArrayOf(0.7f, 0.7f, 0f)))
        // Close to nothing.
        assertNull(router.route(floatArrayOf(0.2f, 0.2f, -0.9f)))
    }

    @Test
    fun `without rules or a router the message is general`() {
        assertEquals(Route.GENERAL, Routing.route("explícame la relatividad", floatArrayOf(1f), null))
    }
}
