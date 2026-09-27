import { describe, expect, it } from "vitest";

import { readCallback, signInUrl } from "~/background/sign-in";

const REDIRECT = "https://ijkajblcbajjpjbknfgdiiiljipafiko.chromiumapp.org/callback";
const CODE = "A".repeat(43);

describe("readCallback", () => {
	it("accepts the exact redirect with the exact state", () => {
		expect(
			readCallback(`${REDIRECT}?code=${CODE}&state=s1`, { redirectUri: REDIRECT, state: "s1" }),
		).toStrictEqual({ code: CODE });
	});

	it.each([
		[`${REDIRECT}?code=${CODE}&state=other`, "state"],
		[`${REDIRECT}?code=${CODE}`, "state"],
		[`${REDIRECT}?code=${CODE}&state=s1&state=s1`, "state"],
		[`${REDIRECT}/x?code=${CODE}&state=s1`, "invalid"],
		[`https://evil.chromiumapp.org/callback?code=${CODE}&state=s1`, "invalid"],
		[`${REDIRECT}?code=short&state=s1`, "invalid"],
		[`${REDIRECT}?code=${CODE}&code=${CODE}&state=s1`, "invalid"],
		[`${REDIRECT}?error=access_denied&state=s1`, "access_denied"],
	])("refuses %s", (url, error) => {
		expect(readCallback(url, { redirectUri: REDIRECT, state: "s1" })).toStrictEqual({ error });
	});
});

describe("signInUrl", () => {
	const instance = {
		origin: "https://heph.example.test",
		apiBase: "https://heph.example.test/api",
		webAppOrigin: "https://heph.example.test",
	};
	const params = { clientId: "id", redirectUri: REDIRECT, challenge: "c", state: "s" };

	it("starts a provider sign-in on the API in client mode", () => {
		const url = new URL(
			signInUrl(instance, { kind: "provider", registrationId: "gitlab-lrz" }, params),
		);
		expect(url.origin + url.pathname).toBe("https://heph.example.test/api/auth/login");
		expect(Object.fromEntries(url.searchParams)).toStrictEqual({
			provider: "gitlab-lrz",
			mode: "client",
			client_id: "id",
			redirect_uri: REDIRECT,
			code_challenge: "c",
			code_challenge_method: "S256",
			state: "s",
		});
	});

	it("starts a development sign-in on its own door", () => {
		const url = new URL(signInUrl(instance, { kind: "dev", username: "e2e", admin: true }, params));
		expect(url.pathname).toBe("/api/auth/dev-login/client");
		expect(url.searchParams.get("username")).toBe("e2e");
		expect(url.searchParams.get("admin")).toBe("true");
	});
});
