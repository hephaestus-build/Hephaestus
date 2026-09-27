import { z } from "zod";

import type { Instance } from "@/instance/instance-url";

/**
 * The signed-in session of this installation, as a state machine with no React Native in it, so its
 * races can be tested with promises the test controls.
 *
 * Three rules hold it together:
 *
 * - Every transition, and every storage read or write it depends on, runs one at a time through a
 *   single lock. A sign-out never interleaves with a rotation writing the keychain.
 * - Every session has an epoch. Work that started in one epoch — a refresh, a request — acts only if
 *   the epoch is still current when its answer arrives. A late answer from a signed-out session can
 *   never resurrect it or touch the next one.
 * - A refresh is never retried automatically. The server rotates strictly: presenting a secret it has
 *   already replaced ends the session. When an answer is lost, the next attempt decides, and at worst
 *   the person signs in again.
 */

export interface IssuedTokens {
	/** The server's id for this sign-in, the same across every refresh; not a secret. */
	nativeSessionId: string;
	accessToken: string;
	accessTokenExpiresAt: number;
	refreshToken: string;
	sessionExpiresAt: number;
}

/** What one refresh came to. `failed` means no usable answer: the server may or may not have rotated. */
export type RefreshOutcome =
	| { kind: "rotated"; tokens: IssuedTokens }
	| { kind: "ended" }
	| { kind: "failed" };

export interface SessionStorage {
	read: (key: string) => Promise<string | null>;
	write: (key: string, value: string) => Promise<void>;
	remove: (key: string) => Promise<void>;
}

export interface SessionDependencies {
	storage: SessionStorage;
	/** An id that exists once per installation and does not survive an uninstall. */
	installationId: () => string;
	refresh: (instance: Instance, refreshToken: string) => Promise<RefreshOutcome>;
	/** Presents a secret for sign-out; `retry` when the server could not be reached or failed. */
	logout: (apiBaseUrl: string, refreshToken: string) => Promise<"settled" | "retry">;
	now: () => number;
}

export type Consent = "unknown" | "required" | "complete";

export type SessionState =
	| { status: "restoring" }
	| { status: "signedOut"; notice?: SignedOutNotice }
	| {
			status: "signedIn";
			instance: Instance;
			/** Stable for the whole sign-in, across refreshes and relaunches: what push is addressed to. */
			nativeSessionId: string;
			consent: Consent;
			epoch: number;
	  };

/** Why the app is signed out when the person did not ask for it. */
export type SignedOutNotice = "ended" | "reinstalled";

/** The epoch and API a request was built for, so its answer can be matched to the session it belongs to. */
export interface RequestOwner {
	epoch: number;
	apiBaseUrl: string;
}

/** Why a session could not give out a token: it has ended, or a refresh got no usable answer. */
export class SessionError extends Error {
	override name = "SessionError";
	readonly reason: "ended" | "unanswered";

	constructor(reason: "ended" | "unanswered", message: string) {
		super(message);
		this.reason = reason;
	}
}

export const SESSION_KEY = "hephaestus.session.v1";
export const REVOCATIONS_KEY = "hephaestus.revocations.v1";

const persistedSession = z.object({
	installationId: z.string(),
	instance: z.object({ apiBaseUrl: z.url(), label: z.string(), webUrl: z.url() }),
	nativeSessionId: z.uuid(),
	refreshToken: z.string().min(1),
	sessionExpiresAt: z.number(),
});
type PersistedSession = z.infer<typeof persistedSession>;

const pendingRevocations = z.array(
	z.object({ apiBaseUrl: z.url(), refreshToken: z.string().min(1), expiresAt: z.number() }),
);
type PendingRevocation = z.infer<typeof pendingRevocations>[number];

interface Live {
	epoch: number;
	persisted: PersistedSession;
	accessToken?: string;
	accessTokenExpiresAt?: number;
}

/** Renew an access token this close to its expiry. */
const LEEWAY_MS = 60_000;

/** Runs tasks one after another, in the order they asked; a failed task does not stop the next. */
function createLock(): <T>(task: () => Promise<T>) => Promise<T> {
	let tail: Promise<void> = Promise.resolve();
	return async (task) => {
		const previous = tail;
		let release: (() => void) | undefined;
		// oxlint-disable-next-line promise/avoid-new -- the lock needs a promise it resolves itself
		tail = new Promise<void>((resolve) => {
			release = resolve;
		});
		await previous;
		try {
			return await task();
		} finally {
			release?.();
		}
	};
}

/** `task`'s result, or `fallback` when it throws: for storage a transition must not stop on. */
async function attempt<T>(task: () => Promise<T>, fallback: T): Promise<T> {
	try {
		return await task();
	} catch {
		return fallback;
	}
}

function parse<T>(raw: string | null, schema: z.ZodType<T>): T | undefined {
	if (raw === null) {
		return undefined;
	}
	try {
		const result = schema.safeParse(JSON.parse(raw));
		return result.success ? result.data : undefined;
	} catch {
		return undefined;
	}
}

export function createSessionCore(deps: SessionDependencies) {
	const locked = createLock();
	const listeners = new Set<() => void>();
	let state: SessionState = { status: "restoring" };
	let live: Live | undefined;
	let epoch = 0;
	let rotation: { epoch: number; promise: Promise<string> } | undefined;
	// Flushes run one after another, each over the queue as it then is, so a secret queued while one
	// runs is presented by the next rather than waiting for a later launch.
	const flushLocked = createLock();

	function publish(next: SessionState): void {
		state = next;
		for (const listener of listeners) {
			listener();
		}
	}

	async function readRevocations(): Promise<PendingRevocation[]> {
		return parse(await deps.storage.read(REVOCATIONS_KEY), pendingRevocations) ?? [];
	}

	async function writeRevocations(queue: PendingRevocation[]): Promise<void> {
		if (queue.length === 0) {
			await deps.storage.remove(REVOCATIONS_KEY);
		} else {
			await deps.storage.write(REVOCATIONS_KEY, JSON.stringify(queue));
		}
	}

	/** Must run under the lock: the queue is read, extended and written as one step. */
	async function appendRevocation(item: PendingRevocation): Promise<void> {
		const queue = await readRevocations();
		if (!queue.some((queued) => queued.refreshToken === item.refreshToken)) {
			queue.push(item);
		}
		await writeRevocations(queue);
	}

	/**
	 * Must run under the lock. Ends the live session locally: the screen clears before storage is
	 * touched, and each clean-up step is attempted even when another fails. Returns the secret it could
	 * not queue, so the caller presents it to the server at once instead of losing it.
	 */
	async function end(
		notice: SignedOutNotice | undefined,
		revoke: boolean,
	): Promise<PendingRevocation | undefined> {
		const ending = live;
		epoch += 1;
		live = undefined;
		publish(notice === undefined ? { status: "signedOut" } : { status: "signedOut", notice });
		if (ending === undefined) {
			return undefined;
		}
		const revocation = {
			apiBaseUrl: ending.persisted.instance.apiBaseUrl,
			refreshToken: ending.persisted.refreshToken,
			expiresAt: ending.persisted.sessionExpiresAt,
		};
		let unqueued: PendingRevocation | undefined;
		if (revoke) {
			try {
				await appendRevocation(revocation);
			} catch {
				unqueued = revocation;
			}
		}
		try {
			await deps.storage.remove(SESSION_KEY);
		} catch {
			// The next launch refuses to restore a session whose secret waits for sign-out, and one whose
			// secret the server has revoked ends at its first refresh.
		}
		return unqueued;
	}

	async function rotateFor(owner: number): Promise<string> {
		const current = live;
		if (current === undefined || current.epoch !== owner) {
			throw new SessionError("ended", "signed out");
		}
		const outcome = await deps.refresh(current.persisted.instance, current.persisted.refreshToken);
		return locked(async () => {
			if (live === undefined || live.epoch !== owner) {
				// Signed out, or in again, while this ran. A secret it brought back is revocation
				// material for the session that was ended, never a session.
				if (outcome.kind === "rotated") {
					await appendRevocation({
						apiBaseUrl: current.persisted.instance.apiBaseUrl,
						refreshToken: outcome.tokens.refreshToken,
						expiresAt: outcome.tokens.sessionExpiresAt,
					});
				}
				throw new SessionError("ended", "signed out while refreshing");
			}
			if (outcome.kind === "ended") {
				await end("ended", false);
				throw new SessionError("ended", "the session ended");
			}
			if (outcome.kind === "failed") {
				throw new SessionError("unanswered", "the refresh did not get an answer");
			}
			// A refresh renews a sign-in; an answer naming another one is not this session's, and its
			// secret is revoked rather than kept.
			if (outcome.tokens.nativeSessionId !== live.persisted.nativeSessionId) {
				await appendRevocation({
					apiBaseUrl: current.persisted.instance.apiBaseUrl,
					refreshToken: outcome.tokens.refreshToken,
					expiresAt: outcome.tokens.sessionExpiresAt,
				});
				await end("ended", false);
				throw new SessionError("ended", "the refresh answered for another session");
			}
			const persisted: PersistedSession = {
				...live.persisted,
				refreshToken: outcome.tokens.refreshToken,
				sessionExpiresAt: outcome.tokens.sessionExpiresAt,
			};
			live = {
				epoch: owner,
				persisted,
				accessToken: outcome.tokens.accessToken,
				accessTokenExpiresAt: outcome.tokens.accessTokenExpiresAt,
			};
			// The old secret is dead the moment the server answered. If the keychain refuses the new
			// one, this process keeps working with it; the next launch finds the old one and signs in again.
			try {
				await deps.storage.write(SESSION_KEY, JSON.stringify(persisted));
			} catch {
				// Nothing better to do: the session is valid for as long as this process holds it.
			}
			return outcome.tokens.accessToken;
		});
	}

	/** One rotation per epoch at a time; a caller from a newer epoch never joins an older one. */
	async function rotate(): Promise<string> {
		const owner = live?.epoch;
		if (owner === undefined) {
			throw new SessionError("ended", "signed out");
		}
		if (rotation?.epoch !== owner) {
			const promise = rotateFor(owner);
			rotation = { epoch: owner, promise };
			try {
				return await promise;
			} finally {
				if (rotation.promise === promise) {
					rotation = undefined;
				}
			}
		}
		return rotation.promise;
	}

	async function flush(): Promise<void> {
		const pending = await locked(readRevocations);
		const settled = new Set<string>();
		for (const item of pending) {
			if (item.expiresAt <= deps.now()) {
				settled.add(item.refreshToken);
				continue;
			}
			let answer: "settled" | "retry";
			try {
				answer = await deps.logout(item.apiBaseUrl, item.refreshToken);
			} catch {
				answer = "retry";
			}
			if (answer === "settled") {
				settled.add(item.refreshToken);
			}
		}
		if (settled.size > 0) {
			// Reconcile against the queue as it is now: entries added while this ran stay queued.
			await locked(async () => {
				const queue = await readRevocations();
				await writeRevocations(queue.filter((item) => !settled.has(item.refreshToken)));
			});
		}
	}

	/** Presents every queued secret once. */
	const flushRevocations = async (): Promise<void> => flushLocked(flush);

	return {
		getState: (): SessionState => state,

		subscribe(listener: () => void): () => void {
			listeners.add(listener);
			return () => {
				listeners.delete(listener);
			};
		},

		/** Reads the persisted session at launch. Storage that cannot be read signs out without erasing it. */
		async restore(): Promise<void> {
			await locked(async () => {
				let raw: string | null;
				try {
					raw = await deps.storage.read(SESSION_KEY);
				} catch {
					publish({ status: "signedOut" });
					return;
				}
				const persisted = parse(raw, persistedSession);
				if (persisted === undefined) {
					publish({ status: "signedOut" });
					return;
				}
				if (persisted.installationId !== deps.installationId()) {
					await deps.storage.remove(SESSION_KEY);
					publish({ status: "signedOut", notice: "reinstalled" });
					return;
				}
				if (persisted.sessionExpiresAt <= deps.now()) {
					await deps.storage.remove(SESSION_KEY);
					publish({ status: "signedOut", notice: "ended" });
					return;
				}
				// A sign-out that could not remove the stored session still queued its secret: that session
				// was ended by the person and is never resumed.
				const pending = await attempt(readRevocations, []);
				if (pending.some((item) => item.refreshToken === persisted.refreshToken)) {
					await attempt(async () => deps.storage.remove(SESSION_KEY), undefined);
					publish({ status: "signedOut" });
					return;
				}
				epoch += 1;
				live = { epoch, persisted };
				publish({
					status: "signedIn",
					instance: persisted.instance,
					nativeSessionId: persisted.nativeSessionId,
					consent: "unknown",
					epoch,
				});
			});
		},

		/**
		 * Starts a session from a completed sign-in. If the secret cannot be stored, the session is not
		 * started and the secret is queued for revocation instead of being left alive on the server.
		 */
		async begin(instance: Instance, issued: IssuedTokens): Promise<void> {
			await locked(async () => {
				if (live !== undefined) {
					await end(undefined, true);
				}
				const persisted: PersistedSession = {
					installationId: deps.installationId(),
					instance,
					nativeSessionId: issued.nativeSessionId,
					refreshToken: issued.refreshToken,
					sessionExpiresAt: issued.sessionExpiresAt,
				};
				try {
					await deps.storage.write(SESSION_KEY, JSON.stringify(persisted));
				} catch (error) {
					await appendRevocation({
						apiBaseUrl: instance.apiBaseUrl,
						refreshToken: issued.refreshToken,
						expiresAt: issued.sessionExpiresAt,
					});
					throw error;
				}
				epoch += 1;
				live = {
					epoch,
					persisted,
					accessToken: issued.accessToken,
					accessTokenExpiresAt: issued.accessTokenExpiresAt,
				};
				publish({
					status: "signedIn",
					instance,
					nativeSessionId: issued.nativeSessionId,
					consent: "unknown",
					epoch,
				});
			});
		},

		/** The access token for a request, renewed first when it is about to expire. */
		async accessToken(): Promise<{ token: string; owner: RequestOwner } | undefined> {
			const current = live;
			if (current === undefined) {
				return undefined;
			}
			const owner = { epoch: current.epoch, apiBaseUrl: current.persisted.instance.apiBaseUrl };
			if (
				current.accessToken !== undefined &&
				current.accessTokenExpiresAt !== undefined &&
				current.accessTokenExpiresAt - deps.now() > LEEWAY_MS
			) {
				return { token: current.accessToken, owner };
			}
			return { token: await rotate(), owner };
		},

		/** Renews now, for a change an administrator made to this account's roles. */
		async renew(): Promise<void> {
			if (live !== undefined) {
				live = { epoch: live.epoch, persisted: live.persisted };
				await rotate();
			}
		},

		/**
		 * A request answered 401. If it still belongs to the live session, one rotation decides whether
		 * the access token was merely stale or the session ended; an answer from an earlier session is
		 * ignored.
		 */
		async unauthorized(owner: RequestOwner): Promise<void> {
			if (live?.epoch !== owner.epoch) {
				return;
			}
			if (live.accessToken !== undefined) {
				live = { epoch: live.epoch, persisted: live.persisted };
			}
			try {
				await rotate();
			} catch {
				// Either the session ended, which `rotate` already published, or it could not be reached.
			}
		},

		/** A request of this session was refused with 428, or the notice status was read. */
		consent(owner: RequestOwner, consent: Exclude<Consent, "unknown">): void {
			if (state.status === "signedIn" && state.epoch === owner.epoch && state.consent !== consent) {
				publish({ ...state, consent });
			}
		},

		/** The API the live session talks to and its epoch, for tagging a request as it is sent. */
		owner(): RequestOwner | undefined {
			return live === undefined
				? undefined
				: { epoch: live.epoch, apiBaseUrl: live.persisted.instance.apiBaseUrl };
		},

		/**
		 * Signs this installation out: the screen clears at once, the refresh secret is queued, then
		 * presented. Offline, it stays queued until a later launch reaches the server.
		 */
		async signOut(): Promise<void> {
			const unqueued = await locked(async () => end(undefined, true));
			if (unqueued !== undefined) {
				// The queue could not take it, so there is no later: present it now, once.
				try {
					await deps.logout(unqueued.apiBaseUrl, unqueued.refreshToken);
				} catch {
					// Unreachable too. Nothing on this device can revoke it any more; it expires with the session.
				}
			}
			await flushRevocations();
		},

		flushRevocations,
	};
}

export type SessionCore = ReturnType<typeof createSessionCore>;
