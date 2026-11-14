package net.robog.clarity.backend.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

private class SandboxStub(override val name: String) : Tool {
    override val description = "stub"
    override val inputSchema: JsonObject = buildJsonObject { put("type", "object") }
    override val effect = ToolEffect.READ
    override suspend fun invoke(input: JsonObject): ToolResult = ToolResult.Ok("")
}

/** Who gets the attachment tools: helpers that may read messages and have the sandbox; never the lead; and using them taints the run. */
@EnabledIf(ContainerRuntimeAvailable::class)
class AttachmentToolboxDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    val sandbox = listOf(FakeFetchPage(), SandboxStub("sandbox__write_file"), SandboxStub("sandbox__read_file_base64"))

    test("a default helper with the sandbox has save_attachment and view_image; the lead has neither") {
        val rig = LeadRig(database, sandboxTools = sandbox)
        val helper = rig.toolbox.helperTools(RunScope("h", isLead = false), null).tools.tools.map { it.name }
        helper shouldContain SAVE_ATTACHMENT
        helper shouldContain VIEW_IMAGE
        val lead = rig.toolbox.leadTools(RunScope("lead", isLead = true)).tools.map { it.name }
        lead shouldNotContain SAVE_ATTACHMENT
        lead shouldNotContain VIEW_IMAGE
    }

    test("without the sandbox there are no attachment tools") {
        val rig = LeadRig(database)
        val helper = rig.toolbox.helperTools(RunScope("h", isLead = false), null).tools.tools.map { it.name }
        helper shouldNotContain SAVE_ATTACHMENT
        helper shouldNotContain VIEW_IMAGE
    }

    test("save_attachment needs a tool that reads messages in the same run") {
        val rig = LeadRig(database, sandboxTools = sandbox)
        val without = rig.toolbox.helperTools(RunScope("h", isLead = false), listOf(SAVE_ATTACHMENT, VIEW_IMAGE))
        without.unknown shouldBe listOf(SAVE_ATTACHMENT)
        without.tools.tools.map { it.name } shouldNotContain SAVE_ATTACHMENT
        without.tools.tools.map { it.name } shouldContain VIEW_IMAGE

        val with = rig.toolbox.helperTools(RunScope("h", isLead = false), listOf(SAVE_ATTACHMENT, "search_messages"))
        with.unknown shouldBe emptyList()
        with.tools.tools.map { it.name } shouldContain SAVE_ATTACHMENT
    }

    test("a model that does not take images does not get view_image") {
        val rig = LeadRig(database, sandboxTools = sandbox, capabilities = ModelCapabilities { false })
        val helper = rig.toolbox.helperTools(RunScope("h", isLead = false), null).tools.tools.map { it.name }
        helper shouldContain SAVE_ATTACHMENT
        helper shouldNotContain VIEW_IMAGE
    }

    test("using either tool marks the run as having read outside content") {
        val rig = LeadRig(database, sandboxTools = sandbox)
        val scope = RunScope("h", isLead = false)
        scope.untrusted shouldBe false
        val tool = rig.toolbox.helperTools(scope, null).tools.tools.first { it.name == SAVE_ATTACHMENT }
        tool.invoke(buildJsonObject { put("messageId", "x"); put("part", "2") })
        scope.untrusted shouldBe true
    }
})
