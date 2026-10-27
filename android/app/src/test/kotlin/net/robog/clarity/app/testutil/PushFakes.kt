package net.robog.clarity.app.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.robog.clarity.app.push.NotificationPlan
import net.robog.clarity.app.push.PushController
import net.robog.clarity.app.push.PushNotifier
import net.robog.clarity.app.push.PushPrefs

class FakePushController(enabled: Boolean = true, asked: Boolean = false) : PushController {
    val enabledState = MutableStateFlow(enabled)
    val askedState = MutableStateFlow(asked)
    var reconciles = 0
    override val enabled: Flow<Boolean> = enabledState
    override val batteryAsked: Flow<Boolean> = askedState
    override suspend fun setEnabled(on: Boolean) { enabledState.value = on; reconcile() }
    override suspend fun markBatteryAsked() { askedState.value = true }
    override suspend fun reconcile() { reconciles++ }
}

class FakePushPrefs(enabled: Boolean = true, asked: Boolean = false) : PushPrefs {
    val enabledState = MutableStateFlow(enabled)
    val askedState = MutableStateFlow(asked)
    override val enabled: Flow<Boolean> = enabledState
    override suspend fun setEnabled(on: Boolean) { enabledState.value = on }
    override val batteryAsked: Flow<Boolean> = askedState
    override suspend fun markBatteryAsked() { askedState.value = true }
}

class RecordingPushNotifier : PushNotifier {
    val posted = mutableListOf<NotificationPlan>()
    override fun post(plan: NotificationPlan) { posted += plan }
    var clearedAll = 0
    override fun clearAll() { clearedAll++ }
}
