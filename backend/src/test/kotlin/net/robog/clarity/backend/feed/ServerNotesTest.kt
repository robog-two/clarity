package net.robog.clarity.backend.feed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.robog.clarity.backend.backup.NightlyBackup
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.NOTIFY_IN_APP
import net.robog.clarity.shared.feed.NOTIFY_REPLY

class ServerNotesTest : FunSpec({
    test("a note about a turn that answered the person is a reply, otherwise an ordinary post") {
        turnNote("The model declined.", answeringPerson = true).notify shouldBe NOTIFY_REPLY
        turnNote("The model declined.", answeringPerson = false).notify shouldBe NOTIFY_IN_APP
        turnNote("x", answeringPerson = true).pushCounted shouldBe false
    }

    test("a note the person must see goes out at once and is not counted in the daily budget") {
        val draft = serverNote("Spend limit reached.", NOTIFY_PUSH_NOW)
        draft.notify shouldBe "push_now"
        draft.pushCounted shouldBe false
        draft.author shouldBe FeedItem.Author.AGENT
        draft.cards shouldBe listOf(Card.Note("Spend limit reached."))
        serverNote("plain").notify shouldBe NOTIFY_IN_APP
    }

    test("the backup notice keeps the app voice") {
        NightlyBackup.BACKUP_FAILED_NOTE.count { it == ',' } shouldBe 1
        NightlyBackup.BACKUP_FAILED_NOTE.contains(':') shouldBe false
    }
})
