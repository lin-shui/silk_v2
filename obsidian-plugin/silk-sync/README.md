# Silk Sync

一键将 Silk 聊天记录和知识库同步到 Obsidian。

## 安装

1. 在 Obsidian 中打开「设置」→「第三方插件」→「开发者模式」
2. 将 `silk-sync` 文件夹复制到 vault 的 `.obsidian/plugins/` 目录
3. 在 Obsidian 中重新加载第三方插件列表
4. 启用 Silk Sync

## 使用

1. 首次使用前需在设置页填写：
   - **Silk 服务器地址**：如 `http://localhost:13096`
   - **API Token**：登录 Silk Web → 设置 → 外部访问，复制 API Token
   - **用户 ID**：登录 Silk Web → 设置 → 外部访问，复制用户 ID
2. 点击左侧 ribbon 中的 🔄 图标，或运行命令「Silk: 一键同步」
3. 同步后内容出现在 vault 的 `Silk/` 目录下

## 目录结构

```
Silk/
├── Chats/
│   ├── 项目讨论.md
│   └── 日常闲聊.md
└── Knowledge/
    └── <Project>/
        └── <Topic>/
            └── <标题>.md
```

## 开发

```bash
cd obsidian-plugin/silk-sync
npm install
npm run build    # 生产构建
npm run dev      # 开发模式
```
