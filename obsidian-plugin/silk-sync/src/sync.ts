import { App, Notice, TFile, TFolder } from "obsidian";
import { SilkSyncSettings } from "./settings";

/** 对应后端 /api/obsidian/sync 的返回结构 */
interface ObsidianSyncResponse {
	success: boolean;
	syncedAt: number;
	chats: ObsidianChatExport[];
	kbEntries: ObsidianKbExport[];
}

interface ObsidianChatExport {
	groupId: string;
	groupName: string;
	updatedAt: number;
	messageCount: number;
	vaultPath: string;
	markdown: string;
}

interface ObsidianKbExport {
	entryId: string;
	title: string;
	topicName: string;
	project: string;
	updatedAt: number;
	vaultPath: string;
	markdown: string;
}

/**
 * 一键同步：调 API → 写 vault
 */
export async function runSync(app: App, settings: SilkSyncSettings) {
	if (!settings.serverUrl || !settings.apiToken || !settings.userId) {
		new Notice("❌ 请先在设置中填写 Silk 服务器地址、Token 和用户 ID");
		return;
	}

	new Notice("🔄 Silk 同步中...");

	try {
		const url = `${settings.serverUrl.replace(/\/+$/, "")}/api/obsidian/sync?userId=${encodeURIComponent(settings.userId)}`;
		const resp = await fetch(url, {
			headers: {
				Authorization: `Bearer ${settings.apiToken}`,
			},
		});

		if (!resp.ok) {
			const text = await resp.text();
			new Notice(`❌ 同步失败: HTTP ${resp.status}`);
			console.error("Silk sync error:", text);
			return;
		}

		const data: ObsidianSyncResponse = await resp.json();
		if (!data.success) {
			new Notice("❌ 同步失败: 服务器返回错误");
			return;
		}

		// 统计
		let chatCount = 0;
		let kbCount = 0;

		// 写入聊天记录（替换默认 Silk/ 前缀为用户配置的目录）
		for (const chat of data.chats) {
			const path = rebasePath(chat.vaultPath, settings.syncDir);
			await writeFile(app, path, chat.markdown);
			chatCount++;
		}

		// 写入 KB 条目
		for (const entry of data.kbEntries) {
			const path = rebasePath(entry.vaultPath, settings.syncDir);
			await writeFile(app, path, entry.markdown);
			kbCount++;
		}

		new Notice(`✅ 同步完成: ${chatCount} 个群聊, ${kbCount} 条 KB 条目`);
	} catch (e) {
		new Notice(`❌ 同步失败: ${e.message}`);
		console.error("Silk sync error:", e);
	}
}

/**
 * 将 vaultPath 中的默认 "Silk/" 前缀替换为用户自定义目录。
 * 例如: "Silk/Chats/demo.md" + syncDir="我的笔记" → "我的笔记/Chats/demo.md"
 */
function rebasePath(vaultPath: string, syncDir: string): string {
	const dir = syncDir.replace(/^\/+|\/+$/g, "").trim() || "Silk";
	return vaultPath.replace(/^Silk\/?/, dir + "/");
}

/**
 * 确保路径的目录存在，然后写入/覆盖文件
 */
async function writeFile(app: App, vaultPath: string, content: string) {
	// 确保文件所在的目录树存在
	const parts = vaultPath.split("/");
	const fileName = parts.pop()!;

	let currentPath = "";
	for (const part of parts) {
		currentPath = currentPath ? `${currentPath}/${part}` : part;
		const folder = app.vault.getAbstractFileByPath(currentPath);
		if (!folder) {
			await app.vault.createFolder(currentPath);
		}
	}

	// 写入/覆盖文件
	const existing = app.vault.getAbstractFileByPath(vaultPath);
	if (existing instanceof TFile) {
		await app.vault.modify(existing, content);
	} else {
		await app.vault.create(vaultPath, content);
	}
}
