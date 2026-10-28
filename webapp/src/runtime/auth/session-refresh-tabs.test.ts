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
	afterEach(() => {
		vi.useRealTimers();
		vi.unstubAllGlobals();
	});

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
	it.each([
		["Web Locks", locks],
		["IndexedDB", undefined],
	])(
		"releases %s after a failed refresh without retrying the request",
		async (_name, lockManager) => {
			vi.stubGlobal("navigator", { locks: lockManager });
			const [first, second] = await tabs();
			refreshMock.mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce(rotated);

			await expect(first.refreshAccessToken()).resolves.toBe("unavailable");
			await expect(second.refreshAccessToken()).resolves.toBe("refreshed");

			expect(refreshMock).toHaveBeenCalledTimes(2);
		},
	);

	it("renews without a lock when neither browser locking API is available", async () => {
		vi.stubGlobal("navigator", { locks: undefined });
		vi.stubGlobal("indexedDB", undefined);
		const [first] = await tabs();
		refreshMock.mockResolvedValue(rotated);

		await expect(first.refreshAccessToken()).resolves.toBe("refreshed");

		expect(refreshMock).toHaveBeenCalledOnce();
	});
	it("renews when IndexedDB exists but the browser refuses storage access", async () => {
		vi.stubGlobal("navigator", { locks: undefined });
		vi.stubGlobal("indexedDB", {
			open: () => {
				throw new DOMException("Storage disabled", "SecurityError");
			},
		});
		const [first] = await tabs();
		refreshMock.mockResolvedValue(rotated);

		await expect(first.refreshAccessToken()).resolves.toBe("refreshed");
		expect(refreshMock).toHaveBeenCalledOnce();
	});

	it("renews when Web Locks rejects acquisition and IndexedDB is unavailable", async () => {
		vi.stubGlobal("navigator", {
			locks: {
				request: vi.fn().mockRejectedValue(new DOMException("Lock disabled", "SecurityError")),
			},
		});
		vi.stubGlobal("indexedDB", undefined);
		const [first] = await tabs();
		refreshMock.mockResolvedValue(rotated);

		await expect(first.refreshAccessToken()).resolves.toBe("refreshed");
		expect(refreshMock).toHaveBeenCalledOnce();
	});

	it("lets another tab renew after a stalled IndexedDB lock reaches its deadline", async () => {
		vi.stubGlobal("navigator", { locks: undefined });
		vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date", "performance"] });
		const [first, second] = await tabs();
		const stalled = deferred<typeof rotated>();
		refreshMock.mockReturnValueOnce(stalled.promise).mockResolvedValueOnce(rotated);
		const a = first.refreshAccessToken();
		await vi.waitFor(() => expect(refreshMock).toHaveBeenCalledOnce());
		const b = second.refreshAccessToken();
		try {
			await vi.advanceTimersByTimeAsync(6000);
			await vi.waitFor(() => expect(refreshMock).toHaveBeenCalledTimes(2));
			await expect(b).resolves.toBe("refreshed");
		} finally {
			stalled.resolve(rotated);
			await a;
		}
	});
});
