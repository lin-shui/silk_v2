# CI Script Smoke Scope

## 目标

这个 workflow 用来验证 `silk.sh` 的装配脚本链路，不把这些更重的检查塞回基础快检。

工作流文件：`.github/workflows/ci-script-smoke.yml`

## 触发方式

- `push`
- `pull_request`
- `workflow_dispatch`

说明：

- `push` / `pull_request` 只在脚本、Gradle 配置、Web / Android 相关目录变更时触发，避免无关改动也跑装配 smoke。
- 这里不复用 `ci-fast-validation.yml` 的触发矩阵；它的职责仍然是快速拦截。

## 当前覆盖（2026-04-21）

### Web 脚本链路

- [x] `./silk.sh build`
- [x] `:frontend:webApp:browserProductionWebpack`
- [x] 构建产物复制到 `backend/static/`
- [x] `backend/static/index.html` 与生产构建输出一致

### Android 脚本链路

- [x] `./silk.sh build-apk`
- [x] GitHub-hosted runner 上安装 Android SDK 与 `local.properties`
- [x] `:frontend:androidApp:assembleDebug`
- [x] APK 复制到 `backend/static/files/androidApp-debug.apk`
- [x] `backend/static/silk-*.apk` 与 `silk.apk` 链接更新

### Artifact

- [x] 上传 Web 生产构建目录
- [x] 上传脚本构建出的 APK 与 `backend/static` 复制结果

## 明确未覆盖

- [ ] `./silk.sh build-all` 的顺序编排本身
- [ ] `./silk.sh start` 的服务启动 smoke
- [ ] `./silk.sh deploy` 的端口清理、Weaviate、启动全链路
- [ ] `./silk.sh build-hap` / Harmony HAP 构建

## 运行备注

- Web job 不上传整个 `backend/static/`，避免把仓库已有静态文件和历史 APK 一并打包成超大 artifact。
- Android job 复用快检里的 SDK 安装方式，并显式写入 `local.properties`，避免 `silk.sh build-apk` 只在本地开发机可用。
- 这层 smoke 的重点是“脚本能否把装配动作串起来并把产物放到交付目录”，不是替代快检里的单测和编译门禁。

## 下一步建议

1. 如果后续需要验证脚本编排顺序而不是单个子命令，可再补一个 `build-all` job，但不要和现有两个 job 重复上传大产物。
2. 若要覆盖 `start` / `deploy`，单独准备临时端口、后台进程清理和 Weaviate mock/skip 策略，避免把 runner 弄成状态机。
3. Harmony 仍应保留为独立 workflow，放到具备 DevEco / hvigor / hdc 的 runner。
