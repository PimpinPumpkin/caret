/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo
import app.caret.compat.AppWorkarounds
import app.caret.latin.common.Constants.ImeOption.NO_FLOATING_GESTURE_PREVIEW
import app.caret.latin.common.Constants.ImeOption.NO_MICROPHONE
import app.caret.latin.common.containsValueWhenSplit
import app.caret.latin.utils.InputTypeUtils
import app.caret.latin.utils.Log
import java.util.ArrayList
import java.util.Arrays

/**
 * Class to hold attributes of the input field.
 */
class InputAttributes {
    private val TAG = InputAttributes::class.java.simpleName

    @JvmField val targetApplicationPackageName: String?
    @JvmField val inputTypeShouldAutoCorrect: Boolean
    @JvmField val isPasswordField: Boolean
    @JvmField val shouldShowSuggestions: Boolean
    @JvmField val mayOverrideShowingSuggestions: Boolean
    @JvmField val applicationSpecifiedCompletionOn: Boolean
    @JvmField val shouldInsertSpacesAutomatically: Boolean
    @JvmField val shouldShowVoiceInputKey: Boolean
    /** The field does not rule out a microphone key (not a password or email field, no opt-out). */
    @JvmField val fieldAllowsMicrophone: Boolean
    @JvmField val noLearning: Boolean
    /**
     * Whether the floating gesture preview should be disabled. If true, this should override the
     * corresponding keyboard settings preference, always suppressing the floating preview text.
     * {@link app.caret.latin.settings.SettingsValues#mGestureFloatingPreviewTextEnabled}
     */
    @JvmField val disableGestureFloatingPreviewText: Boolean
    @JvmField val isGeneralTextInput: Boolean
    @JvmField val inputType: Int
    private val editorInfo: EditorInfo?
    private val packageNameForPrivateImeOptions: String?

    constructor(editorInfo: EditorInfo?, isFullscreenMode: Boolean,
            packageNameForPrivateImeOptions: String?) {
        this.editorInfo = editorInfo
        this.packageNameForPrivateImeOptions = packageNameForPrivateImeOptions
        targetApplicationPackageName = if (null != editorInfo) editorInfo.packageName else null
        inputType = AppWorkarounds.adjustInputType(if (null != editorInfo) editorInfo.inputType else 0, targetApplicationPackageName)
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        isPasswordField = InputTypeUtils.isPasswordInputType(inputType)
                || InputTypeUtils.isVisiblePasswordInputType(inputType)
        if (inputClass != InputType.TYPE_CLASS_TEXT) {
            // If we are not looking at a TYPE_CLASS_TEXT field, the following strange
            // cases may arise, so we do a couple sanity checks for them. If it's a
            // TYPE_CLASS_TEXT field, these special cases cannot happen, by construction
            // of the flags.
            if (null == editorInfo) {
                Log.w(TAG, "No editor info for this field. Bug?")
            } else if (InputType.TYPE_NULL == inputType) {
                // TODO: We should honor TYPE_NULL specification.
                Log.i(TAG, "InputType.TYPE_NULL is specified")
            } else if (inputClass == 0) {
                // TODO: is this check still necessary?
                Log.w(TAG, String.format("Unexpected input class: inputType=0x%08x"
                        + " imeOptions=0x%08x", inputType, editorInfo.imeOptions))
            }
            shouldShowSuggestions = false
            mayOverrideShowingSuggestions = false
            inputTypeShouldAutoCorrect = false
            applicationSpecifiedCompletionOn = false
            shouldInsertSpacesAutomatically = false
            shouldShowVoiceInputKey = false
            fieldAllowsMicrophone = false
            disableGestureFloatingPreviewText = false
            isGeneralTextInput = false
            noLearning = false
            return
        }

        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val flagNoSuggestions = 0 != (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val flagMultiLine = 0 != (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val flagAutoCorrect = 0 != (inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        val flagAutoComplete = 0 != (inputType and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)

        // TODO: Have a helper method in InputTypeUtils
        // Make sure that passwords are not displayed in {@link SuggestionStripView}.
        shouldShowSuggestions = !isPasswordField && !flagNoSuggestions
        mayOverrideShowingSuggestions = !isPasswordField

        shouldInsertSpacesAutomatically = InputTypeUtils.isAutoSpaceFriendlyType(inputType)

        fieldAllowsMicrophone = !(isPasswordField
                || InputTypeUtils.isEmailVariation(variation)
                || hasNoMicrophoneKeyOption())
        shouldShowVoiceInputKey = fieldAllowsMicrophone
                && RichInputMethodManager.isInitialized() // avoid crash when only using spell checker
                && RichInputMethodManager.getInstance().isShortcutImeReady

        disableGestureFloatingPreviewText = InputAttributes.inPrivateImeOptions(
                packageNameForPrivateImeOptions, NO_FLOATING_GESTURE_PREVIEW, editorInfo)

        // autocorrect if explicitly wanted, but also for most multi-line input types (like AOSP keyboard)
        // originally, URI and email were always excluded from autocorrect (in Suggest.java), but this is
        //  and unexpected place, and if the input field explicitly requests autocorrect we should follow the flag
        // Caret: also for fields of one line that do not say (a search bar, a title, a name of a list), as on
        // Gboard: left out are only fields that ask for no suggestions and the kinds where words are not
        // meant, addresses of mail and web, passwords, names of people. Web pages mark every field alike, so
        // there the page has to ask (browsers do, for ordinary text).
        // A search field is corrected even when it asks for no suggestions: launchers and many apps set that
        // on their search bars so that nothing is underlined, and a misspelled search finds nothing.
        val isSearch = ((editorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH
        inputTypeShouldAutoCorrect = flagAutoCorrect || (
                variation != InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
                && variation != InputType.TYPE_TEXT_VARIATION_URI
                && variation != InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && !isPasswordField
                && !InputTypeUtils.isEmailVariation(variation)
                && (!flagNoSuggestions || isSearch)
        )

        applicationSpecifiedCompletionOn = flagAutoComplete && isFullscreenMode

        // If we come here, inputClass is always TYPE_CLASS_TEXT
        isGeneralTextInput = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS != variation
                && InputType.TYPE_TEXT_VARIATION_PASSWORD != variation
                && InputType.TYPE_TEXT_VARIATION_PHONETIC != variation
                && InputType.TYPE_TEXT_VARIATION_URI != variation
                && InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != variation
                && InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS != variation
                && InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD != variation


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            noLearning = (editorInfo!!.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        else
            noLearning = false
    }

    val isTypeNull: Boolean
        get() = InputType.TYPE_NULL == inputType

    fun isSameInputType(editorInfo: EditorInfo?): Boolean {
        return editorInfo!!.inputType == inputType && this.editorInfo != null
                && (this.editorInfo.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) == (editorInfo.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII)
    }

    private fun hasNoMicrophoneKeyOption(): Boolean {
        return InputAttributes.inPrivateImeOptions(packageNameForPrivateImeOptions, NO_MICROPHONE, editorInfo)
    }

    @Suppress("unused")
    private fun dumpFlags(inputTypeToDump: Int) {
        val inputClass = inputTypeToDump and InputType.TYPE_MASK_CLASS
        val inputClassString = toInputClassString(inputClass)
        val variationString = toVariationString(
                inputClass, inputTypeToDump and InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val flagsString = toFlagsString(inputTypeToDump and InputType.TYPE_MASK_FLAGS)
        Log.i(TAG, "Input class: " + inputClassString)
        Log.i(TAG, "Variation: " + variationString)
        Log.i(TAG, "Flags: " + flagsString)
    }

    // Pretty print
    override fun toString(): String {
        return String.format(
                "%s: inputType=0x%08x%s%s%s%s%s targetApp=%s\n", javaClass.simpleName,
                inputType,
                (if (inputTypeShouldAutoCorrect) " shouldAutoCorrect" else ""),
                (if (isPasswordField) " password" else ""),
                (if (shouldShowSuggestions) " shouldShowSuggestions" else ""),
                (if (applicationSpecifiedCompletionOn) " appSpecified" else ""),
                (if (shouldInsertSpacesAutomatically) " insertSpaces" else ""),
                targetApplicationPackageName)
    }

    companion object {
        private fun toInputClassString(inputClass: Int): String {
            return when (inputClass) {
                InputType.TYPE_CLASS_TEXT -> "TYPE_CLASS_TEXT"
                InputType.TYPE_CLASS_PHONE -> "TYPE_CLASS_PHONE"
                InputType.TYPE_CLASS_NUMBER -> "TYPE_CLASS_NUMBER"
                InputType.TYPE_CLASS_DATETIME -> "TYPE_CLASS_DATETIME"
                else -> String.format("unknownInputClass<0x%08x>", inputClass)
            }
        }

        private fun toVariationString(inputClass: Int, variation: Int): String {
            return when (inputClass) {
                InputType.TYPE_CLASS_TEXT -> toTextVariationString(variation)
                InputType.TYPE_CLASS_NUMBER -> toNumberVariationString(variation)
                InputType.TYPE_CLASS_DATETIME -> toDatetimeVariationString(variation)
                else -> ""
            }
        }

        private fun toTextVariationString(variation: Int): String {
            return when (variation) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> " TYPE_TEXT_VARIATION_EMAIL_ADDRESS"
                InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> "TYPE_TEXT_VARIATION_EMAIL_SUBJECT"
                InputType.TYPE_TEXT_VARIATION_FILTER -> "TYPE_TEXT_VARIATION_FILTER"
                InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> "TYPE_TEXT_VARIATION_LONG_MESSAGE"
                InputType.TYPE_TEXT_VARIATION_NORMAL -> "TYPE_TEXT_VARIATION_NORMAL"
                InputType.TYPE_TEXT_VARIATION_PASSWORD -> "TYPE_TEXT_VARIATION_PASSWORD"
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> "TYPE_TEXT_VARIATION_PERSON_NAME"
                InputType.TYPE_TEXT_VARIATION_PHONETIC -> "TYPE_TEXT_VARIATION_PHONETIC"
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> "TYPE_TEXT_VARIATION_POSTAL_ADDRESS"
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> "TYPE_TEXT_VARIATION_SHORT_MESSAGE"
                InputType.TYPE_TEXT_VARIATION_URI -> "TYPE_TEXT_VARIATION_URI"
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> "TYPE_TEXT_VARIATION_VISIBLE_PASSWORD"
                InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> "TYPE_TEXT_VARIATION_WEB_EDIT_TEXT"
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS"
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> "TYPE_TEXT_VARIATION_WEB_PASSWORD"
                else -> String.format("unknownVariation<0x%08x>", variation)
            }
        }

        private fun toNumberVariationString(variation: Int): String {
            return when (variation) {
                InputType.TYPE_NUMBER_VARIATION_NORMAL -> "TYPE_NUMBER_VARIATION_NORMAL"
                InputType.TYPE_NUMBER_VARIATION_PASSWORD -> "TYPE_NUMBER_VARIATION_PASSWORD"
                else -> String.format("unknownVariation<0x%08x>", variation)
            }
        }

        private fun toDatetimeVariationString(variation: Int): String {
            return when (variation) {
                InputType.TYPE_DATETIME_VARIATION_NORMAL -> "TYPE_DATETIME_VARIATION_NORMAL"
                InputType.TYPE_DATETIME_VARIATION_DATE -> "TYPE_DATETIME_VARIATION_DATE"
                InputType.TYPE_DATETIME_VARIATION_TIME -> "TYPE_DATETIME_VARIATION_TIME"
                else -> String.format("unknownVariation<0x%08x>", variation)
            }
        }

        private fun toFlagsString(flags: Int): String {
            val flagsArray = ArrayList<String>()
            if (0 != (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
                flagsArray.add("TYPE_TEXT_FLAG_NO_SUGGESTIONS")
            if (0 != (flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE))
                flagsArray.add("TYPE_TEXT_FLAG_MULTI_LINE")
            if (0 != (flags and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE))
                flagsArray.add("TYPE_TEXT_FLAG_IME_MULTI_LINE")
            if (0 != (flags and InputType.TYPE_TEXT_FLAG_CAP_WORDS))
                flagsArray.add("TYPE_TEXT_FLAG_CAP_WORDS")
            if (0 != (flags and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
                flagsArray.add("TYPE_TEXT_FLAG_CAP_SENTENCES")
            if (0 != (flags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS))
                flagsArray.add("TYPE_TEXT_FLAG_CAP_CHARACTERS")
            if (0 != (flags and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT))
                flagsArray.add("TYPE_TEXT_FLAG_AUTO_CORRECT")
            if (0 != (flags and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE))
                flagsArray.add("TYPE_TEXT_FLAG_AUTO_COMPLETE")
            return if (flagsArray.isEmpty()) "" else Arrays.toString(flagsArray.toArray())
        }

        @JvmStatic
        fun inPrivateImeOptions(packageName: String?, key: String, editorInfo: EditorInfo?): Boolean {
            if (editorInfo == null) return false
            val findingKey = if (packageName != null) packageName + "." + key else key
            return containsValueWhenSplit(editorInfo.privateImeOptions, findingKey, ",")
        }
    }
}
