package net.robog.clarity.app.vault

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Keyboard options for a secret: no auto-correct, no capitalization, a keyboard type that keyboards
 * treat as private. Together inside [PrivateKeyboard] the keyboard neither suggests nor learns from it.
 */
fun secretKeyboard(type: KeyboardType = KeyboardType.Password, ime: ImeAction = ImeAction.Next) = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = type,
    imeAction = ime,
)

/** Text fields inside [content] ask the keyboard not to learn from what is typed (`IME_FLAG_NO_PERSONALIZED_LEARNING`). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PrivateKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(NoLearningInterceptor, content)
}

@OptIn(ExperimentalComposeUiApi::class)
private object NoLearningInterceptor : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(request: PlatformTextInputMethodRequest, nextHandler: PlatformTextInputSession): Nothing =
        nextHandler.startInputMethod(
            object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    return connection
                }
            },
        )
}

/** While this is in the composition, the system's autofill ignores the whole screen (the form holds secrets). */
@Composable
fun DisableAutofill() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.importantForAutofill
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose { view.importantForAutofill = before }
    }
}
