package com.silk.web

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun WorkflowScene(appState: WebAppState) {
    val user = appState.currentUser ?: return
    val scope = rememberCoroutineScope()
    var workflows by remember { mutableStateOf<List<WorkflowItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(user.id) {
        isLoading = true
        workflows = ApiClient.getWorkflows(user.id)
        isLoading = false
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            height(100.percent)
            width(100.percent)
            property("overflow", "hidden")
            property("background", SilkColors.backgroundGradient)
        }
    }) {
        // Left: workflow list
        Div({
            style {
                width(320.px)
                property("flex-shrink", "0")
                property("border-right", "1px solid ${SilkColors.border}")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                backgroundColor(Color(SilkColors.surface))
            }
        }) {
            // Header
            Div({
                style {
                    padding(16.px)
                    property("border-bottom", "1px solid ${SilkColors.border}")
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceBetween)
                    alignItems(AlignItems.Center)
                }
            }) {
                Span({
                    style {
                        fontSize(18.px)
                        fontWeight("bold")
                        color(Color(SilkColors.textPrimary))
                    }
                }) { Text("工作流") }
                Button({
                    style {
                        backgroundColor(Color(SilkColors.primary))
                        color(Color.white)
                        border(0.px)
                        borderRadius(6.px)
                        padding(6.px, 12.px)
                        property("cursor", "pointer")
                    }
                    onClick { showCreateDialog = true }
                }) { Text("+ 创建") }
            }

            // List
            Div({
                style {
                    property("flex", "1")
                    property("overflow-y", "auto")
                }
            }) {
                if (isLoading) {
                    Div({ style { padding(20.px); property("text-align", "center") } }) {
                        Text("加载中...")
                    }
                } else if (workflows.isEmpty()) {
                    Div({
                        style {
                            padding(40.px, 20.px)
                            property("text-align", "center")
                            color(Color(SilkColors.textSecondary))
                        }
                    }) {
                        Text("暂无工作流")
                    }
                } else {
                    workflows.forEach { wf ->
                        Div({
                            style {
                                padding(12.px, 16.px)
                                property("border-bottom", "1px solid ${SilkColors.border}")
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.SpaceBetween)
                                alignItems(AlignItems.Center)
                            }
                        }) {
                            Span({
                                style {
                                    color(Color(SilkColors.textPrimary))
                                    fontSize(14.px)
                                }
                            }) { Text(wf.name) }
                            Button({
                                style {
                                    backgroundColor(Color("transparent"))
                                    color(Color(SilkColors.error))
                                    border(0.px)
                                    property("cursor", "pointer")
                                    fontSize(12.px)
                                }
                                onClick {
                                    scope.launch {
                                        ApiClient.deleteWorkflow(wf.id, user.id)
                                        workflows = ApiClient.getWorkflows(user.id)
                                    }
                                }
                            }) { Text("删除") }
                        }
                    }
                }
            }
        }

        // Right: placeholder
        Div({
            style {
                property("flex", "1")
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
                alignItems(AlignItems.Center)
                flexDirection(FlexDirection.Column)
            }
        }) {
            Span({ style { fontSize(48.px); marginBottom(16.px) } }) { Text("\uD83D\uDD17") }
            Span({
                style {
                    fontSize(18.px)
                    color(Color(SilkColors.textSecondary))
                }
            }) { Text("工作流功能开发中") }
            Span({
                style {
                    fontSize(14.px)
                    color(Color(SilkColors.textLight))
                    marginTop(8.px)
                }
            }) { Text("可在左侧创建工作流名称，后续将支持多机器人编排") }
        }
    }

    // Create dialog
    if (showCreateDialog) {
        Div({
            style {
                position(Position.Fixed)
                top(0.px); left(0.px); right(0.px); bottom(0.px)
                backgroundColor(Color("rgba(0,0,0,0.4)"))
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
                alignItems(AlignItems.Center)
                property("z-index", "1000")
            }
            onClick { showCreateDialog = false }
        }) {
            Div({
                style {
                    backgroundColor(Color.white)
                    borderRadius(12.px)
                    padding(24.px)
                    width(360.px)
                    property("box-shadow", "0 8px 32px rgba(0,0,0,0.15)")
                }
                onClick { it.stopPropagation() }
            }) {
                H3({ style { marginTop(0.px); color(Color(SilkColors.textPrimary)) } }) { Text("创建工作流") }
                Input(InputType.Text) {
                    value(newName)
                    onInput { newName = it.value }
                    attr("placeholder", "工作流名称")
                    style {
                        width(100.percent)
                        height(40.px)
                        borderRadius(6.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        padding(8.px)
                        fontSize(14.px)
                        marginBottom(16.px)
                        property("box-sizing", "border-box")
                    }
                }
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        justifyContent(JustifyContent.FlexEnd)
                        property("gap", "8px")
                    }
                }) {
                    Button({
                        style {
                            backgroundColor(Color(SilkColors.surface))
                            color(Color(SilkColors.textSecondary))
                            border(1.px, LineStyle.Solid, Color(SilkColors.border))
                            borderRadius(6.px)
                            padding(8.px, 16.px)
                            property("cursor", "pointer")
                        }
                        onClick { showCreateDialog = false; newName = "" }
                    }) { Text("取消") }
                    Button({
                        style {
                            backgroundColor(Color(SilkColors.primary))
                            color(Color.white)
                            border(0.px)
                            borderRadius(6.px)
                            padding(8.px, 16.px)
                            property("cursor", "pointer")
                        }
                        onClick {
                            if (newName.isNotBlank()) {
                                scope.launch {
                                    ApiClient.createWorkflow(newName.trim(), "", user.id)
                                    workflows = ApiClient.getWorkflows(user.id)
                                    showCreateDialog = false
                                    newName = ""
                                }
                            }
                        }
                    }) { Text("创建") }
                }
            }
        }
    }
}
