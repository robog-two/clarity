package net.robog.clarity.backend.setup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.hours
import net.robog.clarity.backend.agent.AgentRuntime
import net.robog.clarity.backend.agent.FakeModel
import net.robog.clarity.backend.agent.InMemoryRoleOverrideStore
import net.robog.clarity.backend.agent.ModelGatewayFactory
import net.robog.clarity.backend.agent.ModelSettings
import net.robog.clarity.backend.agent.UsageRecorder
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.api.Problem
import net.robog.clarity.shared.session.ModelSettingsView
import net.robog.clarity.shared.session.RoleModelOverride
import net.robog.clarity.shared.session.SettingSource

private const val TOKEN = "Bearer device"
private const val PATH = "/api/v1/settings/models"

class ModelSettingsRoutesTest : FunSpec({
    fun rig(openRouterKey: String? = "sk-or"): AgentRuntime {
        val config = AgentConfig(anthropicApiKey = "sk-ant", reviewInterval = 2.hours, openRouterApiKey = openRouterKey)
        val model = FakeModel()
        return AgentRuntime(config, ModelSettings(config, InMemoryRoleOverrideStore()), ModelGatewayFactory { _, _ -> model }, UsageRecorder { _, _, _ -> })
    }

    fun ApplicationTestBuilder.setup(runtime: AgentRuntime): HttpClient {
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
            install(Authentication) {
                bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId("d1"), null) } }
            }
            ModelSettingsRoutes(ModelSettingsService(runtime)).install(this)
        }
        return createClient { install(ContentNegotiation) { json(ClarityJson) } }
    }

    suspend fun HttpClient.putRole(role: String, body: RoleModelOverride) = put("$PATH/$role") {
        header(HttpHeaders.Authorization, TOKEN)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    test("every route needs a device") {
        testApplication {
            val client = setup(rig())
            client.get(PATH).status shouldBe HttpStatusCode.Unauthorized
            client.put("$PATH/guard") { contentType(ContentType.Application.Json); setBody("{}") }.status shouldBe HttpStatusCode.Unauthorized
            client.delete("$PATH/guard").status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("GET shows each role's settings in effect, their defaults and sources, and the providers with a key") {
        testApplication {
            val client = setup(rig())
            val view = client.get(PATH) { header(HttpHeaders.Authorization, TOKEN) }.body<ModelSettingsView>()
            view.roles.map { it.role } shouldContainExactly listOf("lead", "helper", "guard", "triage")
            view.providersWithKeys shouldContainExactly listOf("anthropic", "openrouter")
            val lead = view.roles.first()
            lead.effective shouldBe lead.default
            lead.effective.model shouldBe "claude-opus-5-5"
            lead.sources.values.toSet() shouldBe setOf(SettingSource.DEFAULT)
            lead.updatedAt shouldBe null
        }
    }

    test("PUT sets a role's overrides, effective at once; DELETE goes back to the defaults") {
        val runtime = rig()
        testApplication {
            val client = setup(runtime)
            val saved = client.putRole("guard", RoleModelOverride(provider = "openrouter", model = "anthropic/claude-haiku-5.5", effort = "medium"))
            saved.status shouldBe HttpStatusCode.OK
            val guard = saved.body<ModelSettingsView>().roles.single { it.role == "guard" }
            guard.effective.provider shouldBe "openrouter"
            guard.effective.model shouldBe "anthropic/claude-haiku-5.5"
            guard.default.model shouldBe "claude-haiku-5-5"
            guard.sources["model"] shouldBe SettingSource.OVERRIDE
            guard.sources["maxTokens"] shouldBe SettingSource.DEFAULT
            (guard.updatedAt != null) shouldBe true
            runtime.settings[AgentRole.GUARD].model shouldBe "anthropic/claude-haiku-5.5"

            val cleared = client.delete("$PATH/guard") { header(HttpHeaders.Authorization, TOKEN) }
            cleared.status shouldBe HttpStatusCode.OK
            cleared.body<ModelSettingsView>().roles.single { it.role == "guard" }.effective.model shouldBe "claude-haiku-5-5"
            runtime.settings[AgentRole.GUARD].provider.name shouldBe "ANTHROPIC"
        }
    }

    test("refused changes are 422 problems naming each field; an unknown role is 404") {
        testApplication {
            val client = setup(rig(openRouterKey = null))
            val lead = client.putRole("lead", RoleModelOverride(provider = "openrouter", model = "anthropic/claude-opus-5.5"))
            lead.status shouldBe HttpStatusCode.UnprocessableEntity
            val problem = ClarityJson.decodeFromString(Problem.serializer(), lead.bodyAsText())
            problem.errors!!.single().field shouldBe "provider"
            problem.errors!!.single().message shouldContain "lead"

            val noKey = ClarityJson.decodeFromString(
                Problem.serializer(),
                client.putRole("guard", RoleModelOverride(provider = "openrouter", model = "a/b")).bodyAsText(),
            )
            noKey.errors!!.single().message shouldContain "OPENROUTER_API_KEY"

            val many = client.putRole("helper", RoleModelOverride(provider = "bedrock", effort = "extreme"))
            many.status shouldBe HttpStatusCode.UnprocessableEntity
            ClarityJson.decodeFromString(Problem.serializer(), many.bodyAsText()).errors!!.map { it.field } shouldContainExactly listOf("provider")

            val values = client.putRole("helper", RoleModelOverride(effort = "extreme", cacheTtl = "2h", compactAtTokens = 10))
            ClarityJson.decodeFromString(Problem.serializer(), values.bodyAsText()).errors!!.map { it.field } shouldContainExactly
                listOf("effort", "cacheTtl", "compactAtTokens")

            client.putRole("chef", RoleModelOverride(model = "x")).status shouldBe HttpStatusCode.NotFound
            client.delete("$PATH/chef") { header(HttpHeaders.Authorization, TOKEN) }.status shouldBe HttpStatusCode.NotFound

            // Changing the lead between Claude models is allowed.
            client.putRole("lead", RoleModelOverride(model = "claude-sonnet-5-5")).status shouldBe HttpStatusCode.OK
        }
    }
})
