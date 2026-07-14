export interface SilkSyncSettings {
	serverUrl: string;
	apiToken: string;
	userId: string;
	syncDir: string;
}

export const DEFAULT_SETTINGS: SilkSyncSettings = {
	serverUrl: "http://localhost:13096",
	apiToken: "",
	userId: "",
	syncDir: "Silk",
};
