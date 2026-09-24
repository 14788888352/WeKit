*
     * The colored range is the @ character itself plus the non-space run immediately following
     * it — WeChat renders a mention inside the bubble as @nickname (with trailing spaces), so a
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
     * WeChat's NeatTextView funnels every text change through
     * setText(CharSequence, TextView.BufferType, Boolean), which spannable-izes the argument
     * (preserving spans), refreshes its cached text and re-lays out. That overload is obfuscated
     * and absent from the compile-time stub, so it is reached reflectively first — this is the
     * path that keeps the widget's internal state coherent. If the obfuscated name ever changes,
     * we fall back to writing straight into the wrapped TextView, which is what WeChat's own
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
