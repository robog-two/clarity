package net.robog.clarity.app.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.robog.clarity.app.connector.ConfigForms
import net.robog.clarity.app.connector.FormValues
import net.robog.clarity.app.connector.ItemValues
import net.robog.clarity.app.connector.PhoneConnectorKind
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ServerProbe
import net.robog.clarity.app.ui.onboarding.ConnectorItem
import net.robog.clarity.app.ui.onboarding.ConnectorRow
import net.robog.clarity.app.ui.onboarding.ConnectorSetupContent
import net.robog.clarity.app.ui.onboarding.ConnectorSetupState
import net.robog.clarity.app.ui.onboarding.ConnectorsContent
import net.robog.clarity.app.ui.onboarding.ConnectorsState
import net.robog.clarity.app.ui.onboarding.ConstitutionContent
import net.robog.clarity.app.ui.onboarding.ConstitutionEntry
import net.robog.clarity.app.ui.onboarding.ConstitutionSectionView
import net.robog.clarity.app.ui.onboarding.ConstitutionUi
import net.robog.clarity.app.ui.onboarding.DoneContent
import net.robog.clarity.app.ui.onboarding.DoneState
import net.robog.clarity.app.ui.onboarding.InterviewPhase
import net.robog.clarity.app.ui.onboarding.ItemEdit
import net.robog.clarity.app.ui.onboarding.ModelContent
import net.robog.clarity.app.ui.onboarding.ModelResult
import net.robog.clarity.app.ui.onboarding.ModelState
import net.robog.clarity.app.ui.onboarding.NOT_RUNNING
import net.robog.clarity.app.ui.onboarding.ScheduleContent
import net.robog.clarity.app.ui.onboarding.ScheduleState
import net.robog.clarity.app.ui.onboarding.ServerContent
import net.robog.clarity.app.ui.onboarding.ServerError
import net.robog.clarity.app.ui.onboarding.ServerState
import net.robog.clarity.app.ui.onboarding.SetupActions
import net.robog.clarity.app.ui.onboarding.SetupProblem
import net.robog.clarity.app.ui.onboarding.TestLine
import net.robog.clarity.app.ui.onboarding.TestState
import net.robog.clarity.app.ui.onboarding.ValuesContent
import net.robog.clarity.app.ui.onboarding.ValuesIntroScreen
import net.robog.clarity.app.ui.onboarding.ValuesState
import net.robog.clarity.app.ui.onboarding.WelcomeScreen
import net.robog.clarity.app.ui.settings.SettingsActions
import net.robog.clarity.app.ui.settings.SettingsContent
import net.robog.clarity.app.ui.settings.SettingsPage
import net.robog.clarity.app.ui.settings.SettingsUiState
import net.robog.clarity.app.ui.settings.SourceRow
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.vault.CredentialForm
import net.robog.clarity.app.vault.CredentialFormContent
import net.robog.clarity.app.vault.CredentialRow
import net.robog.clarity.app.vault.FormCardState
import net.robog.clarity.app.vault.VaultKeyStatus
import net.robog.clarity.app.vault.VaultSectionContent
import net.robog.clarity.app.vault.VaultUiState
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.Section
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialRequest
import net.robog.clarity.shared.vault.CredentialRequestId
import net.robog.clarity.shared.vault.CredentialRequestStatus
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType

@Preview(name = "light", widthDp = 412, heightDp = 915)
@Preview(name = "dark", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class Screen

@Preview(name = "light", widthDp = 412)
@Preview(name = "dark", widthDp = 412, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class Part

@Composable
private fun Frame(content: @Composable () -> Unit) = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) { content() }
}

/** Shows the part of a tall scrolling screen that starts [from] dp down. */
@Composable
private fun Slice(from: Dp, content: @Composable () -> Unit) = Frame {
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Box(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset(y = -from).requiredHeight(2600.dp)) { content() }
    }
}

// --- onboarding ---

@PreviewTest @Screen @Composable
fun SetupWelcome() = Frame { WelcomeScreen {} }

@PreviewTest @Screen @Composable
fun SetupServerError() = Frame {
    ServerContent(
        ServerState(url = "https://clarity.home.example.org", code = "", error = ServerError.Unreachable),
        {}, {}, {}, {}, {},
    )
}

@PreviewTest @Screen @Composable
fun SetupServerConnected() = Frame {
    ServerContent(ServerState(url = "https://clarity.home.example.org", probe = ServerProbe(18, true), enrolled = true), {}, {}, {}, {}, {})
}

@PreviewTest @Screen @Composable
fun SetupModelChecking() = Frame {
    ModelContent(ModelState(key = "sk-ant-api03-EXAMPLEEXAMPLEEXAMPLE", checking = true), {}, {}, {}, {})
}

@PreviewTest @Screen @Composable
fun SetupModelRejected() = Frame {
    ModelContent(
        ModelState(key = "sk-ant-api03-EXAMPLEEXAMPLEEXAMPLE", result = ModelResult.Rejected("authentication_error: invalid x-api-key")),
        {}, {}, {}, {},
    )
}

private fun server(id: String, name: String?, kind: String = "imap") = ConnectorRow.Server(
    ConnectorSummary(
        ConnectorId(id),
        name?.let {
            net.robog.clarity.shared.connector.ConnectorManifest(kind = kind, displayName = it, version = "1", produces = emptySet())
        },
        emptyMap(),
        null,
    ),
)

@PreviewTest @Screen @Composable
fun SetupConnectorsList() = Frame {
    ConnectorsContent(
        ConnectorsState(
            loading = false,
            items = listOf(
                ConnectorItem(server("mail", "Mail"), saved = true, test = TestState.Ok("4,218 messages")),
                ConnectorItem(server("dav", "Calendar and Contacts", "caldav"), saved = true, test = TestState.Failed("Personal: password rejected by caldav.icloud.com (401)")),
                ConnectorItem(server("files", "Files", "webdav"), saved = false, test = null),
                ConnectorItem(server("tasks", null), saved = false, test = null),
                ConnectorItem(ConnectorRow.Phone(PhoneConnectorKind.Messages), saved = true, test = TestState.Ok("86 threads")),
            ),
        ),
        {}, {}, {}, {},
    )
}

@PreviewTest @Screen @Composable
fun SetupConnectorsEmptyError() = Frame {
    ConnectorsContent(ConnectorsState(loading = false, error = ApiError.Unreachable("timeout")), {}, {}, {}, {})
}

private val SCHEMA = Json.parseToJsonElement(
    """
    {"type":"object","required":["accounts"],"properties":{"accounts":{"type":"array","title":"Accounts","minItems":1,
      "x-item-title":"label","x-item-subtitle":"username",
      "x-presets":[{"title":"iCloud","description":"Use an app-specific password from account.apple.com","values":{"imapHost":"imap.mail.me.com","imapPort":993,"imapSecurity":"ssl"}},
                   {"title":"Fastmail","values":{"imapHost":"imap.fastmail.com","imapPort":993,"imapSecurity":"ssl"}}],
      "items":{"type":"object","required":["label","imapHost","username","password"],"properties":{
        "id":{"type":"string","readOnly":true},
        "label":{"type":"string","title":"Name","examples":["Work"]},
        "imapHost":{"type":"string","title":"IMAP host"},
        "imapPort":{"type":"integer","title":"IMAP port","default":993},
        "imapSecurity":{"type":"string","enum":["ssl","starttls","none"],"x-enum-titles":["SSL","STARTTLS","None"],"default":"ssl"},
        "username":{"type":"string"},
        "password":{"type":"string","format":"password"},
        "folders":{"type":"array","items":{"type":"string"},"default":["INBOX"]}}}}}}
    """,
).jsonObject

private fun mailState(editing: Boolean = false, choosing: Boolean = false, newItem: Boolean = false): ConnectorSetupState {
    val form = ConfigForms.parse(SCHEMA)
    val itemForm = form.fields.first().collection!!.itemForm
    fun item(id: String, label: String, user: String) = ItemValues(
        id,
        itemForm.initialValues().withText("label", label).withText("username", user).withText("imapHost", "imap.mail.me.com"),
    )
    val values = FormValues().withItems(
        "accounts",
        listOf(item("k3j9x2p1", "Work", "alex.morgan@work-example.com"), item("m4n8q7r2", "Personal", "alex@icloud.example")),
    )
    val editValues = if (newItem) itemForm.initialValues() else values.items("accounts").first().values
    return ConnectorSetupState(
        key = "mail", title = "Mail", proto = "IMAP · on your server", blurb = "I read your inbox and save drafts.",
        form = form, values = values, lastTest = TestLine("Work: 4,218 messages · Personal: password rejected", ok = false),
        editor = if (editing) ItemEdit("accounts", if (newItem) "new1" else "k3j9x2p1", newItem, editValues, hint = if (newItem) "Use an app-specific password from account.apple.com" else null, showErrors = true) else null,
        choosing = if (choosing) "accounts" else null,
    )
}

@PreviewTest @Screen @Composable
fun SetupConnectorMailFailed() = Frame {
    ConnectorSetupContent(mailState().copy(failedReason = "Personal: password rejected", problem = SetupProblem.NoSealingKey), SetupActions())
}

@PreviewTest @Screen @Composable
fun SetupConnectorAccountEdit() = Frame { ConnectorSetupContent(mailState(editing = true), SetupActions()) }

@PreviewTest @Screen @Composable
fun SetupConnectorAccountNew() = Frame { ConnectorSetupContent(mailState(editing = true, newItem = true), SetupActions()) }

@PreviewTest @Screen @Composable
fun SetupConnectorPresets() = Frame { ConnectorSetupContent(mailState(choosing = true), SetupActions()) }

@PreviewTest @Screen @Composable
fun SetupConnectorNotRunning() = Frame {
    ConnectorSetupContent(
        ConnectorSetupState(
            key = "dav", title = "Dav", proto = NOT_RUNNING, notRunning = true,
            blurb = "Dav hasn’t started on your server yet, so there’s nothing to set up. Once it’s running, tap Check again.",
            adminHint = "Whoever runs your server can start it: it’s the “dav” profile in the server’s compose setup.",
        ),
        SetupActions(), step = null, backLabel = "← settings",
    )
}

@PreviewTest @Screen @Composable
fun SetupConnectorPhone() = Frame {
    ConnectorSetupContent(
        ConnectorSetupState(key = "phone-messages", title = "Messages", proto = "on this phone", blurb = PhoneConnectorKind.Messages.blurb, phone = PhoneConnectorKind.Messages),
        SetupActions(),
    )
}

@PreviewTest @Screen @Composable
fun SetupConnectorMissing() = Frame {
    ConnectorSetupContent(ConnectorSetupState(key = "files", title = "Files", missing = true), SetupActions(), step = null, backLabel = "← settings")
}

@PreviewTest @Screen @Composable
fun SetupValuesIntro() = Frame { ValuesIntroScreen({}, {}) }

private val hardLine = Line(
    LineId("l1"), Section.HARD_LINES, "Never speak for me to a group without my review, even when it seems routine or time-sensitive.",
    because = "being misrepresented costs more than any time saved.", hard = true,
)

@PreviewTest @Screen @Composable
fun SetupValuesInterview() = Frame {
    ValuesContent(
        ValuesState(
            phase = InterviewPhase.Active, index = 1, count = 7,
            question = "Now a time help made things worse. What crossed the line?",
            said = "A scheduling tool accepted a meeting in my focus block, then emailed my whole team about it before I’d even seen it.",
            captured = hardLine, mic = MicChipState.Listening,
        ),
        {}, {}, {}, {}, {}, {}, {}, {},
    )
}

@PreviewTest @Screen @Composable
fun SetupValuesStarting() = Frame {
    ValuesContent(ValuesState(phase = InterviewPhase.Starting, mic = MicChipState.Off), {}, {}, {}, {}, {}, {}, {}, {})
}

@PreviewTest @Screen @Composable
fun SetupConstitution() = Frame {
    ConstitutionContent(
        ConstitutionUi.Loaded(
            3,
            listOf(
                ConstitutionSectionView("what I value", listOf(ConstitutionEntry("Keep 9 to 12 for deep work.", "that’s when the work gets the best of me."), ConstitutionEntry("Family dinners come first on weeknights."))),
                ConstitutionSectionView("hard lines · never crossed", listOf(ConstitutionEntry(hardLine.text, hardLine.because))),
                ConstitutionSectionView("how much I do alone", listOf(ConstitutionEntry("Archive newsletters without asking.", "they never need a reply."))),
            ),
        ),
        {}, {}, {},
    )
}

@PreviewTest @Screen @Composable
fun SetupSchedule() = Frame {
    ScheduleContent(ScheduleState(loading = false, settings = Settings(), error = ApiError.Unreachable(null)), {}, {}, {}, {}, {})
}

@PreviewTest @Screen @Composable
fun SetupDone() = Frame {
    DoneContent(DoneState(Settings.MorningCall(), connectors = 6, constitutionVersion = 1, nextDay = "tomorrow", askBattery = true), {})
}

// --- settings ---

private val vaultRows = listOf(
    CredentialRow(CredentialId("c1"), "chase_checking", "login", "secure.chase.com", "alex.morgan@work-example.com"),
    CredentialRow(CredentialId("c2"), "visa_personal", "card", "", "Visa ·· 4242 expires 03"),
    CredentialRow(CredentialId("c3"), "dmv_identity", "identity", "dmv.ca.gov", ""),
)

private val settingsState = SettingsUiState(
    instanceUrl = "https://clarity.home.example.org", deviceId = "dev_8f3k2m9q", loading = false,
    settings = Settings(), constitution = "v3 · 14 lines · amended yesterday",
    serverSources = listOf(
        SourceRow("mail", "imap · 2 accounts", "4 min ago", "mail"),
        SourceRow("calendar and contacts", "caldav · password rejected", "failed", "dav"),
        SourceRow("files", "webdav", "never", "files"),
    ),
    smsGranted = true, smsLastSync = "just now", notificationsOn = false, needsExactAlarms = true, batteryUnrestricted = false,
)

@Composable
private fun SettingsAt(page: SettingsPage) = SettingsContent(
    settingsState, SettingsActions(),
    vault = { VaultSectionContent(VaultUiState(VaultKeyStatus.READY, vaultRows, loading = false), {}, {}, showTitle = false) },
    startPage = page,
)

@PreviewTest @Screen @Composable
fun SetupSettingsTop() = Slice(0.dp) { SettingsAt(SettingsPage.Home) }

@PreviewTest @Screen @Composable
fun SetupSettingsMiddle() = Frame { SettingsAt(SettingsPage.MorningCall) }

@PreviewTest @Screen @Composable
fun SetupSettingsBottom() = Frame { SettingsAt(SettingsPage.Sources) }

@PreviewTest @Screen @Composable
fun SetupSettingsLoading() = Frame { SettingsContent(SettingsUiState(), SettingsActions()) }

@PreviewTest @Screen @Composable
fun SetupSettingsOffline() = Frame {
    SettingsContent(
        settingsState.copy(settings = Settings(), message = "Could not reach your server. Changes will be saved when you’re back online.", localOnly = true),
        SettingsActions(),
    )
}

@PreviewTest @Part @Composable
fun SetupVaultEmptyInvalidated() = Frame {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VaultSectionContent(VaultUiState(VaultKeyStatus.INVALIDATED, emptyList(), loading = false, notice = "The vault keys were reset. Logins saved before can’t be opened; add them again when I ask."), {}, {})
    }
}

// --- the credential form card ---

private val loginRequest = CredentialRequest(
    id = CredentialRequestId("r1"), name = "chase_checking", kind = CredentialKind.LOGIN,
    fields = listOf(
        FieldSpec("username", "Username", FieldType.USERNAME),
        FieldSpec("password", "Password", FieldType.PASSWORD),
        FieldSpec("otp", "One-time code", FieldType.OTP, required = false),
    ),
    site = "secure.chase.com", purpose = "Log in to Chase to download last month’s statement for your accountant",
    status = CredentialRequestStatus.OPEN, createdAt = Instant.parse("2026-10-26T14:04:00Z"),
)

private val cardRequest = loginRequest.copy(
    name = "visa_personal", kind = CredentialKind.PAYMENT_CARD, site = "shop.example.com", purpose = "Pay for the concert tickets",
    fields = listOf(
        FieldSpec("name", "Name on card", FieldType.NAME),
        FieldSpec("number", "Card number", FieldType.CARD_NUMBER),
        FieldSpec("expiry", "Expiry", FieldType.CARD_EXPIRY),
        FieldSpec("cvc", "CVC", FieldType.CARD_CVC),
    ),
)

@PreviewTest @Part @Composable
fun SetupCredentialLogin() = Frame {
    Column(Modifier.padding(16.dp)) {
        CredentialFormContent(
            FormCardState.Editing(
                CredentialForm(loginRequest, 2026, 10, values = mapOf("username" to "sam", "password" to "hunter2"), touched = setOf("username")),
                error = "Could not reach your server. Your entry is still here; try again.",
            ),
            { _, _ -> }, {}, {},
        )
    }
}

@PreviewTest @Part @Composable
fun SetupCredentialCardErrors() = Frame {
    Column(Modifier.padding(16.dp)) {
        CredentialFormContent(
            FormCardState.Editing(
                CredentialForm(cardRequest, 2026, 10, values = mapOf("number" to "4242 4242 4242 4241", "expiry" to "13/24"), touched = setOf("name", "number", "expiry", "cvc")),
            ),
            { _, _ -> }, {}, {},
        )
    }
}

@PreviewTest @Part @Composable
fun SetupCredentialStates() = Frame {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CredentialFormContent(FormCardState.Loading, { _, _ -> }, {}, {})
        CredentialFormContent(FormCardState.Failed("Could not reach your server. Check your connection."), { _, _ -> }, {}, {})
        CredentialFormContent(FormCardState.Done("Saved. “chase_checking” is locked on your phone and I can use it now."), { _, _ -> }, {}, {})
        CredentialFormContent(FormCardState.Editing(CredentialForm(loginRequest, 2026, 10), saving = true), { _, _ -> }, {}, {})
    }
}
