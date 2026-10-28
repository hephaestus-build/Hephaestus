import { locks } from "node:worker_threads";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { deferred } from "@/test/async";

const { refreshMock } = vi.hoisted(() => ({ refreshMock: vi.fn() }));
vi.mock("@/api/sdk.gen", () => ({ refresh: refreshMock }));

const rotated = { response: new Response(null, { status: 204 }) };

// Separate module instances model the separate JavaScript heaps of browser tabs.
async function tabs() {
	vi.resetModules();
	const first = await import("./session-refresh");
	vi.resetModules();
	const second = await import("./session-refresh");
	return [first, second] as const;
}

describe("refresh across tabs", () => {
	beforeEach(() => {
		window.localStorage.clear();
		refreshMock.mockReset();
	});
	afterEach(() => vi.unstubAllGlobals());

	it.each([
		["Web Locks", locks],
		["IndexedDB fallback", undefined],
	])("runs one refresh with %s even while its response is delayed", async (_name, lockManager) => {
		vi.stubGlobal("navigator", { locks: lockManager });
		const [first, second] = await tabs();
		const response = deferred<typeof rotated>();
		refreshMock.mockReturnValue(response.promise);

		const a = first.refreshAccessToken();
		await vi.waitFor(() => expect(refreshMock).toHaveBeenCalledOnce());
		const b = second.refreshAccessToken();
		response.resolve(rotated);
		await expect(Promise.all([a, b])).resolves.toStrictEqual(["refreshed", "refreshed"]);

		expect(refreshMock).toHaveBeenCalledOnce();
	});
	it("releases the fallback lock after a failed refresh", async () => {
		vi.stubGlobal("navigator", { locks: undefined });
		const [first, second] = await tabs();
		refreshMock.mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce(rotated);

		await expect(first.refreshAccessToken()).resolves.toBe("unavailable");
		await expect(second.refreshAccessToken()).resolves.toBe("refreshed");

		expect(refreshMock).toHaveBeenCalledTimes(2);
	});

	it("preserves the session without sending an unlocked refresh when storage locking is unavailable", async () => {
		vi.stubGlobal("navigator", { locks: undefined });
		vi.stubGlobal("indexedDB", undefined);
		const [first] = await tabs();

		await expect(first.refreshAccessToken()).resolves.toBe("unavailable");

		expect(refreshMock).not.toHaveBeenCalled();
	});
});
