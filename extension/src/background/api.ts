import { type Client, createClient, createConfig } from "~/api/client";
import {
	getOwnArtifactTrace,
	getClientSignInConfiguration,
	getConsentStatus,
	getCurrentUser,
	getMemberOnboarding,
	getOwnDeliveredWorkFeedback,
	getThread,
	getWorkspace,
	listIdentityProviders,
	listObservations,
	listReviewedWorkReviewRuns,
	listWorkspaces,
	logoutClientSession,
	refreshClientSession,
	requestPracticeReview,
	resolveReviewContext,
} from "~/api/sdk.gen";
import type {
	ArtifactTrace,
	ChatThreadDetail,
	ConsentStatus,
	CurrentUserView,
	DeliveredWorkFeedback,
	IdentityProviderView,
	PageResponseDtoObservationList,
	ReviewRequestOutcome,
	ReviewContext as ReviewContextDTO,
	Workspace,
	WorkspaceListItem,
	WorkspaceOnboarding,
} from "~/api/types.gen";
import {
	consentRequired,
	forbidden,
	network,
	server,
	signedOut,
	stale,
	WorkerError,
} from "~/background/errors";
import {
	type AccessGrant,
	type Credentials,
	type RefreshOutcome,
	type SessionRecord,
	type SessionStore,
	tokensSchema,
} from "~/background/session";

/**
 * A client per call, never a shared mutable one: the base URL and the bearer belong to the call.
 * `credentials: "omit"` keeps the browser's cookies for the instance out of every request — the
 * server refuses a client-session call that carries its auth cookie. `redirect: "error"` because no
 * API call is ever redirected, and following a 307 would re-send a refresh secret or a PKCE verifier
 * in its body to wherever the redirect points. Interactive sign-in is a navigation inside
 * `launchWebAuthFlow` and keeps its own redirects.
 */
export function clientFor(apiBase: string, token?: string): Client {
	return createClient(
		createConfig({
			baseUrl: apiBase,
			credentials: "omit",
			cache: "no-store",
			redirect: "error",
			headers: token === undefined ? undefined : { Authorization: `Bearer ${token}` },
		}),
	);
}

interface CallResult<T> {
	data?: T;
	error?: unknown;
	response?: Response;
}

/** One mentor turn's response: a stream when the server accepted it, its parsed refusal otherwise. */
export interface MentorTurn {
	status: number;
	contentType: string;
	stream: ReadableStream<Uint8Array> | null;
	error: unknown;
}

/** Awaits one generated call, turning a thrown fetch (offline, DNS, CORS) into a coded error. */
async function send<T>(call: () => Promise<CallResult<T>>): Promise<CallResult<T>> {
	try {
		return await call();
	} catch {
		throw network();
	}
}

function body<T>(result: CallResult<T>): T {
	const status = result.response?.status ?? 0;
	if (status === 0) {
		throw network();
	}
	if (status === 428) {
		throw consentRequired();
	}
	if (status < 200 || status >= 300 || result.data === undefined) {
		throw server(status);
	}
	return result.data;
}

/** Like `body`, but a 404 is an answer: the caller decides what "not there" means. */
function bodyOrMissing<T>(result: CallResult<T>): T | undefined {
	return result.response?.status === 404 ? undefined : body(result);
}

/** Calls that need no session: discovery and the client-session lifecycle itself. */
export const publicApi = {
	async identityProviders(apiBase: string): Promise<IdentityProviderView[]> {
		return body(await send(async () => listIdentityProviders({ client: clientFor(apiBase) })));
	},

	async isRegistered(apiBase: string, clientId: string): Promise<boolean> {
		const result = await send(async () =>
			getClientSignInConfiguration({ client: clientFor(apiBase), query: { clientId } }),
		);
		return body(result).registered;
	},

	async refresh(apiBase: string, refreshToken: string): Promise<RefreshOutcome> {
		let result: CallResult<unknown>;
		try {
			result = await refreshClientSession({ client: clientFor(apiBase), body: { refreshToken } });
		} catch {
			return { status: "unavailable" };
		}
		const status = result.response?.status ?? 0;
		if (status === 400 || status === 401) {
			return { status: "rejected" };
		}
		const tokens = tokensSchema.safeParse(result.data);
		return tokens.success ? { status: "ok", tokens: tokens.data } : { status: "unavailable" };
	},

	async revoke(apiBase: string, refreshToken: string): Promise<void> {
		try {
			await logoutClientSession({ client: clientFor(apiBase), body: { refreshToken } });
		} catch {
			// Offline sign-out is local: the session ends at its own deadline or on the next reuse.
		}
	},
};

/**
 * The signed-in half, built from one session snapshot: the base URL is the issuer the stored family
 * came from, never the instance configured at the moment of the call, so a bearer can only ever go to
 * the server that issued it. Every call reads the token under the generation the API was built with,
 * retries once after a 401 with a refreshed token, and checks the generation again before handing
 * anything back — a result that outlived a sign-out, an instance change or revoked site access is never
 * returned.
 */
export class AuthenticatedApi {
	readonly #apiBase: string;
	readonly #session: SessionStore;
	readonly #generation: number;

	private constructor(credentials: Credentials, session: SessionStore, generation: number) {
		this.#apiBase = credentials.issuer.apiBase;
		this.#session = session;
		this.#generation = generation;
	}

	/** `undefined` when the snapshot holds no credentials: nobody is signed in. */
	static fromSnapshot(
		snapshot: SessionRecord,
		session: SessionStore,
	): AuthenticatedApi | undefined {
		return snapshot.credentials === undefined
			? undefined
			: new AuthenticatedApi(snapshot.credentials, session, snapshot.generation);
	}

	get generation(): number {
		return this.#generation;
	}

	/** A grant this API may present: same view generation, same issuer. */
	#check(grant: AccessGrant | undefined): AccessGrant {
		if (grant === undefined) {
			throw signedOut();
		}
		if (grant.generation !== this.#generation || grant.issuer.apiBase !== this.#apiBase) {
			throw stale();
		}
		return grant;
	}

	async #call<T>(call: (client: Client) => Promise<CallResult<T>>): Promise<CallResult<T>> {
		const first = this.#check(await this.#session.access());
		let result = await send(async () => call(clientFor(this.#apiBase, first.token)));
		if (result.response?.status === 401) {
			const refreshed = this.#check(await this.#session.refresh(first));
			result = await send(async () => call(clientFor(this.#apiBase, refreshed.token)));
			if (result.response?.status === 401) {
				await this.#session.expire(refreshed);
				throw signedOut();
			}
		}
		await this.assertCurrent();
		return result;
	}

	/** Throws `stale` when anything a view may be showing has changed since this API was made. */
	async assertCurrent(): Promise<void> {
		if ((await this.#session.generation()) !== this.#generation) {
			throw stale();
		}
	}

	async currentUser(): Promise<CurrentUserView> {
		return body(await this.#call(async (client) => getCurrentUser({ client })));
	}

	async consent(): Promise<ConsentStatus> {
		return body(await this.#call(async (client) => getConsentStatus({ client })));
	}

	async workspaces(): Promise<WorkspaceListItem[]> {
		return body(await this.#call(async (client) => listWorkspaces({ client })));
	}

	async workspace(workspaceSlug: string): Promise<Workspace | undefined> {
		return bodyOrMissing(
			await this.#call(async (client) => getWorkspace({ client, path: { workspaceSlug } })),
		);
	}

	/** `undefined` is the server's uniform "not work you can see here". */
	async resolve(workspaceSlug: string, url: string): Promise<ReviewContextDTO | undefined> {
		const result = await this.#call(async (client) =>
			resolveReviewContext({ client, path: { workspaceSlug }, query: { url } }),
		);
		const status = result.response?.status;
		// 400 is an unsupported or malformed page; to the reader it is the same "not here".
		if (status === 404 || status === 400 || status === 403) {
			return undefined;
		}
		return body(result);
	}

	/** A trace of the reader's latest visible review; missing access says nothing about other reviews. */
	async trace(workspaceSlug: string, artifactKind: string, artifactId: number) {
		const runs = body(
			await this.#call(async (client) =>
				listReviewedWorkReviewRuns({
					client,
					path: { workspaceSlug, artifactKind, artifactId },
					query: { size: 1 },
				}),
			),
		);
		const run = runs.content[0];
		if (run === undefined) {
			return null;
		}
		if (run.reviewedWork.kind !== artifactKind || run.reviewedWork.id !== String(artifactId)) {
			throw new WorkerError(
				"server",
				"Hephaestus returned records about different work. Open the work in Hephaestus instead.",
			);
		}
		const trace: ArtifactTrace | undefined = bodyOrMissing(
			await this.#call(async (client) =>
				getOwnArtifactTrace({
					client,
					path: { workspaceSlug, artifactKind, artifactId },
					query: { reviewId: run.reviewId },
				}),
			),
		);
		if (
			trace !== undefined &&
			(trace.artifactKind !== artifactKind || trace.artifactId !== artifactId)
		) {
			throw new WorkerError(
				"server",
				"Hephaestus returned records about different work. Open the work in Hephaestus instead.",
			);
		}
		return trace ?? null;
	}

	/**
	 * The reader's own observations on one piece of work, most severe first. Not-applicable rows are
	 * left out: they say the practice had nothing to judge here, which the web app's trace explains.
	 */
	async ownObservations(
		workspaceSlug: string,
		artifactKind: string,
		artifactId: number,
		size: number,
	): Promise<PageResponseDtoObservationList> {
		return body(
			await this.#call(async (client) =>
				listObservations({
					client,
					path: { workspaceSlug },
					query: { artifactKind, artifactId, displayableOnly: true, sort: "SEVERITY", size },
				}),
			),
		);
	}

	/**
	 * The comments Hephaestus recorded posting for the signed-in account on the work at `workUrl`, which
	 * the server resolves in the workspace exactly as it resolves the review context.
	 */
	async workFeedback(workspaceSlug: string, workUrl: string): Promise<DeliveredWorkFeedback> {
		return body(
			await this.#call(async (client) =>
				getOwnDeliveredWorkFeedback({ client, path: { workspaceSlug }, query: { url: workUrl } }),
			),
		);
	}

	/** The reader's AI choice and the workspace's model readiness, which decide whether Heph answers. */
	async onboarding(workspaceSlug: string): Promise<WorkspaceOnboarding> {
		return body(
			await this.#call(async (client) => getMemberOnboarding({ client, path: { workspaceSlug } })),
		);
	}

	/** One of the reader's own conversations; the server answers 404 for anyone else's. */
	async thread(workspaceSlug: string, threadId: string): Promise<ChatThreadDetail | undefined> {
		return bodyOrMissing(
			await this.#call(async (client) => getThread({ client, path: { workspaceSlug, threadId } })),
		);
	}

	/**
	 * Sends one mentor turn through the generated client and hands back its body unread, so it streams.
	 * The endpoint is the workspace's mentor chat, hidden from the published spec because it speaks the
	 * AI SDK's stream protocol rather than JSON; the address is fixed here and the body is the panel's,
	 * validated. Like every call, it retries once with a refreshed token after a 401 — the server starts
	 * no turn on a 401 — and is refused once the generation it was built under has passed.
	 */
	async mentorTurn(workspaceSlug: string, turn: object, signal: AbortSignal): Promise<MentorTurn> {
		const result = await this.#call(async (client) =>
			client.post<{ 200: ReadableStream<Uint8Array> | null }>({
				url: "/workspaces/{workspaceSlug}/mentor/chat",
				path: { workspaceSlug },
				body: turn,
				headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
				parseAs: "stream",
				signal,
			}),
		);
		const { response } = result;
		if (response === undefined) {
			if (signal.aborted) {
				throw new WorkerError("cancelled", "The reply was stopped.");
			}
			throw network();
		}
		return {
			status: response.status,
			contentType: response.headers.get("Content-Type") ?? "",
			stream: response.ok ? (result.data ?? null) : null,
			error: response.ok ? undefined : result.error,
		};
	}

	/**
	 * Asks for a review. A 200 is an answer either way: `SUBMITTED` started one, `REFUSED` names why
	 * not. A refused ask is not an error, and nothing here asks twice.
	 */
	async requestReview(
		workspaceSlug: string,
		artifactKind: string,
		artifactId: number,
	): Promise<ReviewRequestOutcome> {
		const result = await this.#call(async (client) =>
			requestPracticeReview({
				client,
				path: { workspaceSlug },
				body: { artifactKind, artifactId },
			}),
		);
		const status = result.response?.status;
		if (status === 403 || status === 404) {
			throw forbidden("Hephaestus did not let your account ask for a review of this work.");
		}
		return body(result);
	}
}
