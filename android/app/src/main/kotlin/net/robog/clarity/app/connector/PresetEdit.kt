package net.robog.clarity.app.connector

import net.robog.clarity.shared.connector.PresetForm
import net.robog.clarity.shared.connector.PresetInput
import net.robog.clarity.shared.connector.PresetInputError
import net.robog.clarity.shared.connector.PresetInputType
import net.robog.clarity.shared.connector.PresetInputs
import net.robog.clarity.shared.connector.PresetTargets

/**
 * An item opened on its preset's short form: the answers so far, the choice inputs answered with
 * "Other", and whether the full form ("Advanced settings") is open below the questions.
 *
 * The answers write into the item's values as they change ([answer]); the item's values are what is
 * saved. A value changed in the full form is kept: the answers only rewrite values that are still
 * what they produced (see [PresetInputs.apply]).
 */
data class PresetEdit(
    val preset: ItemPreset,
    val answers: Map<String, String> = emptyMap(),
    val others: Set<String> = emptySet(),
    val advanced: Boolean = false,
) {
    val form: PresetForm get() = preset.simple!!

    /** The answer shown for [input]: its own, or the item property it is bound to. */
    fun answerOf(input: PresetInput, values: FormValues): String = input.property?.let(values::text) ?: answers[input.id].orEmpty()

    fun errors(values: FormValues): Map<String, PresetInputError> =
        PresetInputs.errors(form, answers) { values.hasSecret(it) || values.text(it).isNotBlank() }

    /** Item properties changed in the full form so the answers no longer set them. */
    fun diverged(values: FormValues): Set<String> = PresetInputs.diverged(form, answers, values.targets(form))

    /** [input] answered with [value]: the new edit and the item's values. */
    fun answer(input: PresetInput, value: String, values: FormValues): Pair<PresetEdit, FormValues> {
        input.property?.let { return this to values.withText(it, value) }
        val next = answers + (input.id to value)
        return copy(answers = next) to values.withTargets(PresetInputs.apply(form, answers, next, values.targets(form)))
    }

    /** "Other" picked (or a listed choice again) for a choice input. */
    fun chooseOther(input: PresetInput, other: Boolean, values: FormValues): Pair<PresetEdit, FormValues> {
        if (other == (input.id in others)) return this to values
        val (edit, next) = answer(input, if (other) "" else input.default.orEmpty(), values)
        return edit.copy(others = if (other) others + input.id else others - input.id) to next
    }

    companion object {
        /** A new item from [preset]: the defaults answered, and what they produce written into [values]. */
        fun start(preset: ItemPreset, values: FormValues): Pair<PresetEdit, FormValues> {
            val form = preset.simple!!
            val answers = form.inputs.filter { it.property == null }.associate { it.id to it.default.orEmpty() }
            return PresetEdit(preset, answers) to values.withTargets(PresetInputs.apply(form, emptyMap(), answers, values.targets(form)))
        }

        /** A saved item from [preset]: its answers read back from its values. */
        fun reopen(preset: ItemPreset, values: FormValues): PresetEdit {
            val form = preset.simple!!
            val answers = PresetInputs.recover(form, values.targets(form))
            val others = form.inputs.filter { i ->
                i.type == PresetInputType.CHOICE && i.other && answers[i.id].orEmpty().let { a -> a.isNotEmpty() && i.choices.none { it.value == a } }
            }.map { it.id }.toSet()
            return PresetEdit(preset, answers, others)
        }
    }
}

private fun FormValues.targets(form: PresetForm) =
    PresetTargets(texts = form.scalars.keys.associateWith(::text), lists = form.lists.keys.associateWith(::list))

private fun FormValues.withTargets(t: PresetTargets): FormValues =
    copy(texts = texts + t.texts, lists = lists + t.lists)
