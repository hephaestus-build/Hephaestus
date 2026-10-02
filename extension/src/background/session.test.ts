import { describe, expect, it, vi } from "vitest";

import {
	type Credentials,
	type Issuer,
	type RefreshOutcome,
	type SessionRecord,
	SessionStore,
	type Tokens,
} from "~/background/session";

const NOW = Date.parse("2026-09-26T12:00:00Z");

const A: Issuer = {
	origin: "https://a.example.test",
	apiBase: "https://a.example.test/api",
	webAppOrigin: "https://a.example.test",
};
const B: Issuer = {
	origin: "https://b.example.test",
	apiBase: "https://b.example.test/api",
	webAppOrigin: "https://b.example.test",
};

function tokens(suffix: string, accessExpiresInMs = 60 * 60_000): Tokens {
	return {
		accessToken: `access-${suffix}`,
		accessTokenExpiresAt: new Date(NOW + accessExpiresInMs).toISOString(),
		refreshToken: `refresh-${suffix}`,
		sessionExpiresAt: new Date(NOW + 7 * 24 * 60 * 60_000).toISOString(),
	};
}

function family(issuer: Issuer, suffix: string, accessExpiresInMs?: number): Credentials {
	return { issuer, tokens: tokens(suffix, accessExpiresInMs) };
}

function harness(initial: SessionRecord) {
	let record = initial;
	const refresh = vi.fn<(issuer: Issuer, refreshToken: string) => Promise<RefreshOutcome>>();
	const revoke = vi
		.fn<(issuer: Issuer, refreshToken: string) => Promise<void>>()
		.mockResolvedValue(undefined);
	const onChange = vi.fn<(generation: number) => void>();
	const store = new SessionStore({
		storage: {
			read: async () => structuredClone(record),
			write: async (next) => {
				record = structuredClone(next);
			},
		},
		refresh,
		revoke,
		now: () => NOW,
		onChange,
	});
	return { store, refresh, revoke, onChange, current: () => record };
}

/** Holds the next refresh until the test releases it. */
function holdRefresh(refresh: ReturnType<typeof harness>["refresh"]) {
	const reply = Promise.withResolvers<RefreshOutcome>();
	refresh.mockReturnValueOnce(reply.promise);
	return reply;
}

describe("SessionStore", () => {
	it("hands out the stored token with its issuer while it is fresh", async () => {
		const { store, refresh } = harness({ generation: 3, epoch: 2, credentials: family(A, "a") });
		await expect(store.access()).resolves.toStrictEqual({
			token: "access-a",
			issuer: A,
			generation: 3,
			epoch: 2,
		});
		expect(refresh).not.toHaveBeenCalled();
	});

	it("refreshes once for any number of concurrent callers, at the family's issuer", async () => {
		const { store, refresh, current } = harness({
			generation: 1,
			epoch: 1,
			credentials: family(A, "a", 1000),
		});
		const reply = holdRefresh(refresh);
		const callers = [store.access(), store.access(), store.access()];
		reply.resolve({ status: "ok", tokens: tokens("b") });
		const grants = await Promise.all(callers);
		expect(refresh.mock.calls).toStrictEqual([[A, "refresh-a"]]);
		expect(grants.map((grant) => grant?.token)).toStrictEqual(["access-b", "access-b", "access-b"]);
		expect(current().credentials).toStrictEqual(family(A, "b"));
	});

	it("gives a caller holding a replaced token the replacement instead of refreshing again", async () => {
		const { store, refresh } = harness({ generation: 1, epoch: 1, credentials: family(A, "b") });
		await expect(
			store.refresh({ token: "access-a", issuer: A, generation: 1, epoch: 1 }),
		).resolves.toMatchObject({ token: "access-b" });
		expect(refresh).not.toHaveBeenCalled();
	});

	it("keeps a rotation that lands after a view change, and drops only the old view", async () => {
		const { store, refresh, revoke, current } = harness({
			generation: 5,
			epoch: 2,
			credentials: family(A, "before", 0),
		});
		const reply = holdRefresh(refresh);
		const pending = store.access();
		await vi.waitFor(() => {
			expect(refresh).toHaveBeenCalledOnce();
		});
		// Site access is revoked while the refresh is out.
		await store.bump();
		reply.resolve({ status: "ok", tokens: tokens("after") });
		// The grant carries the new generation, so a result for the old view is dropped by the caller.
		await expect(pending).resolves.toMatchObject({
			token: "access-after",
			generation: 6,
			epoch: 2,
		});
		expect(refresh).toHaveBeenCalledOnce();
		expect(revoke).not.toHaveBeenCalled();
		expect(current()).toStrictEqual({ generation: 6, epoch: 2, credentials: family(A, "after") });
		await expect(store.access()).resolves.toMatchObject({ token: "access-after", generation: 6 });
		expect(refresh).toHaveBeenCalledOnce();
	});

	it("clears the current family when its refresh is rejected after a view change", async () => {
		const { store, refresh, onChange, current } = harness({
			generation: 5,
			epoch: 2,
			credentials: family(A, "before", 0),
		});
		const reply = holdRefresh(refresh);
		const pending = store.access();
		await vi.waitFor(() => {
			expect(refresh).toHaveBeenCalledOnce();
		});
		await store.bump();
		reply.resolve({ status: "rejected" });
		await expect(pending).resolves.toBeUndefined();
		expect(current()).toStrictEqual({ generation: 7, epoch: 3 });
		expect(onChange).toHaveBeenLastCalledWith(7);
	});

	it("revokes, and never adopts, a rotation that lands after sign-out", async () => {
		const { store, refresh, revoke, current } = harness({
			generation: 1,
			epoch: 1,
			credentials: family(A, "a", 0),
		});
		const reply = holdRefresh(refresh);
		const pending = store.access();
		await vi.waitFor(() => {
			expect(refresh).toHaveBeenCalledOnce();
		});
		await store.signOut();
		reply.resolve({ status: "ok", tokens: tokens("late") });
		await expect(pending).resolves.toBeUndefined();
		expect(current()).toStrictEqual({ generation: 2, epoch: 2 });
		expect(revoke.mock.calls).toStrictEqual([
			[A, "refresh-a"],
			[A, "refresh-late"],
		]);
	});

	it("keeps the session when the refresh could not reach the issuer", async () => {
		const { store, refresh, current } = harness({
			generation: 4,
			epoch: 1,
			credentials: family(A, "a", 0),
		});
		refresh.mockResolvedValue({ status: "unavailable" });
		await expect(store.access()).rejects.toMatchObject({ code: "network" });
		expect(current().credentials).toStrictEqual(family(A, "a", 0));
	});

	it("clears and announces sign-out before the issuer is told", async () => {
		const { store, revoke, onChange, current } = harness({
			generation: 2,
			epoch: 1,
			credentials: family(A, "a"),
		});
		const order: string[] = [];
		const stored: unknown[] = [];
		onChange.mockImplementation(() => {
			order.push("announced");
		});
		revoke.mockImplementation(async () => {
			order.push("revoked");
			stored.push(current().credentials);
		});
		await store.signOut();
		expect(order).toStrictEqual(["announced", "revoked"]);
		// By the time the secret leaves, nothing of it is stored any more.
		expect(stored).toStrictEqual([undefined]);
		expect(revoke).toHaveBeenCalledWith(A, "refresh-a");
	});

	it("adopts a sign-in as a new lineage", async () => {
		const { store, onChange, current } = harness({ generation: 1, epoch: 1 });
		await expect(store.adopt(family(A, "new"), 1)).resolves.toBe(true);
		expect(current()).toStrictEqual({ generation: 2, epoch: 2, credentials: family(A, "new") });
		expect(onChange).toHaveBeenCalledWith(2);
	});

	it("keeps a sign-in whose view changed but whose lineage did not", async () => {
		const { store, current } = harness({ generation: 1, epoch: 1 });
		await store.bump();
		await expect(store.adopt(family(A, "new"), 1)).resolves.toBe(true);
		expect(current().credentials).toStrictEqual(family(A, "new"));
	});

	it("forgets a session past its absolute deadline", async () => {
		const expired = {
			issuer: A,
			tokens: { ...tokens("a"), sessionExpiresAt: new Date(NOW - 1).toISOString() },
		};
		const { store, refresh, current } = harness({ generation: 1, epoch: 1, credentials: expired });
		await expect(store.access()).resolves.toBeUndefined();
		expect(refresh).not.toHaveBeenCalled();
		expect(current()).toStrictEqual({ generation: 2, epoch: 2 });
	});
});

describe("SessionStore across an instance change", () => {
	it("revokes a sign-in to A that finishes after B was adopted — at A, never at B", async () => {
		const { store, revoke, current } = harness({ generation: 1, epoch: 1 });
		// Sign-in to A starts and its exchange is still out…
		const { epoch: startedForA } = await store.snapshot();
		// …while the user switches to B and signs in there.
		await store.signOut();
		const afterSignOut = await store.snapshot();
		await store.adopt(family(B, "b"), afterSignOut.epoch);
		// A's exchange completes late.
		await expect(store.adopt(family(A, "a"), startedForA)).resolves.toBe(false);
		expect(revoke.mock.calls).toStrictEqual([[A, "refresh-a"]]);
		expect(current().credentials).toStrictEqual(family(B, "b"));
	});

	it("sends a late refresh of A's family to A only, and leaves B's family untouched", async () => {
		const { store, refresh, revoke, current } = harness({
			generation: 1,
			epoch: 1,
			credentials: family(A, "a", 0),
		});
		const reply = holdRefresh(refresh);
		const pending = store.access();
		await vi.waitFor(() => {
			expect(refresh).toHaveBeenCalledOnce();
		});
		await store.signOut();
		const afterSignOut = await store.snapshot();
		await store.adopt(family(B, "b"), afterSignOut.epoch);
		reply.resolve({ status: "ok", tokens: tokens("a2") });
		await expect(pending).resolves.toBeUndefined();
		expect(refresh.mock.calls).toStrictEqual([[A, "refresh-a"]]);
		expect(revoke.mock.calls).toStrictEqual([
			[A, "refresh-a"],
			[A, "refresh-a2"],
		]);
		expect(current().credentials).toStrictEqual(family(B, "b"));
	});
});
