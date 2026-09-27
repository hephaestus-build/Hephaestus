import * as Crypto from "expo-crypto";
import { File, Paths } from "expo-file-system";
import * as SecureStore from "expo-secure-store";
import { useSyncExternalStore } from "react";
import { AppState } from "react-native";

import { logoutNativeSession, refreshNativeSession } from "@/api/sdk.gen";
import type { NativeSessionTokens } from "@/api/types.gen";
import { publicClient } from "@/instance/instance";
import type { Instance } from "@/instance/instance-url";

import { issuedTokens } from "./issued-tokens";
import { createSessionCore, type RefreshOutcome, type SessionState } from "./session-core";

export type { SessionState } from "./session-core";

const STORE_OPTIONS: SecureStore.SecureStoreOptions = {
	// Readable after the first unlock so a refresh can run when the app wakes in the background;
	// THIS_DEVICE_ONLY keeps it out of iCloud Keychain and encrypted backups.
	keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
};

/**
 * A random id created once per installation and kept in the app's sandbox, which — unlike the iOS
 * keychain — does not survive an uninstall. A keychain session whose id differs belongs to a previous
 * installation and is discarded rather than silently resumed.
 */
export function installationId(): string {
	const file = new File(Paths.document, "installation-id");
	if (file.exists) {
		const existing = file.textSync().trim();
		if (existing !== "") {
			return existing;
		}
	}
	const created = Crypto.randomUUID();
	file.create({ overwrite: true });
	file.write(created);
	return created;
}

/**
 * How long a sign-in exchange, refresh or sign-out may take. Each runs inside the session's serialized
 * transitions, so a request a dead network never answers would otherwise hold every later one. None
 * is retried on its own: a refresh the server committed but whose answer was lost needs a new sign-in.
 */
export const SESSION_REQUEST_TIMEOUT_MS = 15_000;

async function refresh(instance: Instance, refreshToken: string): Promise<RefreshOutcome> {
	try {
		const { data, response } = await refreshNativeSession({
			client: publicClient(instance.apiBaseUrl),
			body: { refreshToken },
			signal: AbortSignal.timeout(SESSION_REQUEST_TIMEOUT_MS),
		});
		if (response?.status === 401) {
			return { kind: "ended" };
		}
		const tokens = issuedTokens(data);
		return tokens === undefined ? { kind: "failed" } : { kind: "rotated", tokens };
	} catch {
		return { kind: "failed" };
	}
}

async function logout(apiBaseUrl: string, refreshToken: string): Promise<"settled" | "retry"> {
	try {
		const { response } = await logoutNativeSession({
			client: publicClient(apiBaseUrl),
			body: { refreshToken },
			signal: AbortSignal.timeout(SESSION_REQUEST_TIMEOUT_MS),
		});
		// Any answer but a server failure settles it: 204 even for a secret the server no longer knows.
		return response === undefined || response.status >= 500 || response.status === 429
			? "retry"
			: "settled";
	} catch {
		return "retry";
	}
}

export const session = createSessionCore({
	storage: {
		read: async (key) => SecureStore.getItemAsync(key, STORE_OPTIONS),
		write: async (key, value) => SecureStore.setItemAsync(key, value, STORE_OPTIONS),
		remove: async (key) => SecureStore.deleteItemAsync(key, STORE_OPTIONS),
	},
	installationId,
	now: () => Date.now(),
	refresh,
	logout,
});

// A sign-out made offline reaches the server the next time the app is opened or brought back.
AppState.addEventListener("change", (state) => {
	if (state === "active") {
		void session.flushRevocations();
	}
});

export function useSession(): SessionState {
	return useSyncExternalStore(
		(listener) => session.subscribe(listener),
		() => session.getState(),
	);
}

export async function restoreSession(): Promise<void> {
	await session.restore();
	void session.flushRevocations();
	try {
		// Warm the access token; offline, the app stays signed in and screens say they cannot load.
		await session.accessToken();
	} catch {
		// Nothing to warm: signed out, or unreachable for now.
	}
}

/** Starts a session from a sign-in handoff; false when the response was not a complete session. */
export async function beginSession(
	instance: Instance,
	dto: NativeSessionTokens | undefined,
): Promise<boolean> {
	const tokens = issuedTokens(dto);
	if (tokens === undefined) {
		return false;
	}
	await session.begin(instance, tokens);
	return true;
}

/** The current session's consent, as the screen that read it reports it. */
export function markConsent(consent: "required" | "complete"): void {
	const owner = session.owner();
	if (owner !== undefined) {
		session.consent(owner, consent);
	}
}

export async function renewSession(): Promise<void> {
	await session.renew();
}

const beforeSignOut = new Set<() => Promise<void>>();

/**
 * Registers clean-up to run while the session can still authenticate — for work the server would also
 * do once the session ends, such as unregistering push. Bounded, so it never holds the sign-out.
 */
export function onBeforeSignOut(task: () => Promise<void>): () => void {
	beforeSignOut.add(task);
	return () => {
		beforeSignOut.delete(task);
	};
}

export async function signOut(): Promise<void> {
	const timeout = AbortSignal.timeout(3000);
	await Promise.race([
		Promise.allSettled([...beforeSignOut].map(async (task) => task())),
		// oxlint-disable-next-line promise/avoid-new -- an AbortSignal only exposes an event, not a promise
		new Promise((resolve) => {
			timeout.addEventListener("abort", resolve);
		}),
	]);
	await session.signOut();
}
