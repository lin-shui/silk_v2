import { App, Notice, PluginSettingTab, Setting, Plugin } from "obsidian";
import { SilkSyncSettings, DEFAULT_SETTINGS } from "./settings";
import { runSync } from "./sync";

export default class SilkSyncPlugin extends Plugin {
	settings: SilkSyncSettings;

	async onload() {
		await this.loadSettings();

		// 侧边栏图标按钮 — 使用 sync 图标，直观表示同步操作
		this.addRibbonIcon("sync", "Silk: 一键同步聊天记录和知识库到 Obsidian", async () => {
			if (!this.settings.apiToken || !this.settings.userId) {
				new Notice("⚠️ 请先在 Silk Sync 设置页中填写 API Token 和用户 ID");
				return;
			}
			await runSync(this.app, this.settings);
		});

		// 命令面板
		this.addCommand({
			id: "silk-sync",
			name: "一键同步 Silk 聊天记录与知识库",
			callback: async () => {
				if (!this.settings.apiToken || !this.settings.userId) {
					new Notice("⚠️ 请先在 Silk Sync 设置页中填写 API Token 和用户 ID");
					return;
				}
				await runSync(this.app, this.settings);
			},
		});

		// 设置页
		this.addSettingTab(new SilkSyncSettingTab(this.app, this));

		// 初次启用时提醒配置
		if (!this.settings.apiToken || !this.settings.userId) {
			new Notice("🔑 Silk Sync: 请进入设置页配置 API Token 和用户 ID");
		}
	}

	async loadSettings() {
		this.settings = Object.assign(
			{},
			DEFAULT_SETTINGS,
			await this.loadData()
		);
	}

	async saveSettings() {
		await this.saveData(this.settings);
	}
}

class SilkSyncSettingTab extends PluginSettingTab {
	plugin: SilkSyncPlugin;

	constructor(app: App, plugin: SilkSyncPlugin) {
		super(app, plugin);
		this.plugin = plugin;
	}

	display(): void {
		const { containerEl } = this;
		containerEl.empty();

		containerEl.createEl("h2", { text: "Silk Sync 设置" });

		// 使用说明
		const helpDiv = containerEl.createDiv({ cls: "silk-sync-help" });
		helpDiv.style.cssText = "margin-bottom: 16px; padding: 12px; background: #f0f7ff; border-radius: 8px; font-size: 13px; line-height: 1.6; color: #666;";
		helpDiv.innerHTML = `
			<strong>🔑 配置说明</strong><br>
			1. 登录 Silk Web 应用，进入「设置」→「外部访问」<br>
			2. 复制你的 <strong>用户 ID</strong> 和 <strong>API Token</strong> 填入下方<br>
			3. 点击侧边栏 <strong>🔄 图标</strong> 或运行命令「Silk: 一键同步」开始同步
		`;

		new Setting(containerEl)
			.setName("Silk 服务器地址")
			.setDesc("Silk 后端的地址（查看 .env 中 BACKEND_BASE_URL 或 BACKEND_HOST:端口）")
			.addText((text) =>
				text
					.setPlaceholder("http://localhost:13096")
					.setValue(this.plugin.settings.serverUrl)
					.onChange(async (value) => {
						this.plugin.settings.serverUrl = value;
						await this.plugin.saveSettings();
					})
			);

		new Setting(containerEl)
			.setName("API Token")
			.setDesc("从 Silk Web 设置 → 外部访问 中复制 API Token")
			.addText((text) => {
				text
					.setPlaceholder("输入 API Token")
					.setValue(this.plugin.settings.apiToken)
					.onChange(async (value) => {
						this.plugin.settings.apiToken = value;
						await this.plugin.saveSettings();
					});
				// 密码模式
				text.inputEl.type = "password";
			});

		new Setting(containerEl)
			.setName("用户 ID")
			.setDesc("从 Silk Web 设置 → 外部访问 中复制用户 ID")
			.addText((text) =>
				text
					.setPlaceholder("输入用户 ID（如 user_xxx）")
					.setValue(this.plugin.settings.userId)
					.onChange(async (value) => {
						this.plugin.settings.userId = value;
						await this.plugin.saveSettings();
					})
			);

		// 测试连接按钮
		new Setting(containerEl)
			.setName("测试连接")
			.setDesc("检查配置是否正确，能否连上 Silk 后端")
			.addButton((button) =>
				button.setButtonText("测试").setCta().onClick(async () => {
					button.setDisabled(true);
					button.setButtonText("测试中...");
					try {
						const url = `${this.plugin.settings.serverUrl.replace(/\/+$/, "")}/api/obsidian/sync?userId=${encodeURIComponent(this.plugin.settings.userId)}`;
						const resp = await fetch(url, {
							headers: {
								Authorization: `Bearer ${this.plugin.settings.apiToken}`,
							},
						});
						if (resp.ok) {
							new Notice("✅ Silk 连接正常");
						} else {
							new Notice(`❌ 连接失败: HTTP ${resp.status}`);
						}
					} catch (e) {
						new Notice(`❌ 连接失败: ${e.message}`);
					} finally {
						button.setDisabled(false);
						button.setButtonText("测试");
					}
				})
			);
	}
}
