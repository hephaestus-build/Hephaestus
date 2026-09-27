import { describe, expect, it } from "vitest";

import { nativeSignInUrl, parseSignInCallback, signInErrorMessage } from "./sign-in-url";

const REDIRECT = "build.hephaestus.app.dev:/auth/callback";

describe("nativeSignInUrl", () => {
	it("starts the native federation with the challenge, state and exact redirect", () => {
		const url = new URL(
			nativeSignInUrl(
				"https://team.example.org/api",
				{ provider: "github" },
				{ challenge: "c".repeat(43), state: "s1" },
				REDIRECT,
			),
		);

		expect(url.pathname).toBe("/api/auth/login/native");
		expect(Object.fromEntries(url.searchParams)).toStrictEqual({
			provider: "github",
			code_challenge: "c".repeat(43),
			code_challenge_method: "S256",
			state: "s1",
			redirect_uri: REDIRECT,
		});
	});

	it("uses the dev sign-in path for a dev username", () => {
		const url = new URL(
			nativeSignInUrl(
				"http://localhost:18380",
				{ devUsername: "nora" },
				{ challenge: "c", state: "s" },
				REDIRECT,
			),
		);

		expect(url.pathname).toBe("/auth/dev-login/native");
		expect(url.searchParams.get("username")).toBe("nora");
	});
});

describe("parseSignInCallback", () => {
	it("returns the code when the state matches", () => {
		expect(parseSignInCallback(`${REDIRECT}?code=abc&state=s1`, REDIRECT, "s1")).toStrictEqual({
			kind: "code",
			code: "abc",
		});
	});

	it("returns the server's error with a matching state", () => {
		expect(
			parseSignInCallback(`${REDIRECT}?error=account_inactive&state=s1`, REDIRECT, "s1"),
		).toStrictEqual({ kind: "error", error: "account_inactive" });
	});

	it("ignores a callback carrying another sign-in's state", () => {
		expect(parseSignInCallback(`${REDIRECT}?code=abc&state=other`, REDIRECT, "s1")).toStrictEqual({
			kind: "foreign",
		});
	});

	it("ignores an address that is not this app's callback", () => {
		expect(
			parseSignInCallback("build.hephaestus.app:/auth/callback?code=abc&state=s1", REDIRECT, "s1"),
		).toStrictEqual({ kind: "foreign" });
		expect(parseSignInCallback(`${REDIRECT}-evil?code=abc&state=s1`, REDIRECT, "s1")).toStrictEqual(
			{
				kind: "foreign",
			},
		);
	});
});

describe("signInErrorMessage", () => {
	it("stays quiet when the person cancelled at the provider", () => {
		expect(signInErrorMessage("access_denied")).toBeNull();
	});

	it("explains a suspended account", () => {
		expect(signInErrorMessage("account_inactive")).toContain("suspended");
	});
});
