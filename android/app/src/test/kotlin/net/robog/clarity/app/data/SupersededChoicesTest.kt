package net.robog.clarity.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import net.robog.clarity.app.data.feed.supersededChoices
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.userItem
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Choice

class SupersededChoicesTest {
    private fun asked(seq: Long) = agentItem(seq, "Ready?").let {
        it.copy(cards = it.cards + Card.Choices("Ready?", listOf(Choice("Yes", "Yes"))))
    }

    @Test
    fun `no person reply keeps the buttons`() {
        assertEquals(emptySet(), supersededChoices(listOf(agentItem(1, "Hi"), asked(2))))
    }

    @Test
    fun `a typed reply after the card hides it`() {
        val card = asked(2)
        assertEquals(setOf(card.id), supersededChoices(listOf(agentItem(1, "Hi"), card, userItem(3, "later"))))
    }

    @Test
    fun `a person item before the card does not hide it`() {
        assertEquals(emptySet(), supersededChoices(listOf(userItem(1, "hey"), asked(2))))
    }

    @Test
    fun `only the earlier cards are hidden`() {
        val first = asked(2)
        val second = asked(5)
        assertEquals(setOf(first.id), supersededChoices(listOf(first, userItem(3, "no"), agentItem(4, "ok"), second)))
    }

    @Test
    fun `a send in flight hides every card`() {
        val card = asked(2)
        assertEquals(setOf(card.id), supersededChoices(listOf(card), sending = true))
        assertEquals(emptySet(), supersededChoices(listOf(card), sending = false))
    }
}
