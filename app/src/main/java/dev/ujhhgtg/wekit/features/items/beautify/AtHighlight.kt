// SPDX-License-Identifier: GPL-3.0-only
package dev.ujhhgtg.wekit.features.items.beautify

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.toColorInt
import com.tencent.mm.ui.widget.MMNeat7extView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.WeColorField
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam

/**
 * Colors the `@` segment inside the bubble body of any mention message.
 *
 * A "mention" is any group-chat message whose `msgsource.atuserlist` is non-empty — i.e. @me,
 * @someone-else, @all and stealth mentions alike, which [MessageInfo.mentionedUsers] already
 * exposes without re-parsing the XML. Both self-sent and other-sent messages are colored.
 *
 * Only the bubble body is touched: the bubble background is left alone, and for a quote message
 * only this message's own body is colored — the quoted preview inside keeps its original color
 * because the preview is a separate view, not part of [MMNeat7extView]'s text.
 */
object AtHighlight : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {
    override val technicalId = "艾特高亮"
    override val nameRes = R.string.feature_at_highlight_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_at_highlight_description
    private const val TAG = "AtHighlight"

    /** WeChat green, the color WeChat itself uses for `@` inside the input field. */
    private const val DEFAULT_LIGHT_FG = "#FF576B95"
    /** A lighter, lower-contrast green for dark mode. */
    private const val DEFAULT_DARK_FG = "#FF7D90B8"

    /**
     * Reusable, color-only span instances keyed by packed ARGB. Spans are immutable and stateless,
     * so a single instance per color can be shared across every message view instead of allocating
     * one per bind.
     */
    private val spanCache = HashMap<Int, ForegroundColorSpan>()

    /** Light-mode `@` color. Read at bind time, so a config change applies to the next bind. */
    var lightFg by WePrefs.prefOption("at_highlight_light_fg", DEFAULT_LIGHT_FG)

    /** Dark-mode `@` color. */
    var darkFg by WePrefs.prefOption("at_highlight_dark_fg", DEFAULT_DARK_FG)

    private fun spanFor(color: Int): ForegroundColorSpan =
        spanCache.getOrPut(color) { ForegroundColorSpan(color) }

    private fun parseColor(value: String, fallback: String): Int =
        runCatching { value.toColorInt() }
            .getOrElse { runCatching { fallback.toColorInt() }.getOrDefault(0xFF576B95.toInt()) }

    /**
     * Picks the configured color for the view's current UI mode, so the highlight stays legible
     * whether WeChat is in light or dark theme.
     */
    private fun colorFor(view: View): Int {
        val dark = (view.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (dark) parseColor(darkFg, DEFAULT_DARK_FG) else parseColor(lightFg, DEFAULT_LIGHT_FG)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    // ── Config dialog ─────────────────────────────────────────────────────────

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var light by remember { mutableStateOf(lightFg) }
            var dark by remember { mutableStateOf(darkFg) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_at_highlight_name)) },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        WeColorField(
                            label = stringResource(R.string.at_highlight_light_color),
                            value = light,
                            onValueChange = { light = it })
                        WeColorField(
                            label = stringResource(R.string.at_highlight_dark_color),
                            value = dark,
                            onValueChange = { dark = it })
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        // Both are WePrefs delegates, so assigning writes through immediately.
                        lightFg = light
                        darkFg = dark
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }

    // ── ICreateViewListener ───────────────────────────────────────────────────

    override fun onCreateView(param: HookParam, view: View) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        // Mentions only exist in group chats; a single-chat atuserlist is always empty, but check
        // the talker anyway so a malformed payload can't slip through.
        if (!msgInfo.isInGroupChat) return
        if (msgInfo.mentionedUsers.isEmpty()) return

        @Suppress("DEPRECATION")
        when (msgInfo.type) {
            // Body-bearing types whose bubble is a single MMNeat7extView. QUOTE is included so the
            // sender's own @'s light up; its quoted preview lives in a separate view, untouched.
            MessageType.TEXT, MessageType.LINK, MessageType.GROUP_NOTE, MessageType.QUOTE -> {
                val neatTextView = view.findViewWhich { it is MMNeat7extView } as? MMNeat7extView
                    ?: return
                applyHighlight(neatTextView)
            }

            else -> Unit
        }
    }

    // ── Span application ──────────────────────────────────────────────────────

    /**
     * Rebuilds [view]'s text as a [SpannableStringBuilder] with every visible `@` occurrence
     * colored, then writes it back through WeChat's own text entry point.
     *
     * `MMNeat7extView` is *not* a `TextView`: it extends `NeatTextView`, which extends
     * `android.view.View` and merely wraps an inner `TextView` (exposed as
     * [NeatTextView.getWrappedTextView]). There is no `.text` property, so the body is read from
     * and written to that wrapped `TextView`, and the write goes through WeChat's real
     * `setText(CharSequence, BufferType, Boolean)` overload so the widget's cached text, internal
     * spans and relayout all stay consistent.
     *
     * The colored range is the `@` character itself plus the non-space run immediately following
     * it — WeChat renders a mention inside the bubble as `@nickname` (with trailing spaces), so a
     * run stops at the first whitespace.
     */
    private fun applyHighlight(view: MMNeat7extView) {
        val wrapped = runCatching { view.getWrappedTextView() }.getOrNull() ?: return
        val current = wrapped.text ?: return
        val plain = current.toString()
        if (plain.indexOf('@') < 0) return

        val sb = SpannableStringBuilder(current, 0, plain.length)
        // Drop spans this feature wrote on a previous bind, so a rebind to a non-mention message
        // (or a shorter @ run) can't leave stale coloring behind. Other features' spans are kept:
        // the builder was seeded from the live text, so their ranges survive untouched.
        for (span in sb.getSpans(0, sb.length, ForegroundColorSpan::class.java)) {
            sb.removeSpan(span)
        }
        val color = colorFor(view)
        val span = spanFor(color)
        // Walk every '@' in the body, left to right, coloring '@' plus the non-space run after
        // it. Indices are code units, matching the span offsets.
        var i = 0
        while (i < plain.length) {
            if (plain[i] != '@') {
                i++
                continue
            }
            var end = i + 1
            while (end < plain.length && !plain[end].isWhitespace()) end++
            sb.setSpan(span, i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = end
        }
        setNeatText(view, wrapped, sb)
    }

    /**
     * Hands [sb] back to the widget.
     *
     * WeChat's `NeatTextView` funnels every text change through
     * `setText(CharSequence, TextView.BufferType, Boolean)`, which spannable-izes the argument
     * (preserving spans), refreshes its cached text and re-lays out. That overload is obfuscated
     * and absent from the compile-time stub, so it is reached reflectively first — this is the
     * path that keeps the widget's internal state coherent. If the obfuscated name ever changes,
     * we fall back to writing straight into the wrapped `TextView`, which is what WeChat's own
     * setter does under the hood and still renders correctly.
     */
    private fun setNeatText(view: MMNeat7extView, wrapped: TextView, sb: SpannableStringBuilder) {
        val applied = runCatching {
            view.reflekt().invokeMethod("c", sb, TextView.BufferType.SPANNABLE, false, superclass = true)
        }.isSuccess
        if (!applied) {
            wrapped.setText(sb, TextView.BufferType.SPANNABLE)
        }
    }
}
