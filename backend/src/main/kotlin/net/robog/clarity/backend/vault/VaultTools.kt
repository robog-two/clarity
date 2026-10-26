package net.robog.clarity.backend.vault

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.agent.RunScope
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.VaultNames

/** Names of the always-loaded vault tools. */
val VAULT_TOOL_NAMES = setOf("request_credential", "list_credentials", "ask_code")

/** Pure: a credential as the agent may see it (metadata and the placeholders it can write; never a value). */
fun describeCredential(meta: CredentialMeta): String = buildString {
    append(meta.name).append(" [").append(meta.kind.name.lowercase()).append("] for ").append(meta.site)
    append(": ").append(meta.purpose)
    if (meta.hints.isNotEmpty()) append(" (").append(meta.hints.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }).append(')')
    append(". Fields: ").append(meta.fields.joinToString { VaultNames.placeholder(meta.name, it.name) })
}

/**
 * The agent's vault tools. None of them can return a secret: `request_credential` asks the person to
 * type one on their phone, `list_credentials` shows metadata, `ask_code` asks for a two-factor code
 * that only ever reaches the browser tool as `[secret:code_<id>]`.
 */
@Inject
@SingleIn(AppScope::class)
class VaultTools(
    private val vault: VaultService,
    private val holds: VaultGovernance,
) {
    @Serializable
    class RequestInput(
        @Description("Which kind of credential: login, payment_card, identity or note.") val kind: CredentialKind,
        @Description("The fields to ask for. name: lowercase snake_case (username, password, card_number, card_expiry, card_cvc...); label: what the person sees; type decides the validation.")
        val fields: List<FieldSpec>,
        @Description("The website this is for, e.g. bank.example.com.") val site: String,
        @Description("One short sentence for the person: why you need it.") val purpose: String,
        @Description("Handle for placeholders, lowercase snake_case (e.g. bank_login). Default: derived from the site and kind.")
        val name: String? = null,
        @Description("true to have the person enter a saved credential again (it was wrong or changed): `name` is the saved one, and the new entry replaces it once they save it. The placeholders stay the same.")
        val replace: Boolean = false,
    )

    @Serializable
    class NoInput

    @Serializable
    class AskCodeInput(
        @Description("The website that wants the code.") val site: String,
        @Description("One short sentence for the person: what the code is for.") val purpose: String,
    )

    fun all(scope: RunScope): List<Tool> = listOf(requestCredential(), listCredentials(), askCode(scope))

    private fun requestCredential(): Tool = tool<RequestInput>(
        name = "request_credential",
        description = "Ask the person to enter a login, card or other credential on their phone. You never see the values: you learn only that it was saved, then use " +
            "[secret:<name>_<field>] placeholders in browser tool inputs. This makes the form but does not show it: post a credential_request card {requestId} " +
            "with send_to_user, once. To have them enter a saved one again, pass its name with replace=true (you cannot delete saved credentials; " +
            "the person can, in Settings > Vault).",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        vault.requestCredential(input.name, input.kind, input.fields, input.site, input.purpose, input.replace).fold(
            { ToolResult.Error(it.detail ?: it.title) },
            { request ->
                ToolResult.Ok(
                    "Made the form (request ${request.id}). It is not in the chat yet: show it with send_to_user as one credential_request card " +
                        "{\"type\": \"credential_request\", \"requestId\": \"${request.id}\"}, with a short line saying why. " +
                        (if (input.replace) "Once they save it, it replaces the saved ${request.name}. " else "") +
                        "Once saved it appears in list_credentials as ${request.name}; write " +
                        request.fields.joinToString(" ") { VaultNames.placeholder(request.name, it.name) } + " in browser tool inputs. Do not ask again; carry on.",
                )
            },
        )
    }

    private fun listCredentials(): Tool = tool<NoInput>(
        name = "list_credentials",
        description = "List the saved credentials: name, kind, site, purpose, non-secret hints and the placeholders you can write. Never values.",
        json = ClarityJson,
    ) {
        val all = vault.list()
        ToolResult.Ok(if (all.isEmpty()) "(none saved)" else all.joinToString("\n") { describeCredential(it) })
    }

    private fun askCode(scope: RunScope): Tool = tool<AskCodeInput>(
        name = "ask_code",
        description = "Ask the person for a two-factor or one-time code a site sent them. Codes are not stored. Returns a placeholder " +
            "[secret:code_<id>] to type into the site's code field once they have entered it (it is valid for 5 minutes on that site).",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        if (input.site.isBlank() || Sites.host(input.site) == null) ToolResult.Error("`site` must be a website address or host")
        else if (input.purpose.isBlank()) ToolResult.Error("`purpose` must say what the code is for")
        else {
            val wait = holds.askCode(input.site, input.purpose.trim(), scope.provenance())
            val id = wait.heldId
            if (id == null) ToolResult.Error(wait.text)
            else ToolResult.Ok(
                "Asked the person for the code. Once they type it, use [secret:code_${id.value}] in the browser (on ${Sites.registrableDomain(input.site)} only). " +
                    "If the browser says it has not been entered yet, wait and try again. Do not ask twice.",
            )
        }
    }
}
