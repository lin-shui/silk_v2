package com.silk.web

/** Shared visual contract for room headers and message composers. */
internal object ConversationChromeStyles {
    fun buildStyleSheet(): String = """
.silk-conversation-header {
    box-sizing: border-box;
    display: flex;
    align-items: center;
    gap: 12px;
    min-height: 60px;
    padding: 10px 16px;
    color: ${SilkColors.textPrimary};
    background: ${SilkColors.surfaceElevated};
    border-bottom: 1px solid ${SilkColors.border};
    font-family: inherit;
    letter-spacing: 0;
}

.silk-conversation-header-icon {
    box-sizing: border-box;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    flex: 0 0 34px;
    border-radius: 6px;
    color: ${SilkColors.primaryDark};
    background: rgba(201, 168, 108, 0.14);
    font-size: 17px;
    font-weight: 700;
}

.silk-conversation-header-title {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
}

.silk-conversation-header-title-row,
.silk-conversation-header-subtitle {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
}

.silk-conversation-header-title-text {
    overflow: hidden;
    color: ${SilkColors.textPrimary};
    font-size: 16px;
    font-weight: 600;
    letter-spacing: 0;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.silk-conversation-header-subtitle {
    overflow: hidden;
    color: ${SilkColors.textSecondary};
    font-size: 11px;
    font-weight: 400;
    letter-spacing: 0;
}

.silk-conversation-header-actions {
    display: flex;
    flex: 0 0 auto;
    flex-wrap: wrap;
    align-items: center;
    justify-content: flex-end;
    gap: 6px;
    min-width: 0;
    margin-left: auto;
}

.silk-conversation-header-action {
    box-sizing: border-box;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    padding: 0;
    border: 1px solid ${SilkColors.border};
    border-radius: 6px;
    color: ${SilkColors.textSecondary};
    background: ${SilkColors.surfaceElevated};
    font: inherit;
    cursor: pointer;
    transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}

.silk-conversation-header-action:hover:not(:disabled) {
    color: ${SilkColors.textPrimary};
    border-color: ${SilkColors.primaryLight};
    background: rgba(201, 168, 108, 0.1);
}

.silk-conversation-header-action.has-label {
    width: auto;
    min-width: 34px;
    padding: 0 10px;
    gap: 6px;
}

.silk-conversation-header-action.is-primary {
    color: white;
    border-color: ${SilkColors.primary};
    background: ${SilkColors.primary};
}

.silk-conversation-header-action.is-danger {
    color: ${SilkColors.error};
    border-color: rgba(217, 123, 123, 0.42);
}

.silk-conversation-header-action:disabled {
    cursor: default;
    opacity: 0.46;
}

.silk-conversation-header-action-icon {
    font-size: 15px;
    line-height: 1;
}

.silk-conversation-header-action-label {
    font-size: 12px;
    font-weight: 500;
    letter-spacing: 0;
    white-space: nowrap;
}

.silk-conversation-header-status {
    max-width: 160px;
    overflow: hidden;
    color: ${SilkColors.textSecondary};
    font-size: 11px;
    letter-spacing: 0;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.silk-conversation-composer {
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 12px 16px;
    background: ${SilkColors.surfaceElevated};
    border-top: 1px solid ${SilkColors.border};
    box-shadow: 0 -2px 8px rgba(74, 64, 56, 0.04);
}

.silk-conversation-composer-context,
.silk-conversation-composer-preview {
    order: 0;
    min-width: 0;
}

.silk-conversation-composer-accessories {
    order: 1;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
    min-width: 0;
    position: relative;
}

.silk-conversation-composer-input-row {
    order: 2;
    display: flex;
    align-items: flex-end;
    gap: 10px;
    min-width: 0;
    position: relative;
}

.silk-conversation-composer-input-surface {
    flex: 1 1 auto;
    min-width: 0;
    position: relative;
}

.silk-conversation-composer-input {
    box-sizing: border-box;
    display: block;
    width: 100%;
    min-height: 42px;
    max-height: 160px;
    padding: 9px 12px;
    resize: none;
    border: 1px solid ${SilkColors.border};
    border-radius: 8px;
    outline: none;
    color: ${SilkColors.textPrimary};
    background: ${SilkColors.surface};
    font: inherit;
    font-size: 14px;
    line-height: 1.5;
    letter-spacing: 0;
    white-space: pre-wrap;
    transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.silk-conversation-composer-input:focus {
    border-color: ${SilkColors.primary};
    box-shadow: 0 0 0 2px rgba(201, 168, 108, 0.12);
}

.silk-conversation-composer-primary {
    box-sizing: border-box;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 42px;
    height: 42px;
    flex: 0 0 42px;
    padding: 0;
    border: 0;
    border-radius: 8px;
    color: white;
    background: ${SilkColors.primary};
    font-size: 20px;
    font-weight: 600;
    line-height: 1;
    cursor: pointer;
    transition: opacity 0.15s ease, background 0.15s ease;
}

.silk-conversation-composer-primary.is-stop {
    background: ${SilkColors.error};
    font-size: 13px;
}

.silk-conversation-composer-primary:disabled {
    cursor: default;
    opacity: 0.42;
}

.silk-conversation-composer-tools {
    order: 3;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
    min-width: 0;
}

.silk-conversation-composer-tool {
    box-sizing: border-box;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 42px;
    height: 42px;
    padding: 0 11px;
    border: 0;
    border-radius: 8px;
    color: ${SilkColors.textPrimary};
    background: ${SilkColors.secondary};
    font: inherit;
    font-size: 18px;
    letter-spacing: 0;
    cursor: pointer;
}

.silk-conversation-composer-tool.is-danger {
    color: white;
    background: ${SilkColors.error};
    font-size: 13px;
    font-weight: 600;
}

.silk-conversation-composer-tool:disabled {
    cursor: default;
    opacity: 0.55;
}

.silk-conversation-composer-image-preview {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    border: 1px solid rgba(201, 168, 108, 0.25);
    border-radius: 8px;
    background: rgba(201, 168, 108, 0.08);
}

.silk-conversation-composer-image-preview-thumbnail {
    width: 60px;
    height: 60px;
    flex: 0 0 60px;
    border-radius: 4px;
    object-fit: cover;
}

.silk-conversation-composer-image-preview-label {
    flex: 1 1 auto;
    min-width: 0;
    color: ${SilkColors.textSecondary};
    font-size: 13px;
}

.silk-conversation-composer-image-preview-remove {
    width: 30px;
    height: 30px;
    padding: 0;
    border: 0;
    border-radius: 6px;
    color: ${SilkColors.textSecondary};
    background: transparent;
    font-size: 18px;
    cursor: pointer;
}

@media (max-width: 760px) {
    .silk-conversation-header {
        flex-wrap: wrap;
        gap: 7px;
        padding: 10px 12px;
    }
    .silk-conversation-header-title { flex: 1 1 180px; }
    .silk-conversation-header-actions { justify-content: flex-end; }
    .silk-conversation-composer { padding: 10px; }
    .silk-conversation-composer-input-row { gap: 6px; }
    .silk-conversation-composer-tools { justify-content: flex-start; }
}
""".trimIndent()
}
