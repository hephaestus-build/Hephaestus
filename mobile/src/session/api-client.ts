import type { Client } from "@/api/client";

import { type RequestOwner, type SessionCore, SessionError } from "./session-core";

/** A non-2xx answer, carrying its status so screens can tell "not allowed" from "try again". */
export class ApiError extends Error {
	override name = "ApiError";
	readonly status: number;

	constructor(status: number, message: string) {
		super(message);
		this.status = status;
	}
}

/** The status of a failed generated call, for retry and empty-state decisions. */
export function statusOf(error: unknown): number | undefined {
	return error instanceof ApiError ? error.status : undefined;
}

const SIGNED_OUT = "Your session has ended. Sign in again.";

type WiredSession = Pick<
	SessionCore,
	"accessToken" | "consent" | "getState" | "subscribe" | "unauthorized"
>;

/**
 * Binds a generated client to the session.
 *
 * The client's base URL follows the session synchronously, at the moment it changes, so no query of a
 * new session is ever built against the previous instance. Every request carries a fresh bearer token
 * and no cookies — and only if it is addressed to the API of the session that token belongs to. A
 * request no token can be found for is not sent, and fails with why. A 401
 * or 428 is applied to the session the request was sent for, and ignored if that session has ended.
 */
export function wireApiClient(client: Client, session: WiredSession, userAgent: string): void {
	const owners = new WeakMap<Request, RequestOwner>();
	const configure = () => {
		const state = session.getState();
		client.setConfig({
			credentials: "omit",
			baseUrl: state.status === "signedIn" ? state.instance.apiBaseUrl : "",
		});
	};
	configure();
	session.subscribe(configure);

	// oxlint-disable-next-line oxc/no-async-endpoint-handlers -- a fetch interceptor, not an Express handler
	client.interceptors.request.use(async (request) => {
		request.headers.set("User-Agent", userAgent);
		// Without a token the request is not sent: unauthenticated, it could only come back 401.
		const auth = await session.accessToken().catch((error: unknown) => {
			throw new ApiError(
				0,
				error instanceof SessionError && error.reason === "ended"
					? SIGNED_OUT
					: "Hephaestus could not be reached to renew your session. Check your connection.",
			);
		});
		if (auth === undefined) {
			throw new ApiError(0, SIGNED_OUT);
		}
		if (!request.url.startsWith(`${auth.owner.apiBaseUrl}/`)) {
			throw new ApiError(
				0,
				"The request was addressed to another Hephaestus than the one signed in",
			);
		}
		request.headers.set("Authorization", `Bearer ${auth.token}`);
		owners.set(request, auth.owner);
		return request;
	});
	client.interceptors.response.use(async (response, request) => {
		const owner = owners.get(request);
		if (owner !== undefined && response.status === 401) {
			await session.unauthorized(owner);
		} else if (owner !== undefined && response.status === 428) {
			session.consent(owner, "required");
		}
		return response;
	});
	client.interceptors.error.use((body, response) => {
		if (body instanceof ApiError) {
			return body;
		}
		const status = response?.status ?? 0;
		const detail =
			typeof body === "object" &&
			body !== null &&
			"detail" in body &&
			typeof body.detail === "string"
				? body.detail
				: `Request failed with ${status}`;
		return new ApiError(status, detail);
	});
}
