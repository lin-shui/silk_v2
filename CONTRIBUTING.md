# Contributing to Silk

感谢你对 Silk 的关注与贡献。为方便协作和代码审核，我们采用 **develop / master** 双分支流程。

## 分支策略

| 分支 | 用途 |
|------|------|
| **master** | 稳定发布分支，仅由维护者从 `develop` 合并。 |
| **develop** | 协作开发分支，所有社区贡献通过 PR 合并到这里。 |

- 新功能、修复、文档改进等，请 **向 `develop` 提 Pull Request**。
- 维护者会审核 PR，合并到 `develop`；稳定后再将 `develop` 合并到 `master` 发布。

## 如何贡献（Fork + PR）

你无法直接向本仓库 push，请按以下步骤操作：

1. **Fork** 本仓库到你的 GitHub 账号下。
2. **克隆你的 fork**，并添加上游远程（可选，便于同步）：
   ```bash
   git clone https://github.com/你的用户名/silk.git
   cd silk
   git remote add upstream https://github.com/原仓库/silk.git
   ```
3. **从 develop 拉取最新代码**（若仓库已有 develop）并创建功能分支：
   ```bash
   git fetch upstream
   git checkout -b feature/你的功能名 upstream/develop
   ```
   若上游暂无 develop，可从 master 创建：
   ```bash
   git checkout -b feature/你的功能名 upstream/master
   ```
4. **开发、提交**：
   ```bash
   # 修改代码后
   git add .
   git commit -m "简短描述你的改动"
   git push origin feature/你的功能名
   ```
5. **在 GitHub 上对本仓库发起 Pull Request**：
   - **Base 分支选择 `develop`**（若存在），否则选 `master`。
   - 标题和描述写清楚改动内容与原因。
6. 维护者审核后合并到 `develop`；合适时会从 `develop` 合并到 `master`。

## 对维护者：首次启用 develop

若仓库目前只有 `master`，可先创建并推送 `develop`：

```bash
git checkout -b develop
git push -u origin develop
```

之后在 GitHub 仓库设置中可将 **默认分支** 设为 `develop`，这样新 PR 会默认指向 `develop`。

## 小结

- **贡献者**：Fork → 在 fork 里开分支 → **PR 到本仓库的 `develop`**（不是直接 push）。
- **维护者**：审核 PR → 合并到 `develop` → 定期将 `develop` 合并到 `master` 发布。

如有问题，欢迎提 Issue 讨论。
