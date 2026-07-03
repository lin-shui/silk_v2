package com.silk.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

internal const val CHAT_MESSAGES_CONTAINER_ID = "messages"
internal const val WORKFLOW_MESSAGES_CONTAINER_ID = "wf-messages"
private const val MESSAGE_NAV_HIGHLIGHT_CLASS = "silk-message-nav-highlight"
private const val MESSAGE_NAV_HIGHLIGHT_DURATION_MS = 2200

internal fun messageDomId(messageId: String): String = "silk-msg-$messageId"

internal fun computeMessageNavigationScrollTop(
    messageOffsetTop: Double,
    messageHeight: Double,
    containerHeight: Double,
    scrollHeight: Double,
): Double {
    val maxScrollTop = (scrollHeight - containerHeight).coerceAtLeast(0.0)
    val centeredTop = messageOffsetTop - ((containerHeight - messageHeight) / 2.0)
    return centeredTop.coerceIn(0.0, maxScrollTop)
}

internal fun scrollMessageIntoContainer(containerId: String, messageId: String): Boolean {
    val container = document.getElementById(containerId) as? HTMLElement ?: return false
    val target = document.getElementById(messageDomId(messageId)) as? HTMLElement ?: return false

    val containerRect = container.getBoundingClientRect()
    val targetRect = target.getBoundingClientRect()
    val targetOffsetTop = targetRect.top - containerRect.top + container.scrollTop.toDouble()
    val nextScrollTop = computeMessageNavigationScrollTop(
        messageOffsetTop = targetOffsetTop,
        messageHeight = targetRect.height,
        containerHeight = container.clientHeight.toDouble(),
        scrollHeight = container.scrollHeight.toDouble(),
    )

    clearMessageNavigationHighlights(container)
    target.classList.add(MESSAGE_NAV_HIGHLIGHT_CLASS)
    val scrollOptions = js("({})")
    scrollOptions.top = nextScrollTop
    scrollOptions.behavior = "smooth"
    container.asDynamic().scrollTo(scrollOptions)
    window.setTimeout({
        target.classList.remove(MESSAGE_NAV_HIGHLIGHT_CLASS)
    }, MESSAGE_NAV_HIGHLIGHT_DURATION_MS)
    return true
}

private fun clearMessageNavigationHighlights(container: HTMLElement) {
    val highlighted = container.querySelectorAll(".$MESSAGE_NAV_HIGHLIGHT_CLASS")
    for (index in 0 until highlighted.length) {
        (highlighted.item(index) as? HTMLElement)?.classList?.remove(MESSAGE_NAV_HIGHLIGHT_CLASS)
    }
}
