# Desktop

## Entry Surface

- `frontend/desktopApp/src/main/kotlin/com/silk/desktop/Main.kt`
- `AppState.kt`
- `ApiClient.kt`
- `GroupListScreen.kt`
- `SettingsScreen.kt`

## Current Shape

- Compose Desktop
- 当前主场景仍是：
  - LOGIN
  - GROUP_LIST
  - CHAT_ROOM
  - SETTINGS
- 不是当前 Workflow / KB 三 Tab 主线承载端

## Why It Still Matters

- CI 会编译并运行桌面轻量单测
- 文件卡片解析合同在这里有独立测试

## Default Validation

- `./gradlew :frontend:desktopApp:test`
- `./gradlew :frontend:desktopApp:compileKotlin`
