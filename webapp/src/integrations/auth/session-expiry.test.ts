import { QueryClient } from "@tanstack/react-query";
import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";

import { __resetSessionRecoveryForTests, handlePossibleSessionExpiry } from "./session-expiry";
import { refreshAccessToken } from "./session-refresh";
vi.mock("./session-refresh", () => ({ refreshAccessToken: vi.fn() }));
const refreshMock = vi.mocked(refreshAccessToken);
vi.mock("@/environment", () => ({ default: { serverUrl: "http://localhost/api" } }));
function stubLocation(pathname: string, search = ""): { assigned: string[] } {
	const assigned: string[] = [];
	const stub = {
		assign: (url: string) => assigned.push(url),
		pathname,
		search,
		origin: "http://localhost",
	};
	Object.defineProperty(window, "location", { configurable: true, value: stub });
	return { assigned };
}

const realLocation = window.location;
beforeEach(() => {
	refreshMock.mockReset();
	__resetSessionRecoveryForTests();
});
afterEach(() => {
	Object.defineProperty(window, "location", { configurable: true, value: realLocation });
	vi.restoreAllMocks();
});

function makeQueryClient(): QueryClient {
	return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function res(status: number, url: string): Response {
	const r = new Response(null, { status });
	Object.defineProperty(r, "url", { value: url, configurable: true });
	return r;
}
const flush = () =>
	new Promise((resolve) => {
		setTimeout(resolve, 0);
	});

describe("handlePossibleSessionExpiry", () => {
	it("recovers a mid-session 401 via a silent refresh — no redirect", async () => {
		refreshMock.mockResolvedValue("refreshed");
		const { assigned } = stubLocation("/w/acme/overview", "?tab=prs");
		const qc = makeQueryClient();
		const invalidate = vi.spyOn(qc, "invalidateQueries");

		const handled = handlePossibleSessionExpiry(
			res(401, "http://localhost:8080/workspaces/acme/practices"),
			qc,
		);

		expect(handled).toBe(true);
		await flush();
		expect(refreshMock).toHaveBeenCalledOnce();
		expect(assigned).toHaveLength(0);
		expect(invalidate).toHaveBeenCalled();
	});

	it("logs out to /login with sanitised returnTo when the 401 cannot be refreshed", async () => {
		refreshMock.mockResolvedValue("expired");
		const { assigned } = stubLocation("/w/acme/overview", "?tab=prs");
		const qc = makeQueryClient();

		const handled = handlePossibleSessionExpiry(
			res(401, "http://localhost:8080/workspaces/acme/practices"),
			qc,
		);

		expect(handled).toBe(true);
		await flush();
		expect(refreshMock).toHaveBeenCalledOnce();
		expect(assigned).toHaveLength(1);
		const [target] = assigned;
		assert(target);
		const url = new URL(target);
		expect(url.pathname).toBe("/login");
		expect(url.searchParams.get("returnTo")).toBe("/w/acme/overview?tab=prs");
	});

	it("does not log out or loop on a delayed 401 after successful recovery", async () => {
		refreshMock.mockResolvedValue("refreshed");
		const { assigned } = stubLocation("/w/acme/overview");
		const qc = makeQueryClient();
		const url = "http://localhost:8080/workspaces/acme/practices";
		handlePossibleSessionExpiry(res(401, url), qc);
		await flush();
		handlePossibleSessionExpiry(res(401, url), qc);
		await flush();
		expect(refreshMock).toHaveBeenCalledOnce();
		expect(assigned).toHaveLength(0);
	});

	it("preserves the page and cache when refresh is temporarily unavailable", async () => {
		refreshMock.mockResolvedValue("unavailable");
		const { assigned } = stubLocation("/w/acme/overview");
		const qc = makeQueryClient();
		const invalidate = vi.spyOn(qc, "invalidateQueries");
		handlePossibleSessionExpiry(res(401, "http://localhost:8080/workspaces/acme"), qc);
		await flush();
		expect(assigned).toHaveLength(0);
		expect(invalidate).not.toHaveBeenCalled();
	});

	it("collapses concurrent 401s into a single refresh and handles all of them in place", async () => {
		refreshMock.mockResolvedValue("refreshed");
		const { assigned } = stubLocation("/w/acme/overview");
		const qc = makeQueryClient();
		const url = "http://localhost:8080/workspaces/acme/practices";
		const handled = [
			handlePossibleSessionExpiry(res(401, url), qc),
			handlePossibleSessionExpiry(res(401, url), qc),
			handlePossibleSessionExpiry(res(401, url), qc),
		];
		await flush();

		expect(handled).toStrictEqual([true, true, true]);
		expect(refreshMock).toHaveBeenCalledOnce();
		expect(assigned).toHaveLength(0);
	});

	it("does NOT handle (or refresh) a 401 from the GET /user probe", () => {
		const { assigned } = stubLocation("/");
		const handled = handlePossibleSessionExpiry(
			res(401, "http://localhost:8080/user"),
			makeQueryClient(),
		);
		expect(handled).toBe(false);
		expect(refreshMock).not.toHaveBeenCalled();
		expect(assigned).toHaveLength(0);
	});

	it("does NOT handle a 401 from the GET /api/user probe (prod /api base path)", () => {
		const { assigned } = stubLocation("/");
		const handled = handlePossibleSessionExpiry(
			res(401, "http://localhost/api/user"),
			makeQueryClient(),
		);
		expect(handled).toBe(false);
		expect(refreshMock).not.toHaveBeenCalled();
		expect(assigned).toHaveLength(0);
	});

	it("does NOT handle a 401 from /api/auth/* endpoints (prod /api base path)", () => {
		const { assigned } = stubLocation("/");
		const handled = handlePossibleSessionExpiry(
			res(401, "http://localhost/api/auth/refresh"),
			makeQueryClient(),
		);
		expect(handled).toBe(false);
		expect(refreshMock).not.toHaveBeenCalled();
		expect(assigned).toHaveLength(0);
	});

	it("does NOT handle a 401 from /auth/* endpoints (refresh must not recurse)", () => {
		const { assigned } = stubLocation("/");
		const handled = handlePossibleSessionExpiry(
			res(401, "http://localhost:8080/auth/refresh"),
			makeQueryClient(),
		);
		expect(handled).toBe(false);
		expect(refreshMock).not.toHaveBeenCalled();
		expect(assigned).toHaveLength(0);
	});

	it("does NOT handle when already on /login (defence-in-depth against loops)", () => {
		const { assigned } = stubLocation("/login");
		const handled = handlePossibleSessionExpiry(
			res(401, "http://localhost:8080/workspaces/acme/practices"),
			makeQueryClient(),
		);
		expect(handled).toBe(false);
		expect(assigned).toHaveLength(0);
	});

	it("drops an open-redirect returnTo down to '/' when logging out", async () => {
		refreshMock.mockResolvedValue("expired");
		const { assigned } = stubLocation("//evil.example.com");
		handlePossibleSessionExpiry(
			res(401, "http://localhost:8080/workspaces/acme"),
			makeQueryClient(),
		);
		await flush();
		const [target] = assigned;
		assert(target);
		const url = new URL(target);
		expect(url.searchParams.get("returnTo")).toBe("/");
	});

	it.each([200, 204, 403, 500])("ignores non-401 status %i", (status) => {
		const { assigned } = stubLocation("/dashboard");
		const handled = handlePossibleSessionExpiry(
			res(status, "http://localhost:8080/workspaces/acme"),
			makeQueryClient(),
		);
		expect(handled).toBe(false);
		expect(assigned).toHaveLength(0);
	});
});
