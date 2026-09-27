import { fakeBrowser } from "@webext-core/fake-browser";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import background from "~/entrypoints/background";

vi.mock("@wxt-dev/browser", () => ({ browser: fakeBrowser }));
vi.mock("~/background/storage", () => ({ restrictSessionStorage: async () => undefined }));
vi.mock("~/background/worker", () => ({
	handleMessage: async () => undefined,
	onSiteAccessAdded: async () => undefined,
	onSiteAccessRemoved: async () => undefined,
	reconcile: async () => undefined,
}));

beforeEach(() => {
	fakeBrowser.reset();
	vi.spyOn(fakeBrowser.permissions.onAdded, "addListener").mockReturnValue(undefined);
	vi.spyOn(fakeBrowser.permissions.onRemoved, "addListener").mockReturnValue(undefined);
});

afterEach(() => {
	vi.restoreAllMocks();
});

describe("settings entry points", () => {
	it("opens settings only for a first installation or a toolbar click", async () => {
		const open = vi.spyOn(fakeBrowser.runtime, "openOptionsPage").mockResolvedValue();
		background.main();
		expect(open).not.toHaveBeenCalled();
		await fakeBrowser.runtime.onInstalled.trigger({ reason: "update", previousVersion: "0.1.0" });
		await fakeBrowser.runtime.onInstalled.trigger({ reason: "chrome_update" });
		expect(open).not.toHaveBeenCalled();
		await fakeBrowser.runtime.onInstalled.trigger({ reason: "install" });
		expect(open).toHaveBeenCalledOnce();
		await fakeBrowser.action.onClicked.trigger(
			await fakeBrowser.tabs.create({ url: "https://github.com" }),
		);
		expect(open).toHaveBeenCalledTimes(2);
	});
});
