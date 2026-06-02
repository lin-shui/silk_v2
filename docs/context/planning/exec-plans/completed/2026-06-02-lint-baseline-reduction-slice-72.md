# Lint Baseline Reduction Slice 72

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 72 完成历史，记录本轮继续在 `backend` 的 `WebPageDownloader.kt` 上做的 HTTP fallback 复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `backend.xml` 从 158 条降到 156 条。
- `backend/src/main/kotlin/com/silk/backend/utils/WebPageDownloader.kt` 清掉了 `downloadWithSimpleHttp(...)` 上 1 条 `CyclomaticComplexMethod` baseline。
- 同文件的 trust-all `X509TrustManager` 空实现改成表达式体，顺带清掉了 1 条 `EmptyFunctionBlock` baseline。
- 本轮没有改 URL/PDF 提取合同、下载策略优先级或前端可见 payload。

## Completed Slice

1. Slice 72: 把 `downloadWithSimpleHttp(...)` 拆成 SSL 配置、浏览器头连接、响应校验、压缩流读取和 HTML 解析 helper，保持 HTTP fallback 行为不变。
2. Slice 72: 从 `backend.xml` 移除 `WebPageDownloader.kt` 对应的 `CyclomaticComplexMethod` 和 `EmptyFunctionBlock` baseline 各 1 条。

## Validation

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint :backend:test --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这类下载器 fallback 逻辑同时夹着 SSL、请求头、压缩流和 HTML 解析，后续再改时继续沿 helper 扩展，比把分支重新塞回 `downloadWithSimpleHttp(...)` 更稳。
