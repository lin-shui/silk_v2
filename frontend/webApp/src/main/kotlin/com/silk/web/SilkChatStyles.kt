package com.silk.web

/**
 * 参考 [YishenTu/claudian](https://github.com/YishenTu/claudian) 前端显示风格的 CSS 注入。
 * 仅包含 AI 消息卡片样式，无逻辑组件。
 */
object SilkChatStyles {

    /** 注入所有样式到 document head，幂等 */
    fun inject() {
        val styleId = "silk-chat-styles"
        if (kotlinx.browser.document.getElementById(styleId) != null) return

        val style = kotlinx.browser.document.createElement("style") as org.w3c.dom.HTMLElement
        style.id = styleId
        style.textContent = buildStyleSheet()
        kotlinx.browser.document.head?.appendChild(style)
    }

    private fun buildStyleSheet(): String = """
/* ── Animations ── */
@keyframes silk-pulse {
    0%, 100% { opacity: 0.5; }
    50% { opacity: 1; }
}

/* ── AI Message Card (claudian-inspired) ── */
.silk-ai-card {
    background: #FFFBF5;
    border-radius: 12px;
    padding: 0;
    border: 1px solid #E8E0D4;
    box-shadow: 0 1px 4px rgba(169, 137, 77, 0.08);
    transition: all 0.2s ease;
    overflow: hidden;
}

.silk-ai-card:hover {
    box-shadow: 0 2px 8px rgba(169, 137, 77, 0.12);
}

/* ── Header: avatar + name + time ── */
.silk-ai-header {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 14px 16px 8px 16px;
}

.silk-ai-avatar {
    width: 30px;
    height: 30px;
    border-radius: 50%;
    background: linear-gradient(135deg, #C9A86C, #E0CDA0);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 15px;
    flex-shrink: 0;
    box-shadow: 0 1px 3px rgba(201, 168, 108, 0.3);
}

.silk-ai-name {
    font-size: 14px;
    font-weight: 600;
    color: #C9A86C;
    letter-spacing: 0.3px;
}

.silk-ai-time {
    font-size: 11px;
    color: #B8A890;
    margin-left: auto;
    flex-shrink: 0;
}

/* ── Content area ── */
.silk-ai-content {
    padding: 4px 16px 8px 16px;
    line-height: 1.7;
    color: #4A4038;
    font-size: 14px;
    word-break: break-word;
}

/* ── Action buttons (hover reveal) ── */
.silk-ai-actions {
    display: flex;
    gap: 2px;
    padding: 2px 16px 10px 16px;
    opacity: 0;
    transition: opacity 0.2s ease;
}

.silk-ai-card:hover .silk-ai-actions {
    opacity: 1;
}

.silk-ai-action {
    font-size: 11px;
    color: #8A7B6A;
    cursor: pointer;
    padding: 3px 8px;
    border-radius: 4px;
    transition: all 0.15s ease;
    user-select: none;
    display: flex;
    align-items: center;
    gap: 3px;
    background: transparent;
    border: none;
    font-family: inherit;
}

.silk-ai-action:hover {
    color: #4A4038;
    background: rgba(201, 168, 108, 0.1);
}

.silk-ai-action-danger:hover {
    color: #D97B7B;
    background: rgba(217, 123, 123, 0.08);
}

/* ── Transient (generating) indicator ── */
.silk-ai-generating {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 4px 16px 12px 16px;
    font-size: 12px;
    color: #C9A86C;
}

.silk-ai-generating-dot {
    display: inline-block;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #C9A86C;
    animation: silk-pulse 1s ease-in-out infinite;
}

/* ── Thinking / Tool content styling ── */
.silk-thinking-text {
    color: #8A7B6A;
    font-style: italic;
    font-size: 13px;
    padding: 2px 0;
}

.silk-thinking-text .pulse-dot {
    display: inline-block;
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: #C9A86C;
    margin-right: 4px;
    animation: silk-pulse 1.5s ease-in-out infinite;
    vertical-align: middle;
}

/* ── Selection mode checkbox ── */
.silk-select-box {
    width: 20px;
    height: 20px;
    border-radius: 4px;
    border: 2px solid #E8E0D4;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    margin-top: 16px;
    transition: all 0.2s;
    cursor: pointer;
    font-size: 12px;
    color: white;
}

.silk-select-box.selected {
    background: #C9A86C;
    border-color: #C9A86C;
}

.silk-message-nav-highlight {
    background: rgba(201, 168, 108, 0.18) !important;
    outline: 2px solid rgba(201, 168, 108, 0.75);
    outline-offset: 2px;
    box-shadow: 0 0 0 6px rgba(201, 168, 108, 0.12);
    transition: background 0.2s ease, outline-color 0.2s ease, box-shadow 0.2s ease;
}
""".trimIndent()
}
