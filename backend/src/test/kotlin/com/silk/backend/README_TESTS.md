# Silk 后端自动化测试框架

## 概述

本目录包含 Silk 后端的自动化测试用例，用于验证核心 API 的正确性。

当前测试分为两类：

| 文件 | 描述 |
|------|------|
| `ApplicationTest.kt` | 基础 smoke test，确认测试框架可运行 |
| `BackendApiContractTest.kt` | 真实 API contract tests，覆盖认证、群组、联系人、消息发送/撤回、未读数 |

旧的字符串级测试 (`MessageRecallTest.kt` / `MessageForwardTest.kt` / `MessageCopyTest.kt`) 仍然保留，但它们不构成主要回归门禁。

## 运行测试

### 使用 Gradle

```bash
./gradlew :backend:test
```

测试会自动为每个用例创建独立的临时数据库和临时 `chat_history` 目录，不会污染仓库根目录。

## 添加新测试

1. 优先为真实 HTTP 路由添加 contract test，而不是只测字符串或 JSON 片段。
2. 使用 `testApplication {}` 启动 Ktor 应用。
3. 使用 `BackendContractTestBase`，让测试自动获得隔离的数据库和文件目录。
4. 修复 bug 时，优先补 regression test。

### 测试命名规范

- 测试类: `{功能名}Test.kt`
- 测试方法: `test{具体测试场景}`

### 示例

```kotlin
package com.silk.backend

import org.junit.Test
import kotlin.test.assertTrue

class ExampleTest {
    @Test
    fun testExample() {
        assertTrue(true, "示例测试应该通过")
    }
}
```

## 测试原则

1. **独立性**: 每个测试应该独立运行，不依赖其他测试
2. **可重复性**: 测试结果应该可重复
3. **自验证**: 测试应该自动判断成功或失败
4. **及时性**: 测试应该快速执行

## 持续集成

GitHub Actions workflow: `.github/workflows/backend-contract.yml`

当前 fast gate 只跑：
- `./gradlew :backend:test`

后续可以继续扩展：
- Web smoke / Playwright
- `frontend/shared` 纯逻辑测试
- 夜间运行的 Weaviate / Android / `silk.sh` smoke
