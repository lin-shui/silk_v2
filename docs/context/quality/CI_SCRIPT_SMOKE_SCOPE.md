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
- `start` / `deploy` smoke 复用同一 workflow 触发范围；纯 backend 源码变更默认仍由快检覆盖，需要验证运行态启动时可手动 `workflow_dispatch`。
- 这里不复用 `ci-fast-validation.yml` 的触发矩阵；它的职责仍然是快速拦截。

## 当前覆盖（2026-05-09）

### Web 脚本链路

- [x] `./silk.sh build`
- [x] `:frontend:webApp:browserProductionWebpack`
- [x] 构建产物复制到 `backend/static/`
- [x] `backend/static/index.html` 与生产构建输出一致（允许 `silk.sh` 将 `__BUILD_TIMESTAMP__` 替换为真实构建时间戳）

### Android 脚本链路

- [x] `./silk.sh build-apk`
- [x] GitHub-hosted runner 上安装 Android SDK 与 `local.properties`
- [x] `:frontend:androidApp:assembleDebug`
- [x] APK 复制到 `backend/static/files/androidApp-debug.apk`
- [x] `backend/static/silk-*.apk` 与 `silk.apk` 链接更新

### Build-all 编排链路

- [x] `./silk.sh build-all` 顺序编排 smoke
- [x] 临时 Gradle stub 锁定 Web 构建先于 Android APK 构建
- [x] 验证 Web 产物复制、APK 复制和 `silk.apk` 链接更新
- [x] 不重复执行真实 Web / Android 构建，真实构建仍由上面两个 job 覆盖

### 运行态 start/stop 链路

- [x] `./silk.sh start` 使用 CI 临时 `.env` 和独立端口启动
- [x] 本地轻量 Weaviate readiness mock 占位，避免在 runner 上拉起真实 Docker/外部服务
- [x] 真实后端通过 `:backend:run` 启动并响应 `/health`
- [x] 预置 Web 静态 fixture，验证 `silk.sh start` 的 Python 静态服务器路径，不重复跑 Web 构建
- [x] `./silk.sh stop` 清理后端与前端进程，mock 进程由 workflow 显式回收

### Deploy 编排链路

- [x] `./silk.sh deploy` 使用 CI 临时 `.env` 和独立端口启动
- [x] 先占用 backend / frontend 端口，验证 `deploy` 的强制端口清理能让后续启动接管端口
- [x] Gradle stub 锁定 `deploy -> build-all -> Web build -> Android assembleDebug -> backend:run` 调用顺序
- [x] 本地轻量 Weaviate readiness mock 让 `weaviate_start` 走“已就绪直接复用”路径，不拉真实 Docker/外部服务
- [x] 后端 `:backend:run` 用 mock server 占位，只验证 `deploy` 的进程编排、端口就绪和 `/health` smoke；真实 backend 启动由 `start` job 覆盖
- [x] 验证 Web 产物复制、APK 复制、`silk.apk` 链接、后端 `/health` 和前端静态页

### Artifact

- [x] 上传 Web 生产构建目录
- [x] 上传脚本构建出的 APK 与 `backend/static` 复制结果
- [x] 上传 start smoke 的 backend / frontend / Weaviate mock 日志
- [x] 上传 deploy smoke 的脚本输出、backend / frontend / Weaviate mock 日志

## 明确未覆盖

- [ ] `./silk.sh start` 的服务启动 smoke
- [ ] `./silk.sh deploy` 的端口清理、启动全链路
- [ ] `./silk.sh deploy` 的真实 Web/Android 构建 + 真实 backend +（若仍启用）真实 Weaviate 全链路一次性跑通
- [ ] `./silk.sh build-hap` / Harmony HAP 构建

## 运行备注

- Web job 不上传整个 `backend/static/`，避免把仓库已有静态文件和历史 APK 一并打包成超大 artifact。
- Android job 复用快检里的 SDK 安装方式，并显式写入 `local.properties`，避免 `silk.sh build-apk` 只在本地开发机可用。
- `build-all` job 使用 CI 内临时 `gradlew` stub，只验证脚本编排与复制/链接逻辑，避免与真实 Web / Android 构建 job 重复耗时。
- 这层 smoke 的重点是“脚本能否把装配动作串起来并把产物放到交付目录”，不是替代快检里的单测和编译门禁。
- `start` job 使用真实 backend 启动，但用本地 readiness mock 替代 Weaviate；它只验证启动/端口/健康检查/静态服务/停止清理，不验证搜索索引链路。
- `start` job 预置最小 Web 静态产物，避免把运行态 smoke 变成第二个 Web production build。
- `deploy` job 使用 Gradle/backend stub，把真实构建和真实 backend 启动拆给其它 job 覆盖；它专注验证 `deploy` 的端口清理、编排顺序、产物复制、Weaviate 复用分支和最终端口就绪。

## 下一步建议

1. 若要覆盖 `start` / `deploy`，单独准备临时端口、后台进程清理，避免把 runner 弄成状态机。
2. 若要覆盖更真实的 `deploy`，建议拆到可选或定时 workflow，复用已有构建 artifact，并在需要时配真实 Weaviate 服务，避免拖慢普通 PR。
3. Harmony 仍应保留为独立 workflow，放到具备 DevEco / hvigor / hdc 的 runner。
