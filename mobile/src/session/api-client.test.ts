import { afterEach, describe, expect, it, vi } from "vitest";

import { createClient, createConfig } from "@/api/client";
import { getCurrentUser } from "@/api/sdk.gen";
import type { ClientOptions } from "@/api/types.gen";

import { ApiError, statusOf, wireApiClient } from "./api-client";
import { type RequestOwner, SessionError, type SessionState } from "./session-core";

const TEAM = {
	apiBaseUrl: "https://team.example.org/api",
	label: "team.example.org",
	webUrl: "https://team.example.org",
};
const OTHER = {
	apiBaseUrl: "https://other.example.org/api",
	label: "other.example.org",
	webUrl: "https://other.example.org",
};

function fakeSession(instance = TEAM) {
	let state: SessionState = {
		status: "signedIn",
		instance,
		nativeSessionId: "7a1f3c5e-9b2d-4f6a-8c1e-3d5f7a9b1c2e",
		consent: "unknown",
		epoch: 1,
	};
	const listeners = new Set<() => void>();
	const unauthorized = vi.fn<(owner: RequestOwner) => Promise<void>>(async () => undefined);
	const consent = vi.fn<(owner: RequestOwner, consent: "required" | "complete") => void>();
	return {
		unauthorized,
		consent,
		token: "access-1",
		renewal: undefined as SessionError | undefined,
		getState: () => state,
		subscribe: (listener: () => void) => {
			listeners.add(listener);
			return () => {
				listeners.delete(listener);
			};
		},
		async accessToken() {
			if (this.renewal !== undefined) {
				throw this.renewal;
			}
			return state.status === "signedIn"
				? {
						token: this.token,
						owner: { epoch: state.epoch, apiBaseUrl: state.instance.apiBaseUrl },
					}
				: undefined;
		},
		switchTo(next: SessionState) {
			state = next;
			for (const listener of listeners) {
				listener();
			}
		},
	};
}

function recordingFetch(status = 200) {
	const requests: Request[] = [];
	const fetch: typeof globalThis.fetch = async (input) => {
		if (!(input instanceof Request)) {
			throw new TypeError("the generated client sends a Request");
		}
		requests.push(input);
		return new Response(
			status === 200 ? JSON.stringify({ username: "nora" }) : JSON.stringify({ detail: "no" }),
			{
				status,
				headers: { "Content-Type": "application/json" },
			},
		);
	};
	return { fetch, requests };
}

afterEach(() => {
	vi.restoreAllMocks();
});

describe("wireApiClient", () => {
	it("addresses requests to the signed-in instance with its bearer token and no cookies", async () => {
		const session = fakeSession();
		const client = createClient(createConfig<ClientOptions>());
		wireApiClient(client, session, "Hephaestus/test");
		const { fetch, requests } = recordingFetch();

		await getCurrentUser({ client, fetch });

		expect(requests[0]?.url).toBe("https://team.example.org/api/user");
		expect(requests[0]?.headers.get("Authorization")).toBe("Bearer access-1");
		expect(requests[0]?.credentials).toBe("omit");
	});

	it("follows the session to another instance the moment it changes", async () => {
		const session = fakeSession();
		const client = createClient(createConfig<ClientOptions>());
		wireApiClient(client, session, "Hephaestus/test");
		const { fetch, requests } = recordingFetch();

		session.switchTo({
			status: "signedIn",
			instance: OTHER,
			nativeSessionId: "2c4e6a8b-1d3f-4a5c-9e7b-6f8d0a2c4e6a",
			consent: "unknown",
			epoch: 2,
		});
		await getCurrentUser({ client, fetch });

		expect(requests[0]?.url).toBe("https://other.example.org/api/user");
	});

	it("never sends a session's token to another instance", async () => {
		const session = fakeSession();
		const client = createClient(createConfig<ClientOptions>());
		wireApiClient(client, session, "Hephaestus/test");
		const { fetch, requests } = recordingFetch();

		await expect(
			getCurrentUser({
				client,
				fetch,
				baseUrl: "https://evil.example.org/api",
				throwOnError: true,
			}),
		).rejects.toThrow("another Hephaestus");
		expect(requests).toHaveLength(0);
	});

	it("reports a 401 to the session the request was sent for", async () => {
		const session = fakeSession();
		const client = createClient(createConfig<ClientOptions>());
		wireApiClient(client, session, "Hephaestus/test");
		const { fetch } = recordingFetch(401);

		const answer = await getCurrentUser({ client, fetch, throwOnError: true }).catch(
			(error: unknown) => error,
		);

		expect(session.unauthorized).toHaveBeenCalledWith({ epoch: 1, apiBaseUrl: TEAM.apiBaseUrl });
		expect(statusOf(answer)).toBe(401);
		expect(answer).toBeInstanceOf(ApiError);
	});

	it("marks consent required on a 428 for that session only", async () => {
		const session = fakeSession();
		const client = createClient(createConfig<ClientOptions>());
		wireApiClient(client, session, "Hephaestus/test");
		const { fetch } = recordingFetch(428);

		await getCurrentUser({ client, fetch }).catch(() => undefined);

		expect(session.consent).toHaveBeenCalledWith(
			{ epoch: 1, apiBaseUrl: TEAM.apiBaseUrl },
			"required",
		);
	});

	it("does not send a request it has no token for, and says why", async () => {
		const session = fakeSession();
		const client = createClient(createConfig<ClientOptions>());
		wireApiClient(client, session, "Hephaestus/test");
		const { fetch, requests } = recordingFetch();
		session.renewal = new SessionError("unanswered", "the refresh did not get an answer");

		const answer = await getCurrentUser({ client, fetch, throwOnError: true }).catch(
			(error: unknown) => error,
		);

		expect(requests).toHaveLength(0);
		expect(answer).toBeInstanceOf(ApiError);
		expect(answer).toHaveProperty("message", expect.stringContaining("could not be reached"));
		expect(session.unauthorized).not.toHaveBeenCalled();
	});

	it("does not send a request once the session has ended", async () => {
		const session = fakeSession();
		const client = createClient(createConfig<ClientOptions>());
		wireApiClient(client, session, "Hephaestus/test");
		const { fetch, requests } = recordingFetch();
		session.renewal = new SessionError("ended", "the session ended");

		await expect(getCurrentUser({ client, fetch, throwOnError: true })).rejects.toThrow(
			"Sign in again",
		);
		expect(requests).toHaveLength(0);
	});
});
