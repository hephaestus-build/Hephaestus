import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { refresh } from "@/api/sdk.gen";
import { deferred } from "@/test/async";

import {
	SESSION_COOLDOWN_MS,
	refreshAccessToken,
	sessionConfirmedRecently,
} from "./session-refresh";

vi.mock("@/api/sdk.gen", () => ({ refresh: vi.fn() }));

const refreshMock = vi.mocked(refresh);
const NOW = Date.parse("2026-10-28T05:17:00Z");

const rotated = {
	data: undefined,
	error: undefined,
	request: new Request("http://localhost/auth/refresh"),
	response: new Response(null, { status: 204 }),
};

describe("refreshAccessToken", () => {
	beforeEach(() => {
		refreshMock.mockReset();
		window.localStorage.clear();
		vi.useFakeTimers({ toFake: ["Date"] });
		vi.setSystemTime(NOW);
	});

	afterEach(() => vi.useRealTimers());

	it("starts the cooldown that every tab shares only when a renewal succeeds", async () => {
		refreshMock.mockResolvedValueOnce({
			...rotated,
			response: new Response(null, { status: 401 }),
		});
		await refreshAccessToken();
		expect(sessionConfirmedRecently()).toBe(false);

		refreshMock.mockResolvedValueOnce(rotated);
		await refreshAccessToken();
		expect(sessionConfirmedRecently()).toBe(true);
		expect(window.localStorage.getItem("hephaestus:session-confirmed-at")).not.toBeNull();
	});

	it("collapses callers that overlap a rotation onto one POST /auth/refresh", async () => {
		const rotation = deferred<typeof rotated>();
		refreshMock.mockReturnValue(rotation.promise);

		const overlapping = Promise.all([
			refreshAccessToken(),
			refreshAccessToken(),
			refreshAccessToken(),
		]);
		rotation.resolve(rotated);

		await expect(overlapping).resolves.toStrictEqual(["refreshed", "refreshed", "refreshed"]);
		expect(refreshMock).toHaveBeenCalledOnce();
	});

	it("shares the cooldown after a rotation has settled", async () => {
		refreshMock.mockResolvedValue(rotated);

		await refreshAccessToken();
		await refreshAccessToken();

		expect(refreshMock).toHaveBeenCalledOnce();

		vi.setSystemTime(NOW + SESSION_COOLDOWN_MS);
		await refreshAccessToken();
		expect(refreshMock).toHaveBeenCalledTimes(2);
	});

	it("uses the renewal confirmed by another tab without rotating its cookie again", async () => {
		window.localStorage.setItem("hephaestus:session-confirmed-at", String(NOW));

		await expect(refreshAccessToken()).resolves.toBe("refreshed");

		expect(refreshMock).not.toHaveBeenCalled();
	});

	it("reports unavailable rather than throwing when the rotation fails", async () => {
		refreshMock.mockRejectedValue(new Error("offline"));

		await expect(refreshAccessToken()).resolves.toBe("unavailable");
	});
	it("reports unavailable when the generated client returns a transport error without a response", async () => {
		refreshMock.mockResolvedValue({ data: undefined, error: new Error("offline") });
		await expect(refreshAccessToken()).resolves.toBe("unavailable");
	});

	it.each([
		[401, "expired"],
		[403, "unavailable"],
		[429, "unavailable"],
		[500, "unavailable"],
		[503, "unavailable"],
	] as const)(
		"classifies HTTP %i without confusing outages with expiry",
		async (status, expected) => {
			refreshMock.mockResolvedValue({ ...rotated, response: new Response(null, { status }) });
			await expect(refreshAccessToken()).resolves.toBe(expected);
		},
	);
});
