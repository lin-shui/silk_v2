# Lint Baseline Reduction Slice 73

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 73 完成历史，记录本轮继续在 `backend` 的 `WebPageDownloader.kt` 上做的 Playwright fallback 与 URL 解析 fallback 收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `backend.xml` 从 156 条降到 154 条。
- `backend/src/main/kotlin/com/silk/backend/utils/WebPageDownloader.kt` 清掉了 `downloadWithPlaywright(...)` 上 1 条 `NestedBlockDepth` baseline。
- 同文件把 URL 解析/标题 fallback 的吞异常改成 debug 记录，清掉了 `SwallowedException` baseline。
- 本轮没有改 URL/PDF 提取合同、Playwright 优先级、HTTP fallback 路径或前端可见 payload。

## Completed Slice

1. Slice 73: 把 `downloadWithPlaywright(...)` 拆成 context/page 配置、导航/挑战处理、等待 helper 和内容构造 helper，保持 Playwright 抓取行为不变。
2. Slice 73: 把 `isWebPageUrl(...)`、`isPdfUrl(...)` 和 `extractTitleFromUrl(...)` 的 URL 解析 fallback 改成带 debug 记录的返回语义，避免吞异常。
3. Slice 73: 从 `backend.xml` 移除 `WebPageDownloader.kt` 对应的 `NestedBlockDepth` 和 `SwallowedException` baseline 各 1 条。

## Validation

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint :backend:test --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 下载器里 Playwright 分支和 URL 解析 fallback 都容易重新长回“主函数里混导航、等待、提取、回退”的形态；后续继续改这个文件时，优先往现有 helper 上扩展而不是回填大段内联分支。
