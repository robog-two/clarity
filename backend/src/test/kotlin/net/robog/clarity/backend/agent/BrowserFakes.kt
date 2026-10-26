package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.vault.CreateOutcome
import net.robog.clarity.backend.vault.GrantStore
import net.robog.clarity.backend.vault.Hold
import net.robog.clarity.backend.vault.VaultGovernance
import net.robog.clarity.backend.vault.VaultStore
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Provenance
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.CredentialRequest
import net.robog.clarity.shared.vault.CredentialRequestId
import net.robog.clarity.shared.vault.DeviceVaultKey
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType
import net.robog.clarity.shared.vault.VaultEnvelope

class StepClock(var now: Instant = Instant.parse("2026-10-25T12:00:00Z")) : Clock {
    override fun now(): Instant = now
    fun advance(by: Duration) { now += by }
}

/** An in-memory vault: metadata only. */
class MemoryVault(val credentials: List<CredentialMeta>) : VaultStore {
    val touched = CopyOnWriteArrayList<String>()
    override suspend fun list() = credentials
    override suspend fun get(id: CredentialId) = credentials.firstOrNull { it.id == id }
    override suspend fun byName(name: String) = credentials.firstOrNull { it.name == name }
    override suspend fun touch(id: CredentialId) { touched += id.value }
    override suspend fun upsertDeviceKey(key: DeviceVaultKey, approvalKey: String?) = key
    override suspend fun approvalKey(device: DeviceId): String? = null
    override suspend fun markSignatureUsed(signatureHash: String, heldId: String, device: DeviceId) = true
    override suspend fun deviceKeys() = emptyList<DeviceVaultKey>()
    override suspend fun deviceKey(device: DeviceId): DeviceVaultKey? = null
    override suspend fun createRequest(name: String, kind: CredentialKind, fields: List<FieldSpec>, site: String, purpose: String): CredentialRequest = error("unused")
    override suspend fun request(id: CredentialRequestId): CredentialRequest? = null
    override suspend fun cancelRequest(id: CredentialRequestId) = false
    override suspend fun createCredential(request: CredentialRequestId, hints: Map<String, String>, envelopes: List<VaultEnvelope>): CreateOutcome = error("unused")
    override suspend fun delete(id: CredentialId) = false
    override suspend fun envelopeFor(id: CredentialId, device: DeviceId): VaultEnvelope? = null
}

/** Records what governance was asked; answers from scripted queues. */
class FakeGovernance : VaultGovernance {
    val credentialAsks = CopyOnWriteArrayList<Pair<String, String>>()
    val steps = CopyOnWriteArrayList<ProposedAction.ExternalTool>()
    var stepAnswer: Hold = Hold.Wait("Waiting for the person.", HeldActionId("h1"))

    override suspend fun useCredential(credential: CredentialMeta, domain: String, pageUrl: String, provenance: Provenance, reason: String): Hold {
        credentialAsks += credential.name to domain
        return Hold.Wait("Asked the person to approve ${credential.name} on $domain.", HeldActionId("h-${credential.name}"))
    }

    override suspend fun askCode(site: String, purpose: String, provenance: Provenance): Hold.Wait = Hold.Wait("asked", HeldActionId("h-code"))

    override suspend fun browserStep(action: ProposedAction.ExternalTool, provenance: Provenance, reason: String, context: List<ContextItem>): Hold {
        steps += action
        return stepAnswer
    }

    /** What the person answers when a helper waits (null: never); [onDecision] runs just before, like the phone's release. */
    var decision: HeldAction.Status? = null
    var onDecision: () -> Unit = {}
    val awaited = CopyOnWriteArrayList<HeldActionId>()

    override suspend fun awaitDecision(heldId: HeldActionId, timeout: kotlin.time.Duration): HeldAction.Status? {
        awaited += heldId
        decision?.let { onDecision() }
        return decision
    }
}

/** A fake browser: remembers what each tool received and answers like the real one would (echoing typed values back). */
class FakeBrowser {
    var url = "https://bank.example.com/login"
    val received = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    var snapshot = """
        uid=1_0 document "Sign in"
          uid=1_1 textbox "Username"
          uid=1_2 textbox "Password"
          uid=1_3 button "Review details"
          uid=1_4 button "Send money"
          uid=1_5 textbox "Card number"
    """.trimIndent()
    var echo = true
    var pageText = "page shows the password hunter2! and card 4111 1111 1111 1111 and q=hunter2%21"

    fun tool(name: String, body: (JsonObject) -> ToolResult): Tool = object : Tool {
        override val name = "browser__$name"
        override val description = "fake $name"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override val effect = ToolEffect.EXTERNAL
        override suspend fun invoke(input: JsonObject): ToolResult {
            received += name to input
            return body(input)
        }
    }

    val tools: List<Tool> = listOf(
        tool("list_pages") { ToolResult.Ok("1 pages (selected: 0)\n>[0] Bank ($url)") },
        tool("navigate_page") { ToolResult.Ok("[0] → ${it["url"]}").also { _ -> url = (it["url"] as kotlinx.serialization.json.JsonPrimitive).content } },
        tool("take_snapshot") { ToolResult.Ok(snapshot) },
        tool("fill_by_uid") {
            if (it.str("uid") == "boom") ToolResult.Error("could not fill hunter2!")
            else ToolResult.Ok("filled ${it.str("uid")} with ${it.str("value")}")
        },
        tool("fill_form_by_uid") { ToolResult.Ok("filled form: $it") },
        tool("type_text") { ToolResult.Ok("typed ${(it["text"] as kotlinx.serialization.json.JsonPrimitive).content}") },
        tool("click_by_uid") { ToolResult.Ok("clicked ${it.str("uid")}") },
        tool("get_page_text") { ToolResult.Ok(pageText) },
        tool("screenshot_page") { ToolResult.Ok("[ImageContent omitted]") },
        tool("evaluate_script") { ToolResult.Ok("pwned") },
        tool("send_bidi_command") { ToolResult.Ok("pwned") },
    ) + object : Tool {
        override val name = "sandbox__run"
        override val description = "not the browser"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override val effect = ToolEffect.EXTERNAL
        override suspend fun invoke(input: JsonObject) = ToolResult.Ok("ran")
    }

    fun calls(name: String) = received.filter { it.first == name }.map { it.second }
}


fun JsonObject.str(key: String) = (this[key] as kotlinx.serialization.json.JsonPrimitive).content
