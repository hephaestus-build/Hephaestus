import { z } from "zod";

import { network } from "~/background/errors";

export const tokensSchema = z.object({
	accessToken: z.string().min(1),
	accessTokenExpiresAt: z.string(),
	refreshToken: z.string().min(1),
	sessionExpiresAt: z.string(),
});

export type Tokens = z.infer<typeof tokensSchema>;

/** The instance a credential family was issued by; every use of the family goes back to it. */
export const issuerSchema = z.object({
	origin: z.string(),
	apiBase: z.string(),
	webAppOrigin: z.string(),
});

export type Issuer = z.infer<typeof issuerSchema>;

export const credentialsSchema = z.object({ issuer: issuerSchema, tokens: tokensSchema });

export type Credentials = z.infer<typeof credentialsSchema>;

export const sessionRecordSchema = z.object({
	generation: z.number().int().nonnegative(),
	epoch: z.number().int().nonnegative(),
	credentials: credentialsSchema.optional(),
});

/**
 * Two counters, because two different things can go out of date.
 *
 * - `epoch` names the credential lineage: it moves when a family starts or ends — sign-in,
 *   sign-out, a rejected refresh, an expired session. A refresh rotates the secret inside the family
 *   and does not move it.
 * - `generation` names everything a view may be showing: it moves with every epoch change and also
 *   when site access is revoked. A result computed under an older generation is
 *   dropped rather than stored, sent or rendered.
 *
 * Revoking site access therefore drops the old view's results without making a refresh that was
 * in flight look like a sign-out.
 */
export type SessionRecord = z.infer<typeof sessionRecordSchema>;

export const EMPTY_SESSION: SessionRecord = { generation: 0, epoch: 0 };

export interface SessionStorage {
	read: () => Promise<SessionRecord>;
	write: (record: SessionRecord) => Promise<void>;
}

export type RefreshOutcome =
	| { status: "ok"; tokens: Tokens }
	| { status: "rejected" }
	| { status: "unavailable" };

export interface SessionDependencies {
	storage: SessionStorage;
	/** Always called with the family's own issuer, never with whatever instance is configured now. */
	refresh: (issuer: Issuer, refreshToken: string) => Promise<RefreshOutcome>;
	/** Best effort: a revocation that cannot reach the issuer leaves the session to expire. */
	revoke: (issuer: Issuer, refreshToken: string) => Promise<void>;
	now: () => number;
	onChange: (generation: number) => void;
}

/** A token to present, the issuer it may be presented to, and the state it was read under. */
export interface AccessGrant {
	token: string;
	issuer: Issuer;
	generation: number;
	epoch: number;
}

// Refresh a little early, so a token does not expire between being read and being presented.
const EXPIRY_MARGIN_MS = 30_000;

function grantOf(record: SessionRecord, credentials: Credentials): AccessGrant {
	return {
		token: credentials.tokens.accessToken,
		issuer: credentials.issuer,
		generation: record.generation,
		epoch: record.epoch,
	};
}

/**
 * The worker's one copy of the credentials. Every read-modify-write runs under one lock, a refresh is
 * single-flight, and a late refresh or sign-in result is stored only if the family it belongs to is
 * still the stored one — otherwise it is revoked at its own issuer. Rotation is strict on the server,
 * so two concurrent refreshes would end the session; the single flight is what prevents that. A
 * refresh whose response is lost (the worker stopped while it was out) leaves an old secret, and the
 * next refresh ends the session: signing in again is the answer.
 */
export class SessionStore {
	readonly #deps: SessionDependencies;
	#lock: Promise<undefined> = Promise.resolve(undefined);
	#refreshing: Promise<AccessGrant | undefined> | undefined;

	constructor(deps: SessionDependencies) {
		this.#deps = deps;
	}

	async #exclusive<T>(task: () => Promise<T>): Promise<T> {
		const previous = this.#lock;
		const released = Promise.withResolvers<undefined>();
		this.#lock = released.promise;
		try {
			await previous;
			return await task();
		} finally {
			released.resolve(undefined);
		}
	}

	async snapshot(): Promise<SessionRecord> {
		return this.#exclusive(async () => this.#deps.storage.read());
	}

	async generation(): Promise<number> {
		const { generation } = await this.snapshot();
		return generation;
	}

	/** Moves the view generation without touching the credential lineage, and tells every view. */
	async bump(): Promise<number> {
		const next = await this.#exclusive(async () => {
			const record = await this.#deps.storage.read();
			const updated = { ...record, generation: record.generation + 1 };
			await this.#deps.storage.write(updated);
			return updated.generation;
		});
		this.#deps.onChange(next);
		return next;
	}

	/**
	 * Stores the family a sign-in produced, if no lineage change happened since the sign-in began. A
	 * sign-in that lost the race is revoked at once, at the instance that issued it, so no live
	 * session is left behind unreferenced.
	 */
	async adopt(credentials: Credentials, startedAtEpoch: number): Promise<boolean> {
		const adopted = await this.#exclusive(async () => {
			const record = await this.#deps.storage.read();
			if (record.epoch !== startedAtEpoch) {
				return;
			}
			const updated = {
				generation: record.generation + 1,
				epoch: record.epoch + 1,
				credentials,
			};
			await this.#deps.storage.write(updated);
			return updated.generation;
		});
		if (adopted === undefined) {
			await this.#deps.revoke(credentials.issuer, credentials.tokens.refreshToken);
			return false;
		}
		this.#deps.onChange(adopted);
		return true;
	}

	/** A usable access token, refreshing first when it is about to expire. */
	async access(): Promise<AccessGrant | undefined> {
		const record = await this.snapshot();
		const { credentials } = record;
		if (credentials === undefined) {
			return undefined;
		}
		if (Date.parse(credentials.tokens.sessionExpiresAt) <= this.#deps.now()) {
			await this.#end(credentials.tokens.refreshToken);
			return undefined;
		}
		if (Date.parse(credentials.tokens.accessTokenExpiresAt) - EXPIRY_MARGIN_MS > this.#deps.now()) {
			return grantOf(record, credentials);
		}
		return this.refresh(grantOf(record, credentials));
	}

	/**
	 * Replaces `rejected` — a token the server refused or that is about to expire. Concurrent callers
	 * share one refresh; a caller whose token was already replaced gets the replacement.
	 */
	async refresh(rejected: AccessGrant): Promise<AccessGrant | undefined> {
		if (this.#refreshing === undefined) {
			const flight = this.#refreshOnce(rejected);
			this.#refreshing = flight;
			try {
				return await flight;
			} finally {
				this.#refreshing = undefined;
			}
		}
		return this.#refreshing;
	}

	async #refreshOnce(rejected: AccessGrant): Promise<AccessGrant | undefined> {
		const record = await this.snapshot();
		const { credentials } = record;
		if (credentials === undefined || record.epoch !== rejected.epoch) {
			return undefined;
		}
		if (credentials.tokens.accessToken !== rejected.token) {
			return grantOf(record, credentials);
		}
		const presented = credentials.tokens.refreshToken;
		const outcome = await this.#deps.refresh(credentials.issuer, presented);
		if (outcome.status === "unavailable") {
			throw network();
		}
		if (outcome.status === "rejected") {
			await this.#end(presented);
			return undefined;
		}
		const stored = await this.#exclusive(async () => {
			const current = await this.#deps.storage.read();
			// Still the family and the secret that was presented: revoked site access does not change
			// the family, but a sign-out or another sign-in does.
			if (current.credentials?.tokens.refreshToken !== presented) {
				return;
			}
			const updated = {
				...current,
				credentials: { issuer: credentials.issuer, tokens: outcome.tokens },
			};
			await this.#deps.storage.write(updated);
			return grantOf(updated, updated.credentials);
		});
		if (stored === undefined) {
			// The family ended while the refresh was out: the rotated secret is the live one now.
			await this.#deps.revoke(credentials.issuer, outcome.tokens.refreshToken);
		}
		return stored;
	}

	/**
	 * Clears credentials, advances their epoch, and tells every view. Returns the detached family
	 * so callers can publish related local state before revoking it outside their critical section.
	 */
	async detach(): Promise<Credentials | undefined> {
		const ended = await this.#exclusive(async () => {
			const record = await this.#deps.storage.read();
			const updated = { generation: record.generation + 1, epoch: record.epoch + 1 };
			await this.#deps.storage.write(updated);
			return { generation: updated.generation, credentials: record.credentials };
		});
		this.#deps.onChange(ended.generation);
		return ended.credentials;
	}

	/** Ends the local family before attempting remote revocation. */
	async signOut(): Promise<void> {
		const credentials = await this.detach();
		if (credentials !== undefined) {
			await this.#deps.revoke(credentials.issuer, credentials.tokens.refreshToken);
		}
	}

	/**
	 * The issuer no longer honours the family whose secret was `refreshToken`: forget it without
	 * revoking — unless the stored family has already moved on, in which case there is nothing to end.
	 */
	async #end(refreshToken: string): Promise<void> {
		const ended = await this.#exclusive(async () => {
			const record = await this.#deps.storage.read();
			if (record.credentials?.tokens.refreshToken !== refreshToken) {
				return;
			}
			const updated = { generation: record.generation + 1, epoch: record.epoch + 1 };
			await this.#deps.storage.write(updated);
			return updated.generation;
		});
		if (ended !== undefined) {
			this.#deps.onChange(ended);
		}
	}

	/** Used when the issuer answers 401 even to a fresh token. */
	async expire(grant: AccessGrant): Promise<void> {
		const record = await this.snapshot();
		if (record.epoch === grant.epoch && record.credentials !== undefined) {
			await this.#end(record.credentials.tokens.refreshToken);
		}
	}
}
