import assert from "node:assert/strict";
import { describe, expect, it } from "vitest";
import { z } from "zod";

import {
	createSessionCore,
	type IssuedTokens,
	REVOCATIONS_KEY,
	type RefreshOutcome,
	SESSION_KEY,
	type SessionStorage,
} from "./session-core";

const NOW = 1_800_000_000_000;
const HOUR = 3_600_000;
const INSTANCE = {
	apiBaseUrl: "https://team.example.org/api",
	label: "team.example.org",
	webUrl: "https://team.example.org",
};
const OTHER = {
	apiBaseUrl: "https://other.example.org/api",
	label: "other.example.org",
	webUrl: "https://other.example.org",
};

const SIGN_IN = "7a1f3c5e-9b2d-4f6a-8c1e-3d5f7a9b1c2e";
const OTHER_SIGN_IN = "2c4e6a8b-1d3f-4a5c-9e7b-6f8d0a2c4e6a";

function issued(
	secret: string,
	access = `access-${secret}`,
	nativeSessionId = SIGN_IN,
): IssuedTokens {
	return {
		nativeSessionId,
		accessToken: access,
		accessTokenExpiresAt: NOW + HOUR,
		refreshToken: secret,
		sessionExpiresAt: NOW + 24 * HOUR,
	};
}

interface Gate<T> {
	promise: Promise<T>;
	open: (value: T) => void;
	fail: (error: Error) => void;
}

function gate<T>(): Gate<T> {
	let resolveGate: ((value: T) => void) | undefined;
	let rejectGate: ((error: Error) => void) | undefined;
	// oxlint-disable-next-line promise/avoid-new -- the test decides when each network answer arrives
	const promise = new Promise<T>((resolve, reject) => {
		resolveGate = resolve;
		rejectGate = reject;
	});
	return {
		promise,
		open: (value) => resolveGate?.(value),
		fail: (error) => rejectGate?.(error),
	};
}

/** Lets every queued microtask and continuation run. */
async function settle(): Promise<void> {
	for (let turn = 0; turn < 20; turn += 1) {
		await Promise.resolve();
	}
}

const storedSession = z.object({
	refreshToken: z.string(),
	instance: z.object({ apiBaseUrl: z.string() }),
});
const storedQueue = z.array(z.object({ refreshToken: z.string() }));

class MemoryStorage implements SessionStorage {
	readonly values = new Map<string, string>();
	failWrites = false;
	/** When set, the next write of `key` waits for the test to release it. */
	writeGates = new Map<string, Gate<undefined>>();

	async read(key: string): Promise<string | null> {
		return this.values.get(key) ?? null;
	}

	async write(key: string, value: string): Promise<void> {
		const waiting = this.writeGates.get(key);
		if (waiting !== undefined) {
			this.writeGates.delete(key);
			await waiting.promise;
		}
		if (this.failWrites) {
			throw new Error("keychain unavailable");
		}
		this.values.set(key, value);
	}

	async remove(key: string): Promise<void> {
		this.values.delete(key);
	}

	session(): z.infer<typeof storedSession> | undefined {
		const raw = this.values.get(SESSION_KEY);
		return raw === undefined ? undefined : storedSession.parse(JSON.parse(raw));
	}

	queued(): string[] {
		const raw = this.values.get(REVOCATIONS_KEY);
		return raw === undefined
			? []
			: storedQueue.parse(JSON.parse(raw)).map((item) => item.refreshToken);
	}
}

function harness(installation = "install-1") {
	const storage = new MemoryStorage();
	const refreshes: { secret: string; answer: Gate<RefreshOutcome> }[] = [];
	const logouts: { secret: string; answer: Gate<"settled" | "retry"> }[] = [];
	let now = NOW;
	const core = createSessionCore({
		storage,
		installationId: () => installation,
		now: () => now,
		refresh: async (_instance, secret) => {
			const answer = gate<RefreshOutcome>();
			refreshes.push({ secret, answer });
			return answer.promise;
		},
		logout: async (_base, secret) => {
			const answer = gate<"settled" | "retry">();
			logouts.push({ secret, answer });
			return answer.promise;
		},
	});
	return {
		core,
		storage,
		refreshes,
		logouts,
		advance: (ms: number) => {
			now += ms;
		},
	};
}

describe("session lifecycle", () => {
	it("restores a stored session and rotates its secret for the first request", async () => {
		const { core, storage, refreshes } = harness();
		const first = harness();
		await first.core.begin(INSTANCE, issued("s1"));
		const stored = first.storage.values.get(SESSION_KEY);
		assert.ok(stored !== undefined);
		storage.values.set(SESSION_KEY, stored);

		await core.restore();
		expect(core.getState()).toMatchObject({ status: "signedIn", instance: INSTANCE });

		const token = core.accessToken();
		await settle();
		expect(refreshes.map((refresh) => refresh.secret)).toStrictEqual(["s1"]);
		refreshes[0]?.answer.open({ kind: "rotated", tokens: issued("s2") });

		await expect(token).resolves.toMatchObject({ token: "access-s2" });
		expect(storage.session()?.refreshToken).toBe("s2");
	});

	it("discards a session stored by a previous installation", async () => {
		const before = harness("install-old");
		await before.core.begin(INSTANCE, issued("s1"));
		const { core, storage } = harness("install-new");
		const stored = before.storage.values.get(SESSION_KEY);
		assert.ok(stored !== undefined);
		storage.values.set(SESSION_KEY, stored);

		await core.restore();

		expect(core.getState()).toStrictEqual({ status: "signedOut", notice: "reinstalled" });
		expect(storage.session()).toBeUndefined();
	});

	it("stays signed in offline and keeps the stored secret", async () => {
		const { core, storage, refreshes } = harness();
		await core.begin(INSTANCE, issued("s1"));
		const renewing = core.renew();
		await settle();
		refreshes.at(-1)?.answer.open({ kind: "failed" });

		await expect(renewing).rejects.toThrow("did not get an answer");
		expect(core.getState()).toMatchObject({ status: "signedIn" });
		expect(storage.session()?.refreshToken).toBe("s1");
	});

	it("never retries a refresh on its own", async () => {
		const { core, refreshes } = harness();
		await core.begin(INSTANCE, issued("s1"));

		const renewing = core.renew();
		await settle();
		refreshes[0]?.answer.open({ kind: "failed" });
		await expect(renewing).rejects.toThrow("did not get an answer");
		await settle();

		expect(refreshes).toHaveLength(1);
	});

	it("shares one rotation between concurrent callers", async () => {
		const { core, refreshes } = harness();
		await core.begin(INSTANCE, { ...issued("s1"), accessTokenExpiresAt: NOW });

		const first = core.accessToken();
		const second = core.accessToken();
		await settle();
		refreshes[0]?.answer.open({ kind: "rotated", tokens: issued("s2") });

		await expect(Promise.all([first, second])).resolves.toMatchObject([
			{ token: "access-s2" },
			{ token: "access-s2" },
		]);
		expect(refreshes).toHaveLength(1);
	});

	it("keeps the sign-in's session id across a refresh and a relaunch", async () => {
		const first = harness();
		await first.core.begin(INSTANCE, { ...issued("s1"), accessTokenExpiresAt: NOW });
		const renewing = first.core.renew();
		await settle();
		first.refreshes[0]?.answer.open({ kind: "rotated", tokens: issued("s2") });
		await renewing;

		const relaunched = harness();
		for (const [key, value] of first.storage.values) {
			relaunched.storage.values.set(key, value);
		}
		await relaunched.core.restore();

		expect(first.core.getState()).toMatchObject({ status: "signedIn", nativeSessionId: SIGN_IN });
		expect(relaunched.core.getState()).toMatchObject({
			status: "signedIn",
			nativeSessionId: SIGN_IN,
		});
	});

	it("ends the session when a refresh answers for another sign-in, revoking what it brought", async () => {
		const { core, storage, refreshes } = harness();
		await core.begin(INSTANCE, issued("s1"));

		const renewing = core.renew();
		await settle();
		refreshes[0]?.answer.open({
			kind: "rotated",
			tokens: issued("s2", "access-s2", OTHER_SIGN_IN),
		});

		await expect(renewing).rejects.toThrow("another session");
		expect(core.getState()).toMatchObject({ status: "signedOut" });
		expect(storage.session()).toBeUndefined();
		expect(storage.queued()).toContain("s2");
	});

	it("signs out when the server says the session ended", async () => {
		const { core, storage, refreshes } = harness();
		await core.begin(INSTANCE, issued("s1"));

		const renewing = core.renew();
		await settle();
		refreshes[0]?.answer.open({ kind: "ended" });

		await expect(renewing).rejects.toThrow("ended");
		expect(core.getState()).toStrictEqual({ status: "signedOut", notice: "ended" });
		expect(storage.session()).toBeUndefined();
	});
});

describe("sign-out racing a refresh", () => {
	it("never resurrects the session from a refresh that answers after sign-out", async () => {
		const { core, storage, refreshes } = harness();
		await core.begin(INSTANCE, issued("s1"));
		const renewing = core.renew();
		await settle();

		void core.signOut();
		await settle();
		refreshes[0]?.answer.open({ kind: "rotated", tokens: issued("s2") });

		await expect(renewing).rejects.toThrow("signed out");
		await settle();
		expect(core.getState()).toStrictEqual({ status: "signedOut" });
		expect(storage.session()).toBeUndefined();
		// Both the secret it held and the one the late answer brought back are presented for sign-out.
		expect(storage.queued()).toStrictEqual(expect.arrayContaining(["s1", "s2"]));
	});

	it("waits for a rotation writing the keychain before signing out, then revokes the new secret", async () => {
		const { core, storage, refreshes } = harness();
		await core.begin(INSTANCE, issued("s1"));
		const write = gate<undefined>();
		storage.writeGates.set(SESSION_KEY, write);

		const renewing = core.renew();
		await settle();
		refreshes[0]?.answer.open({ kind: "rotated", tokens: issued("s2") });
		await settle();
		const signingOut = core.signOut();
		await settle();
		expect(core.getState()).toMatchObject({ status: "signedIn" });

		write.open(undefined);
		await renewing;
		await settle();

		expect(core.getState()).toStrictEqual({ status: "signedOut" });
		expect(storage.session()).toBeUndefined();
		expect(storage.queued()).toContain("s2");
		void signingOut;
	});

	it("keeps a new account's session when the old account's refresh answers late", async () => {
		const { core, storage, refreshes } = harness();
		await core.begin(INSTANCE, issued("old-1"));
		const renewing = core.renew();
		await settle();

		void core.signOut();
		await settle();
		await core.begin(OTHER, issued("new-1"));
		refreshes[0]?.answer.open({ kind: "rotated", tokens: issued("old-2") });

		await expect(renewing).rejects.toThrow("signed out");
		expect(core.getState()).toMatchObject({ status: "signedIn", instance: OTHER });
		expect(storage.session()).toMatchObject({
			refreshToken: "new-1",
			instance: { apiBaseUrl: OTHER.apiBaseUrl },
		});
		expect(storage.queued()).toStrictEqual(expect.arrayContaining(["old-1", "old-2"]));
		expect(storage.queued()).not.toContain("new-1");
	});
});

describe("revocation queue", () => {
	it("keeps a secret queued while an earlier flush is presenting others", async () => {
		const { core, storage, logouts } = harness();
		await core.begin(INSTANCE, issued("a"));
		const firstSignOut = core.signOut();
		await settle();
		expect(logouts.map((logout) => logout.secret)).toStrictEqual(["a"]);

		await core.begin(INSTANCE, issued("b"));
		// Signing out again while "a" is still being presented queues "b" durably.
		const secondSignOut = core.signOut();
		await settle();
		expect(storage.queued()).toStrictEqual(["a", "b"]);

		logouts[0]?.answer.open("settled");
		await firstSignOut;
		await settle();
		expect(storage.queued()).toStrictEqual(["b"]);
		expect(logouts.map((logout) => logout.secret)).toStrictEqual(["a", "b"]);

		logouts[1]?.answer.open("settled");
		await secondSignOut;
		expect(storage.queued()).toStrictEqual([]);
	});

	it("keeps an offline sign-out queued for a later launch", async () => {
		const { core, storage, logouts } = harness();
		await core.begin(INSTANCE, issued("s1"));

		const signingOut = core.signOut();
		await settle();
		logouts[0]?.answer.open("retry");
		await signingOut;

		expect(core.getState()).toStrictEqual({ status: "signedOut" });
		expect(storage.queued()).toStrictEqual(["s1"]);
	});

	it("drops a queued secret once the session it belonged to has expired anyway", async () => {
		const { core, storage, logouts, advance } = harness();
		await core.begin(INSTANCE, issued("s1"));
		const signingOut = core.signOut();
		await settle();
		logouts[0]?.answer.open("retry");
		await signingOut;

		advance(25 * HOUR);
		await core.flushRevocations();

		expect(logouts).toHaveLength(1);
		expect(storage.queued()).toStrictEqual([]);
	});

	it("revokes rather than abandons a sign-in the keychain could not store", async () => {
		const { core, storage } = harness();
		storage.failWrites = true;

		await expect(core.begin(INSTANCE, issued("s1"))).rejects.toThrow("keychain");

		expect(core.getState()).toStrictEqual({ status: "restoring" });
		storage.failWrites = false;
		expect(storage.session()).toBeUndefined();
	});
});

describe("stale answers", () => {
	it("ignores a 401 from a request of an earlier session", async () => {
		const { core, refreshes } = harness();
		await core.begin(INSTANCE, issued("old"));
		const oldOwner = core.owner();
		void core.signOut();
		await settle();
		await core.begin(OTHER, issued("new"));

		assert.ok(oldOwner);
		await core.unauthorized(oldOwner);

		expect(refreshes).toHaveLength(0);
		expect(core.getState()).toMatchObject({ status: "signedIn", instance: OTHER });
	});

	it("does not ask a new account for consent because of an old request's 428", async () => {
		const { core } = harness();
		await core.begin(INSTANCE, issued("old"));
		const oldOwner = core.owner();
		void core.signOut();
		await settle();
		await core.begin(OTHER, issued("new"));

		assert.ok(oldOwner);
		core.consent(oldOwner, "required");

		expect(core.getState()).toMatchObject({ status: "signedIn", consent: "unknown" });
	});

	it("keeps working in this process when the keychain refuses a rotated secret", async () => {
		const { core, storage, refreshes } = harness();
		await core.begin(INSTANCE, { ...issued("s1"), accessTokenExpiresAt: NOW });
		storage.failWrites = true;

		const token = core.accessToken();
		await settle();
		refreshes[0]?.answer.open({ kind: "rotated", tokens: issued("s2") });

		await expect(token).resolves.toMatchObject({ token: "access-s2" });
		expect(core.getState()).toMatchObject({ status: "signedIn" });
	});
});

describe("sign-out when storage fails", () => {
	it("still removes the session and presents the secret when the queue cannot be written", async () => {
		const { core, storage, logouts } = harness();
		await core.begin(INSTANCE, issued("s1"));
		storage.failWrites = true;

		const signingOut = core.signOut();
		await settle();

		expect(core.getState()).toStrictEqual({ status: "signedOut" });
		expect(storage.session()).toBeUndefined();
		expect(logouts.map((logout) => logout.secret)).toStrictEqual(["s1"]);
		logouts[0]?.answer.open("settled");
		await signingOut;
	});

	it("never restores a session whose secret is waiting to be signed out", async () => {
		const first = harness();
		await first.core.begin(INSTANCE, issued("s1"));
		const stored = first.storage.values.get(SESSION_KEY);
		assert.ok(stored !== undefined);
		const { core, storage } = harness();
		storage.values.set(SESSION_KEY, stored);
		storage.values.set(
			REVOCATIONS_KEY,
			JSON.stringify([
				{ apiBaseUrl: INSTANCE.apiBaseUrl, refreshToken: "s1", expiresAt: NOW + HOUR },
			]),
		);

		await core.restore();

		expect(core.getState()).toStrictEqual({ status: "signedOut" });
		expect(storage.session()).toBeUndefined();
	});
});
