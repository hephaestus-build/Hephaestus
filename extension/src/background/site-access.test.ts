import { beforeEach, describe, expect, it, vi } from "vitest";

import {
	desiredMatches,
	reconcileProviderScript,
	siteAccessEntries,
} from "~/background/site-access";

const browser = vi.hoisted(() => ({
	permissions: { getAll: vi.fn<() => Promise<{ origins: string[] }>>() },
	scripting: {
		getRegisteredContentScripts:
			vi.fn<() => Promise<{ id: string; matches: string[]; js: string[] }[]>>(),
		registerContentScripts: vi.fn<(entries: unknown) => Promise<void>>(),
		updateContentScripts: vi.fn<(entries: unknown) => Promise<void>>(),
		unregisterContentScripts: vi.fn<(filter: unknown) => Promise<void>>(),
		executeScript:
			vi.fn<(details: unknown) => Promise<{ documentId: string; frameId: number }[]>>(),
	},
	tabs: {
		query: vi.fn<() => Promise<{ id: number; url?: string }[]>>(),
		sendMessage:
			vi.fn<(tabId: number, message: unknown, options?: { documentId: string }) => Promise<void>>(),
	},
}));
vi.mock("@wxt-dev/browser", () => ({ browser }));

beforeEach(() => {
	vi.resetAllMocks();
	vi.mocked(browser.permissions.getAll).mockResolvedValue({ origins: [] });
	vi.mocked(browser.scripting.getRegisteredContentScripts).mockResolvedValue([]);
	vi.mocked(browser.tabs.query).mockResolvedValue([]);
	browser.scripting.executeScript.mockResolvedValue([]);
});

/** What Chrome lists as registered: the provider script, while it is. */
function registeredScripts(registered: boolean) {
	return registered
		? [
				{
					id: "hephaestus-provider",
					matches: ["https://github.com/*"],
					js: ["content-scripts/provider.js"],
				},
			]
		: [];
}

describe("reconcileProviderScript", () => {
	it("injects a new grant into already-open top-level documents without widening access", async () => {
		vi.mocked(browser.permissions.getAll).mockResolvedValue({ origins: ["https://github.com/*"] });
		vi.mocked(browser.tabs.query).mockResolvedValue([
			{
				id: 4,
				url: "https://github.com/org/repo/pull/1",
			},
			{
				id: 5,
				url: "https://unrelated.test/",
			},
		]);
		await reconcileProviderScript();
		expect(browser.scripting.registerContentScripts).toHaveBeenCalledWith([
			expect.objectContaining({ matches: ["https://github.com/*"], allFrames: false }),
		]);
		expect(browser.scripting.executeScript).toHaveBeenCalledExactlyOnceWith({
			target: { tabId: 4, frameIds: [0] },
			files: ["content-scripts/provider.js"],
			world: "ISOLATED",
		});
	});

	it("tears down an existing document whose revoked URL Chrome no longer exposes", async () => {
		vi.mocked(browser.scripting.getRegisteredContentScripts).mockResolvedValue([
			{
				id: "hephaestus-provider",
				matches: ["https://github.com/*"],
				js: ["content-scripts/provider.js"],
			},
		]);
		vi.mocked(browser.tabs.query).mockResolvedValue([{ id: 4 }]);
		await reconcileProviderScript();
		expect(browser.scripting.unregisterContentScripts).toHaveBeenCalledWith({
			ids: ["hephaestus-provider"],
		});
		expect(browser.tabs.sendMessage).toHaveBeenCalledWith(4, {
			type: "hephaestus:provider-access",
			enabled: false,
		});
		expect(browser.scripting.executeScript).not.toHaveBeenCalled();
	});

	it("does not let overlapping startup and revocation leave a revoked registration behind", async () => {
		let origins = ["https://github.com/*"];
		let registered = false;
		const entered = Promise.withResolvers<undefined>();
		const release = Promise.withResolvers<undefined>();
		browser.permissions.getAll.mockImplementation(async () => ({ origins }));
		browser.scripting.getRegisteredContentScripts.mockImplementation(async () =>
			registeredScripts(registered),
		);
		browser.scripting.registerContentScripts.mockImplementation(async () => {
			entered.resolve(undefined);
			await release.promise;
			registered = true;
		});
		browser.scripting.unregisterContentScripts.mockImplementation(async () => {
			registered = false;
		});
		const starting = reconcileProviderScript();
		await entered.promise;
		origins = [];
		const removing = reconcileProviderScript();
		release.resolve(undefined);
		await Promise.all([starting, removing]);
		expect(registered).toBe(false);
	});

	it("commits revoke and regrant even when every tab notification never responds", async () => {
		let origins = ["https://github.com/*"];
		let registered = false;
		browser.permissions.getAll.mockImplementation(async () => ({ origins }));
		browser.scripting.getRegisteredContentScripts.mockImplementation(async () =>
			registeredScripts(registered),
		);
		browser.scripting.registerContentScripts.mockImplementation(async () => {
			registered = true;
		});
		browser.scripting.unregisterContentScripts.mockImplementation(async () => {
			registered = false;
		});
		browser.tabs.query.mockResolvedValue([{ id: 4, url: "https://github.com/org/repo/pull/1" }]);
		const unanswered = Promise.withResolvers<undefined>();
		browser.tabs.sendMessage.mockImplementation(async () => unanswered.promise);
		await reconcileProviderScript();
		expect(registered).toBe(true);
		expect(browser.scripting.executeScript).toHaveBeenCalledOnce();
		origins = [];
		await reconcileProviderScript();
		expect(registered).toBe(false);
		expect(browser.tabs.sendMessage).toHaveBeenCalledWith(4, {
			type: "hephaestus:provider-access",
			enabled: false,
		});
		origins = ["https://github.com/*"];
		await reconcileProviderScript();
		expect(registered).toBe(true);
		expect(browser.scripting.executeScript).toHaveBeenCalledTimes(2);
		expect(browser.tabs.sendMessage.mock.calls.map((call) => call[1])).toStrictEqual([
			{ type: "hephaestus:provider-access", enabled: true },
			{ type: "hephaestus:provider-access", enabled: false },
			{ type: "hephaestus:provider-access", enabled: true },
		]);
	});

	it("ignores a stale tab inventory without losing the initial injection on a superseding pass", async () => {
		browser.permissions.getAll.mockResolvedValue({ origins: ["https://github.com/*"] });
		const inventory = Promise.withResolvers<{ id: number; url?: string }[]>();
		browser.tabs.query.mockReturnValueOnce(inventory.promise);
		await reconcileProviderScript();
		browser.scripting.getRegisteredContentScripts.mockResolvedValue([
			{
				id: "hephaestus-provider",
				matches: ["https://github.com/*"],
				js: ["content-scripts/provider.js"],
			},
		]);
		browser.tabs.query.mockResolvedValue([{ id: 4, url: "https://github.com/org/repo/pull/1" }]);
		await reconcileProviderScript();
		expect(browser.scripting.executeScript).toHaveBeenCalledOnce();
		inventory.resolve([{ id: 7, url: "https://github.com/org/repo/pull/2" }]);
		await inventory.promise;
		expect(browser.scripting.executeScript).toHaveBeenCalledOnce();
		expect(browser.tabs.sendMessage).not.toHaveBeenCalledWith(7, expect.anything());
	});

	it("does not inject or send an enabled message from a grant inventory completed after revocation", async () => {
		browser.permissions.getAll.mockResolvedValue({ origins: ["https://github.com/*"] });
		const inventory = Promise.withResolvers<{ id: number; url?: string }[]>();
		browser.tabs.query.mockReturnValueOnce(inventory.promise);
		await reconcileProviderScript();
		browser.permissions.getAll.mockResolvedValue({ origins: [] });
		browser.scripting.getRegisteredContentScripts.mockResolvedValue([
			{
				id: "hephaestus-provider",
				matches: ["https://github.com/*"],
				js: ["content-scripts/provider.js"],
			},
		]);
		browser.tabs.query.mockResolvedValue([{ id: 4 }]);
		await reconcileProviderScript();
		inventory.resolve([{ id: 4, url: "https://github.com/org/repo/pull/1" }]);
		await inventory.promise;
		expect(browser.scripting.executeScript).not.toHaveBeenCalled();
		expect(browser.tabs.sendMessage.mock.calls).toStrictEqual([
			[4, { type: "hephaestus:provider-access", enabled: false }],
		]);
	});

	it("tears down the exact late-injected document after exclusion without blocking registration", async () => {
		browser.permissions.getAll.mockResolvedValue({ origins: ["https://github.com/*"] });
		browser.tabs.query.mockResolvedValue([{ id: 4, url: "https://github.com/org/repo/pull/1" }]);
		const injected = Promise.withResolvers<{ documentId: string; frameId: number }[]>();
		browser.scripting.executeScript.mockReturnValueOnce(injected.promise);
		await reconcileProviderScript();
		browser.scripting.getRegisteredContentScripts.mockResolvedValue([
			{
				id: "hephaestus-provider",
				matches: ["https://github.com/*"],
				js: ["content-scripts/provider.js"],
			},
		]);
		// This origin remains granted for the configured instance, but must no longer host provider UI.
		await reconcileProviderScript("https://github.com");
		expect(browser.scripting.unregisterContentScripts).toHaveBeenCalledOnce();
		injected.resolve([{ documentId: "old-document", frameId: 0 }]);
		await vi.waitFor(() =>
			expect(browser.tabs.sendMessage).toHaveBeenCalledWith(
				4,
				{ type: "hephaestus:provider-access", enabled: false },
				{ documentId: "old-document" },
			),
		);
	});

	it("does not reinject unchanged grants when the worker wakes", async () => {
		vi.mocked(browser.permissions.getAll).mockResolvedValue({ origins: ["https://github.com/*"] });
		vi.mocked(browser.scripting.getRegisteredContentScripts).mockResolvedValue([
			{
				id: "hephaestus-provider",
				matches: ["https://github.com/*"],
				js: ["content-scripts/provider.js"],
			},
		]);
		vi.mocked(browser.tabs.query).mockResolvedValue([
			{
				id: 4,
				url: "https://github.com/org/repo/pull/1",
			},
		]);
		await reconcileProviderScript();
		expect(browser.tabs.sendMessage).toHaveBeenCalledWith(4, {
			type: "hephaestus:provider-access",
			enabled: true,
		});
		expect(browser.scripting.executeScript).not.toHaveBeenCalled();
	});
});

describe("desiredMatches", () => {
	it("runs on every concrete granted origin except the instance", () => {
		expect(
			desiredMatches(
				[
					"https://gitlab.example.test/*",
					"https://heph.example.test/*",
					"https://github.com/*",
					"https://github.com/*",
				],
				"https://heph.example.test",
			),
		).toStrictEqual(["https://github.com/*", "https://gitlab.example.test/*"]);
	});

	it("never treats a wildcard grant as consent to run everywhere", () => {
		expect(desiredMatches(["https://*/*", "<all_urls>"], undefined)).toStrictEqual([]);
	});

	it("is empty once the last grant is gone", () => {
		expect(desiredMatches([], "https://heph.example.test")).toStrictEqual([]);
	});
});

describe("siteAccessEntries", () => {
	it("groups workspaces by the site they are connected to", () => {
		expect(
			siteAccessEntries(
				[
					{
						slug: "a",
						displayName: "A",
						providerType: "GITLAB",
						siteOrigin: "https://gitlab.lrz.de",
					},
					{
						slug: "b",
						displayName: "B",
						providerType: "GITLAB",
						siteOrigin: "https://gitlab.lrz.de",
					},
					{ slug: "c", displayName: "C", providerType: "GITHUB", siteOrigin: "https://github.com" },
				],
				["https://github.com/*"],
			),
		).toStrictEqual([
			{ origin: "https://github.com", providerType: "GITHUB", workspaces: ["C"], granted: true },
			{
				origin: "https://gitlab.lrz.de",
				providerType: "GITLAB",
				workspaces: ["A", "B"],
				granted: false,
			},
		]);
	});
});
