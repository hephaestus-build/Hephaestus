import { beforeEach, describe, expect, it, vi } from "vitest";

import { refresh } from "@/api/sdk.gen";

import { refreshAccessToken } from "./session-refresh";

vi.mock("@/api/sdk.gen", () => ({ refresh: vi.fn() }));

const refreshMock = vi.mocked(refresh);

const rotated = {
	data: undefined,
	error: undefined,
	request: new Request("http://localhost/auth/refresh"),
	response: new Response(null, { status: 204 }),
};

describe("refreshAccessToken", () => {
	beforeEach(() => {
		refreshMock.mockReset();
	});

	it("collapses callers that overlap a rotation onto one POST /auth/refresh", async () => {
		let settle: (() => void) | undefined;
		refreshMock.mockReturnValue(
			new Promise<typeof rotated>((resolve) => {
				settle = () => resolve(rotated);
			}),
		);

		const overlapping = Promise.all([
			refreshAccessToken(),
			refreshAccessToken(),
			refreshAccessToken(),
		]);
		settle?.();

		expect(await overlapping).toStrictEqual(["refreshed", "refreshed", "refreshed"]);
		expect(refreshMock).toHaveBeenCalledTimes(1);
	});

	it("rotates again once the previous rotation has settled", async () => {
		refreshMock.mockResolvedValue(rotated);

		await refreshAccessToken();
		await refreshAccessToken();

		expect(refreshMock).toHaveBeenCalledTimes(2);
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
