import { App, PluginSettingTab, Setting, Plugin } from "obsidian";
import { SilkSyncSettings, DEFAULT_SETTINGS } from "./settings";
import { runSync } from "./sync";

export default class SilkSyncPlugin extends Plugin {
	settings: SilkSyncSettings;

	async onload() {
		await this.loadSettings();

		// 侧边栏图标按钮
		this.addRibbonIcon("cloud-download", "Silk 一键同步", async () => {
			await runSync(this.app, this.settings);
		});

		// 命令面板
		this.addCommand({
			id: "silk-sync",
			name: "一键同步 Silk",
			callback: async () => {
				await runSync(this.app, this.settings);
			},
		});

		// 设置页
		this.addSettingTab(new SilkSyncSettingTab(this.app, this));
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

		new Setting(containerEl)
			.setName("Silk 服务器地址")
			.setDesc("Silk 后端的地址，例如 http://localhost:8080")
			.addText((text) =>
				text
					.setPlaceholder("http://localhost:8080")
					.setValue(this.plugin.settings.serverUrl)
					.onChange(async (value) => {
						this.plugin.settings.serverUrl = value;
						await this.plugin.saveSettings();
					})
			);

		new Setting(containerEl)
			.setName("API Token")
			.setDesc("从 Silk 用户设置中获取的 API Token")
			.addText((text) => {
				text
					.setPlaceholder("输入 Token")
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
			.setDesc("你的 Silk 用户 ID")
			.addText((text) =>
				text
					.setPlaceholder("输入 userId")
					.setValue(this.plugin.settings.userId)
					.onChange(async (value) => {
						this.plugin.settings.userId = value;
						await this.plugin.saveSettings();
					})
			);
	}
}
