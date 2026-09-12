import { HttpResponse, http } from "msw";
import { afterEach, assert, describe, expect, it } from "vitest";

import type { CurrentUserView } from "@/api/types.gen";
import { server } from "@/mocks/server";

import { applyStateChangingHeaders, authClient, toUserProfile } from "./auth-client";
function makeCurrentUser(overrides: CurrentUserView = {}): CurrentUserView {
	return {
		id: 7,
		displayName: "Ada Lovelace",
		appRole: "USER",
		status: "ACTIVE",
		roles: ["user"],
		hasGitLabIdentity: false,
		...overrides,
	};
}
describe("toUserProfile", () => {
	it("maps the core CurrentUserView fields onto UserProfile", () => {
		const profile = toUserProfile(
			makeCurrentUser({
				id: 42,
				displayName: "Ada Lovelace",
				username: "ada",
				primaryEmail: "ada@example.com",
				roles: ["user", "admin"],
				identityProvider: "GITHUB",
				gitProviderId: "1001",
			}),
		);

		expect(profile.id).toBe("42");
		expect(profile.username).toBe("ada");
		expect(profile.email).toBe("ada@example.com");
		expect(profile.name).toBe("Ada Lovelace");
		expect(profile.firstName).toBe("Ada");
		expect(profile.lastName).toBe("Lovelace");
		expect(profile.roles).toStrictEqual(["user", "admin"]);
		expect(profile.identityProvider).toBe("GITHUB");
	});

	it("maps gitProviderId to githubId only for GITHUB", () => {
		const profile = toUserProfile(
			makeCurrentUser({ identityProvider: "GITHUB", gitProviderId: "1001" }),
		);
		expect(profile.githubId).toBe("1001");
		expect(profile.gitlabId).toBeUndefined();
	});

	it("maps gitProviderId to gitlabId only for GITLAB", () => {
		const profile = toUserProfile(
			makeCurrentUser({ identityProvider: "GITLAB", gitProviderId: "2002" }),
		);
		expect(profile.gitlabId).toBe("2002");
		expect(profile.githubId).toBeUndefined();
	});

	it("falls back to username for the name when displayName is missing, and defaults missing fields", () => {
		const profile = toUserProfile(
			makeCurrentUser({
				displayName: undefined,
				username: "solo",
				primaryEmail: undefined,
				roles: undefined,
				identityProvider: undefined,
				gitProviderId: undefined,
			}),
		);
		expect(profile.name).toBe("solo");
		expect(profile.firstName).toBe("solo");
		expect(profile.lastName).toBe("");
		expect(profile.email).toBe("");
		expect(profile.roles).toStrictEqual([]);
		expect(profile.identityProvider).toBeUndefined();
		expect(profile.githubId).toBeUndefined();
		expect(profile.gitlabId).toBeUndefined();
	});

	it("treats a single-word display name as the first name with empty last name", () => {
		const profile = toUserProfile(makeCurrentUser({ displayName: "Cher" }));
		expect(profile.firstName).toBe("Cher");
		expect(profile.lastName).toBe("");
	});
});

describe("authClient.login — returnTo forwarding (safeReturnTo guard)", () => {
	const realLocation = window.location;
	afterEach(() => {
		Object.defineProperty(window, "location", { configurable: true, value: realLocation });
	});
	function stubLocation(): { assigned: string[] } {
		const assigned: string[] = [];
		const stub = { assign: (url: string) => assigned.push(url) };
		Object.defineProperty(window, "location", { configurable: true, value: stub });
		return { assigned };
	}

	it("redirects to the server kickoff carrying a safe same-origin returnTo", () => {
		const { assigned } = stubLocation();
		authClient.login("gitlab", "/settings/account");
		expect(assigned).toHaveLength(1);
		const [target] = assigned;
		assert(target);
		const url = new URL(target);
		expect(`${url.origin}${url.pathname}`).toBe("http://localhost:8080/auth/login");
		expect(url.searchParams.get("provider")).toBe("gitlab");
		expect(url.searchParams.get("returnTo")).toBe("/settings/account");
	});

	it("drops an unsafe (open-redirect) returnTo down to '/'", () => {
		const { assigned } = stubLocation();
		authClient.login("github", "//evil.example.com/phish");
		const [target] = assigned;
		assert(target);
		const url = new URL(target);
		expect(url.searchParams.get("returnTo")).toBe("/");
	});

	it("defaults the provider to github when no idpHint is given", () => {
		const { assigned } = stubLocation();
		authClient.login(undefined, "/dashboard");
		const [target] = assigned;
		assert(target);
		const url = new URL(target);
		expect(url.searchParams.get("provider")).toBe("github");
		expect(url.searchParams.get("returnTo")).toBe("/dashboard");
	});
});

describe("applyStateChangingHeaders (app-wide CSRF)", () => {
	function setCookie(raw: string) {
		Object.defineProperty(document, "cookie", { configurable: true, get: () => raw });
	}
	function req(method: string): Request {
		return new Request("http://localhost:8080/user", { method });
	}

	afterEach(() => setCookie(""));

	it("adds the CSRF double-submit header on state-changing methods", () => {
		setCookie("__Host-XSRF-TOKEN=tok-123");
		const r = applyStateChangingHeaders(req("POST"));
		expect(r.headers.get("X-XSRF-TOKEN")).toBe("tok-123");
	});

	it("sends NO CSRF header on safe methods", () => {
		setCookie("__Host-XSRF-TOKEN=tok-123");
		expect(applyStateChangingHeaders(req("GET")).headers.get("X-XSRF-TOKEN")).toBeNull();
		expect(applyStateChangingHeaders(req("HEAD")).headers.get("X-XSRF-TOKEN")).toBeNull();
	});

	it("omits the CSRF header (fail-safe) when the token cookie is absent", () => {
		setCookie("");
		expect(applyStateChangingHeaders(req("POST")).headers.get("X-XSRF-TOKEN")).toBeNull();
	});
});

describe("authClient.logout", () => {
	const realLocation = window.location;
	afterEach(() => {
		Object.defineProperty(window, "location", { configurable: true, value: realLocation });
	});

	function captureNavigation() {
		const assigned: string[] = [];
		Object.defineProperty(window, "location", {
			configurable: true,
			value: { assign: (url: string) => assigned.push(url) },
		});
		return assigned;
	}

	it.each([204, 401])(
		"returns home when HTTP %i confirms the session has ended",
		async (status) => {
			server.use(http.post("*/auth/logout", () => new HttpResponse(null, { status })));
			const assigned = captureNavigation();
			await authClient.logout();
			expect(assigned).toStrictEqual(["/"]);
		},
	);

	it.each([403, 503])("does not disguise HTTP %i as a successful sign-out", async (status) => {
		server.use(http.post("*/auth/logout", () => new HttpResponse(null, { status })));
		const assigned = captureNavigation();
		await expect(authClient.logout()).rejects.toBeDefined();
		expect(assigned).toStrictEqual([]);
	});

	it("keeps sign-out retryable when the connection fails", async () => {
		server.use(http.post("*/auth/logout", () => HttpResponse.error(), { once: true }));
		const assigned = captureNavigation();
		await expect(authClient.logout()).rejects.toBeDefined();
		expect(assigned).toStrictEqual([]);
		server.use(http.post("*/auth/logout", () => new HttpResponse(null, { status: 204 })));
		await authClient.logout();
		expect(assigned).toStrictEqual(["/"]);
	});
});
