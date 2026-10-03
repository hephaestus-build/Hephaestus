import { fakeBrowser } from "@webext-core/fake-browser";
import { beforeEach, describe, expect, it, vi } from "vitest";

const sidePanel = vi.hoisted(() => ({
	setOptions: vi.fn<(options: object) => Promise<void>>(),
	open: vi.fn<(options: object) => Promise<void>>(),
	getOptions: vi.fn<(options: object) => Promise<{ enabled?: boolean; path?: string }>>(),
}));

vi.mock("@wxt-dev/browser", () => ({ browser: { ...fakeBrowser, sidePanel } }));

const ID = fakeBrowser.runtime.id;
const WORK = "https://github.com/octo/app/pull/12";
const report = {
	id: ID,
	url: "chrome-extension://4a0b7c8e-5d9f-4b3e-9a61-0c1d2e3f4a5b/inline.html?open=x",
	frameId: 3,
	tab: { id: 42, url: `${WORK}/files` },
};

beforeEach(() => {
	vi.resetModules();
	fakeBrowser.reset();
	vi.clearAllMocks();
	sidePanel.setOptions.mockResolvedValue();
	sidePanel.open.mockResolvedValue();
});

describe("opening the Heph panel", () => {
	it("opens the tab's panel while the worker is still handling the press", async () => {
		const { handleMessage } = await import("~/background/worker");
		const answer = handleMessage({ type: "open-mentor" }, report);
		// Nothing has been awaited yet: Chrome opens a side panel only within the reader's press.
		expect(sidePanel.setOptions).toHaveBeenCalledWith({
			tabId: 42,
			path: "mentor.html?tab=42",
			enabled: true,
		});
		expect(sidePanel.open).toHaveBeenCalledWith({ tabId: 42 });
		await expect(answer).resolves.toMatchObject({ ok: true, data: null });
	});

	it.each([
		["a list row's preview", { ...report, url: `${report.url}&work=${encodeURIComponent(WORK)}` }],
		[
			"a tab that shows no work",
			{ ...report, tab: { id: 42, url: "https://github.com/octo/app" } },
		],
	])("refuses %s, and opens nothing", async (_label, sender) => {
		const { handleMessage } = await import("~/background/worker");
		await expect(handleMessage({ type: "open-mentor" }, sender)).resolves.toMatchObject({
			ok: false,
			error: { code: "invalid" },
		});
		expect(sidePanel.open).not.toHaveBeenCalled();
	});

	it("says so when Chrome does not open it", async () => {
		sidePanel.open.mockRejectedValue(new Error("needs a user gesture"));
		const { handleMessage } = await import("~/background/worker");
		await expect(handleMessage({ type: "open-mentor" }, report)).resolves.toMatchObject({
			ok: false,
			error: { code: "stale", message: "Chrome did not open the Heph panel. Try again." },
		});
	});

	it("refuses a panel request from a panel the worker did not configure for that tab", async () => {
		sidePanel.getOptions.mockResolvedValue({ enabled: true, path: "mentor.html?tab=7" });
		const { handleMessage } = await import("~/background/worker");
		await expect(
			handleMessage(
				{ type: "get-mentor-panel" },
				{ id: ID, url: `chrome-extension://${ID}/mentor.html?tab=42` },
			),
		).resolves.toMatchObject({ ok: false, error: { code: "forbidden" } });
	});
});
