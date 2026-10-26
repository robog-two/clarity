package net.robog.clarity.backend.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EmailFolderTest : FunSpec({
    test("the folder is read from account-prefixed and legacy mail source ids, slashes included") {
        emailFolder("bv1sk942tz3m/INBOX/1463054468/19542") shouldBe "INBOX"
        emailFolder("yuppb5511g9s/[Gmail]/Sent Mail/12/34") shouldBe "[Gmail]/Sent Mail"
        emailFolder("INBOX/7/8") shouldBe "INBOX"
        emailFolder("uid-1") shouldBe null
        emailFolder("a/INBOX/x/8") shouldBe null
    }

    test("the pattern matches the whole folder or its last part, ignoring regex characters") {
        fun matches(folder: String, id: String) = Regex(emailFolderPattern(folder), RegexOption.IGNORE_CASE).matches(id)
        matches("Sent Mail", "acct/[Gmail]/Sent Mail/1/2") shouldBe true
        matches("[Gmail]/Sent Mail", "acct/[Gmail]/Sent Mail/1/2") shouldBe true
        matches("inbox", "acct/INBOX/1/2") shouldBe true
        matches("INBOX", "acct/INBOX.Archive/1/2") shouldBe false
        matches("Mail", "acct/[Gmail]/Sent Mail/1/2") shouldBe false
        matches(".*", "acct/INBOX/1/2") shouldBe false
    }
})
