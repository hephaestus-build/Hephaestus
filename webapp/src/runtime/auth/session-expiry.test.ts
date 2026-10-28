import { QueryClient } from "@tanstack/react-query";
import { HttpResponse, http } from "msw";
import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";

import environment from "@/environment";
import { currentUser } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { workspaceAddressConfig } from "@/runtime/workspace-address";

import {
	redirectToLogin,
	__resetSessionRecoveryForTests,
	handlePossibleSessionExpiry,
} from "./session-expiry";
import { SESSION_COOLDOWN_MS, refreshAccessToken } from "./session-refresh";

import { hasText } from "@/lib/text";
import { sleep } from "@/test/async";

vi.mock(import("@/environment"), async (importOriginal) => {
	const actual = await importOriginal();
	return { default: Object.assign(actual.default, { serverUrl: "http://localhost/api" }) };
});
function stubLocation(
	pathname: string,
	search = "",
	origin = "http://localhost",
): { assigned: string[] } {
	const assigned: string[] = [];
	const stub = {
		assign: (url: string) => assigned.push(url),
		pathname,
		search,
		origin,
	};
	Object.defineProperty(window, "location", { configurable: true, value: stub });
	return { assigned };
}

const NOW = new Date("2026-10-28T05:17:00Z").getTime();
const realLocation = window.location;
beforeEach(() => {
	window.localStorage.clear();
	__resetSessionRecoveryForTests();
	vi.useFakeTimers({ toFake: ["Date"] });
	vi.setSystemTime(NOW);
});
afterEach(() => {
	Object.defineProperty(window, "location", { configurable: true, value: realLocation });
	vi.useRealTimers();
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

/** Answers the identity read and the renewal with these statuses, and counts both. */
function serveSession({ user = 200, refresh = 204 }: { user?: number; refresh?: number } = {}) {
	const calls = { user: 0, refresh: 0 };
	server.use(
		http.get("*/user", () => {
			calls.user += 1;
			return user === 200
				? HttpResponse.json(currentUser)
				: new HttpResponse(null, { status: user });
		}),
		http.post("*/auth/refresh", () => {
			calls.refresh += 1;
			return new HttpResponse(null, { status: refresh });
		}),
	);
	return calls;
}

const PRACTICES = "http://localhost:8080/workspaces/acme/practices";

/** Recovery chains an identity read and a renewal; both answer well within this wait. */
async function settled(): Promise<void> {
	await sleep(50);
}

describe("handlePossibleSessionExpiry", () => {
	it("keeps a session whose identity still holds without rotating its cookie", async () => {
		const calls = serveSession();
		const { assigned } = stubLocation("/w/acme/overview", "?tab=prs");
		const qc = makeQueryClient();
		const invalidate = vi.spyOn(qc, "invalidateQueries");

		expect(handlePossibleSessionExpiry(res(401, PRACTICES), qc)).toBe(true);
		await settled();

		expect(calls).toStrictEqual({ user: 1, refresh: 0 });
		expect(assigned).toHaveLength(0);
		expect(invalidate).toHaveBeenCalledWith();
	});

	it("still renews after recovery reads a valid identity", async () => {
		const calls = serveSession();
		stubLocation("/w/acme/activity");
		handlePossibleSessionExpiry(res(401, PRACTICES), makeQueryClient());
		await settled();
		expect(calls).toStrictEqual({ user: 1, refresh: 0 });

		await expect(refreshAccessToken()).resolves.toBe("refreshed");

		expect(calls.refresh).toBe(1);
	});

	it("does not rotate twice when a late 401 follows a keep-alive renewal", async () => {
		const calls = serveSession();
		stubLocation("/w/acme/activity");
		const qc = makeQueryClient();

		await expect(refreshAccessToken()).resolves.toBe("refreshed");
		handlePossibleSessionExpiry(res(401, PRACTICES), qc);
		await settled();
		expect(calls).toStrictEqual({ user: 0, refresh: 1 });

		vi.setSystemTime(NOW + SESSION_COOLDOWN_MS);
		handlePossibleSessionExpiry(res(401, PRACTICES), qc);
		await settled();
		expect(calls).toStrictEqual({ user: 1, refresh: 1 });
	});

	it("holds recovery in this tab after a renewal in another tab", async () => {
		const calls = serveSession();
		stubLocation("/w/acme/activity");
		// What another tab's renewal leaves in the storage that all tabs share.
		window.localStorage.setItem(
			"hephaestus:session-refreshed",
			JSON.stringify({ id: crypto.randomUUID(), completedAt: NOW }),
		);

		handlePossibleSessionExpiry(res(401, PRACTICES), makeQueryClient());
		await settled();

		expect(calls).toStrictEqual({ user: 0, refresh: 0 });
	});

	it("reads the identity once for an endpoint that keeps answering 401", async () => {
		const calls = serveSession();
		stubLocation("/w/acme/overview");
		const qc = makeQueryClient();

		handlePossibleSessionExpiry(res(401, PRACTICES), qc);
		await settled();
		handlePossibleSessionExpiry(res(401, PRACTICES), qc);
		await settled();

		expect(calls).toStrictEqual({ user: 1, refresh: 0 });
	});

	it("collapses concurrent 401s into a single identity read and handles all of them in place", async () => {
		const calls = serveSession();
		const { assigned } = stubLocation("/w/acme/overview");
		const qc = makeQueryClient();
		const handled = [
			handlePossibleSessionExpiry(res(401, PRACTICES), qc),
			handlePossibleSessionExpiry(res(401, PRACTICES), qc),
			handlePossibleSessionExpiry(res(401, PRACTICES), qc),
		];
		await settled();

		expect(handled).toStrictEqual([true, true, true]);
		expect(calls).toStrictEqual({ user: 1, refresh: 0 });
		expect(assigned).toHaveLength(0);
	});

	it("renews only when the server refuses the current cookie", async () => {
		const calls = serveSession({ user: 401 });
		const { assigned } = stubLocation("/w/acme/overview");
		const qc = makeQueryClient();
		const invalidate = vi.spyOn(qc, "invalidateQueries");

		handlePossibleSessionExpiry(res(401, PRACTICES), qc);
		await settled();

		expect(calls).toStrictEqual({ user: 1, refresh: 1 });
		expect(assigned).toHaveLength(0);
		expect(invalidate).toHaveBeenCalledWith();
	});

	it("logs out to /login with sanitised returnTo when the refused cookie cannot be renewed", async () => {
		serveSession({ user: 401, refresh: 401 });
		const { assigned } = stubLocation("/w/acme/overview", "?tab=prs");

		handlePossibleSessionExpiry(res(401, PRACTICES), makeQueryClient());
		await settled();

		expect(assigned).toHaveLength(1);
		const [target] = assigned;
		assert(hasText(target));
		const url = new URL(target);
		expect(url.pathname).toBe("/login");
		expect(url.searchParams.get("returnTo")).toBe("/w/acme/overview?tab=prs");
	});

	it("preserves the page and cache when the server cannot answer", async () => {
		const calls = serveSession({ user: 503 });
		const { assigned } = stubLocation("/w/acme/overview");
		const qc = makeQueryClient();
		const invalidate = vi.spyOn(qc, "invalidateQueries");

		handlePossibleSessionExpiry(res(401, "http://localhost:8080/workspaces/acme"), qc);
		await settled();

		expect(calls).toStrictEqual({ user: 1, refresh: 0 });
		expect(assigned).toHaveLength(0);
		expect(invalidate).not.toHaveBeenCalled();
	});

	it("preserves the page when the renewal is temporarily unavailable", async () => {
		serveSession({ user: 401, refresh: 503 });
		const { assigned } = stubLocation("/w/acme/overview");
		const qc = makeQueryClient();
		const invalidate = vi.spyOn(qc, "invalidateQueries");

		handlePossibleSessionExpiry(res(401, "http://localhost:8080/workspaces/acme"), qc);
		await settled();

		expect(assigned).toHaveLength(0);
		expect(invalidate).not.toHaveBeenCalled();
	});

	it.each([
		["the GET /user probe", "/", "http://localhost:8080/user"],
		["the GET /api/user probe (prod /api base path)", "/", "http://localhost/api/user"],
		["/api/auth/* endpoints (prod /api base path)", "/", "http://localhost/api/auth/refresh"],
		["/auth/* endpoints (refresh must not recurse)", "/", "http://localhost:8080/auth/refresh"],
		["any request while already on /login", "/login", PRACTICES],
	])("does NOT handle a 401 from %s", async (_name, pathname, url) => {
		const calls = serveSession();
		const { assigned } = stubLocation(pathname);

		expect(handlePossibleSessionExpiry(res(401, url), makeQueryClient())).toBe(false);
		await settled();

		expect(calls).toStrictEqual({ user: 0, refresh: 0 });
		expect(assigned).toHaveLength(0);
	});

	it("drops an open-redirect returnTo down to '/' when logging out", async () => {
		serveSession({ user: 401, refresh: 401 });
		const { assigned } = stubLocation("//evil.example.com");
		handlePossibleSessionExpiry(
			res(401, "http://localhost:8080/workspaces/acme"),
			makeQueryClient(),
		);
		await settled();
		const [target] = assigned;
		assert(hasText(target));
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

it("returns tenant sign-in through a relative apex workspace path", () => {
	const { assigned } = stubLocation("/activity", "?range=1y", "https://acme.hephaestus.build");
	const previous = {
		enabled: workspaceAddressConfig.enabled,
		baseDomain: workspaceAddressConfig.baseDomain,
	};
	const { clientUrl } = environment;
	try {
		Object.assign(workspaceAddressConfig, {
			enabled: true,
			baseDomain: "hephaestus.build",
		});
		environment.clientUrl = "https://hephaestus.build";
		redirectToLogin();
		expect(assigned).toStrictEqual([
			"https://hephaestus.build/login?returnTo=%2Fw%2Facme%2Factivity%3Frange%3D1y",
		]);
	} finally {
		Object.assign(workspaceAddressConfig, previous);
		environment.clientUrl = clientUrl;
	}
});
