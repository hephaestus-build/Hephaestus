import Constants from "expo-constants";
import * as Crypto from "expo-crypto";
import * as WebBrowser from "expo-web-browser";

import { exchangeNativeSignIn } from "@/api/sdk.gen";
import { publicClient } from "@/instance/instance";
import type { Instance } from "@/instance/instance-url";

import { createPkce } from "./pkce";
import { SESSION_REQUEST_TIMEOUT_MS, beginSession } from "./session-store";
import { runSignIn, type SignInResult } from "./sign-in-flow";
import { nativeSignInUrl } from "./sign-in-url";

export type { SignInResult } from "./sign-in-flow";

/** This build's callback: its own reverse-domain scheme, which the server's allowlist names exactly. */
export function redirectUri(): string {
	const scheme = Constants.expoConfig?.scheme;
	if (typeof scheme !== "string") {
		throw new Error("app.config.ts must declare a single scheme");
	}
	return `${scheme}:/auth/callback`;
}

function randomBytes(length: number): Uint8Array {
	return Crypto.getRandomBytes(length);
}

async function sha256(ascii: string): Promise<Uint8Array> {
	const digest = await Crypto.digest(
		Crypto.CryptoDigestAlgorithm.SHA256,
		new TextEncoder().encode(ascii),
	);
	return new Uint8Array(digest);
}

/**
 * Signs in through the system's authentication sheet (ASWebAuthenticationSession, Custom Tabs). The
 * identity provider's sign-in happens there, where the person's browser session and password manager
 * are; the app only ever receives a single-use code bound to its PKCE challenge, and redeems it itself.
 * A first sign-in creates the account on the server; it is the same path.
 */
export async function signIn(
	instance: Instance,
	method: { provider: string } | { devUsername: string },
): Promise<SignInResult> {
	return runSignIn({
		redirectUri,
		createPkce: async () => createPkce(randomBytes, sha256),
		signInUrl: (pkce, redirect) => nativeSignInUrl(instance.apiBaseUrl, method, pkce, redirect),
		authenticate: async (url, redirect) => {
			const result = await WebBrowser.openAuthSessionAsync(url, redirect);
			return result.type === "success" ? result.url : undefined;
		},
		redeem: async (code, verifier) => {
			const { data } = await exchangeNativeSignIn({
				client: publicClient(instance.apiBaseUrl),
				body: { code, codeVerifier: verifier },
				signal: AbortSignal.timeout(SESSION_REQUEST_TIMEOUT_MS),
			});
			return beginSession(instance, data);
		},
	});
}
