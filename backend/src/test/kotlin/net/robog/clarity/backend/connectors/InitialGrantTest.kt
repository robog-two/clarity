package net.robog.clarity.backend.connectors

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.robog.clarity.shared.connector.GrantState

class InitialGrantTest : FunSpec({
    test("a newly announced noun waits for the person unless granted by default") {
        initialGrant(null, grantByDefault = false) shouldBe GrantState.PENDING
        initialGrant(null, grantByDefault = true) shouldBe GrantState.GRANTED
    }

    test("granting by default lifts pending and nothing else") {
        initialGrant(GrantState.PENDING, grantByDefault = true) shouldBe GrantState.GRANTED
        initialGrant(GrantState.PENDING, grantByDefault = false) shouldBe null
    }

    test("an explicit decision is never overridden") {
        for (byDefault in listOf(true, false)) {
            initialGrant(GrantState.GRANTED, byDefault) shouldBe null
            initialGrant(GrantState.REVOKED, byDefault) shouldBe null
        }
    }
})
