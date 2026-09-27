import { once } from "node:events";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";

import { afterEach, describe, expect, it, vi } from "vitest";

import { AuthenticatedApi, publicApi } from "~/background/api";
import {
	type Credentials,
	type Issuer,
	type SessionRecord,
	SessionStore,
} from "~/background/session";
import { required } from "~/testing/required";

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

function family(issuer: Issuer, suffix: string, accessExpiresInMs = 60 * 60_000): Credentials {
	return {
		issuer,
		tokens: {
			accessToken: `access-${suffix}`,
			accessTokenExpiresAt: new Date(NOW + accessExpiresInMs).toISOString(),
			refreshToken: `refresh-${suffix}`,
			sessionExpiresAt: new Date(NOW + 7 * 24 * 60 * 60_000).toISOString(),
		},
	};
}

interface Sent {
	host: string;
	path: string;
	redirect: RequestRedirect;
	/** The authorisation header and the body: where a credential would travel. */
	carries: string;
}

/** A store wired to the real client, as the worker wires it. */
function worker(initial: SessionRecord) {
	let record = initial;
	const store = new SessionStore({
		storage: {
			read: async () => structuredClone(record),
			write: async (next) => {
				record = structuredClone(next);
			},
		},
		refresh: async (issuer, refreshToken) => publicApi.refresh(issuer.apiBase, refreshToken),
		revoke: async (issuer, refreshToken) => publicApi.revoke(issuer.apiBase, refreshToken),
		now: () => NOW,
		onChange: () => undefined,
	});
	return { store, current: () => record };
}

function json(body: unknown, status = 200): Response {
	return Response.json(body, { status });
}

function recordFetch(respond: (request: Request) => Promise<Response>) {
	const sent: Sent[] = [];
	vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
		const request = new Request(input, init);
		const url = new URL(request.url);
		const body = request.method === "GET" ? "" : await request.clone().text();
		sent.push({
			host: url.host,
			path: url.pathname,
			redirect: request.redirect,
			carries: `${request.headers.get("Authorization") ?? ""} ${body}`,
		});
		return respond(request);
	});
	return sent;
}

/** An instance whose refresh answers when the test says, whose logout succeeds, and nothing else. */
function refreshHeldBy(refresh: Promise<Response>) {
	return async (request: Request): Promise<Response> => {
		const { pathname } = new URL(request.url);
		if (pathname.endsWith("/auth/client/refresh")) {
			return refresh;
		}
		if (pathname.endsWith("/auth/client/logout")) {
			return new Response(null, { status: 204 });
		}
		return json([]);
	};
}

/** A real server that redirects a refresh elsewhere, recording every path it was asked for. */
function redirectingRefresh(received: string[]) {
	return (request: IncomingMessage, response: ServerResponse) => {
		received.push(request.url ?? "");
		if (request.url === "/api/auth/client/refresh") {
			response.writeHead(307, { Location: "/elsewhere" });
			response.end();
			return;
		}
		response.writeHead(200, { "Content-Type": "application/json" });
		response.end("{}");
	};
}

function portOf(address: ReturnType<Server["address"]>): number {
	return typeof address === "object" && address !== null ? address.port : 0;
}

afterEach(() => {
	vi.unstubAllGlobals();
});

describe("credential lineage across an instance change", () => {
	it("sends every credential of A's family to A only, even when A's refresh lands after B", async () => {
		const { store, current } = worker({ generation: 1, epoch: 1, credentials: family(A, "a", 0) });
		const refreshReply = Promise.withResolvers<Response>();
		const sent = recordFetch(refreshHeldBy(refreshReply.promise));
		const api = AuthenticatedApi.fromSnapshot(await store.snapshot(), store);
		const pending = api?.workspaces();
		await vi.waitFor(() => {
			expect(sent.map((request) => request.path)).toContain("/api/auth/client/refresh");
		});
		// The user switches to B and signs in there while A's refresh is out.
		await store.signOut();
		await store.adopt(family(B, "b"), current().epoch);
		refreshReply.resolve(json(family(A, "a2").tokens));
		await expect(pending).rejects.toMatchObject({ code: "signed-out" });

		const carryingA = sent.filter((request) => /(?:access|refresh)-a2?\b/u.test(request.carries));
		expect(carryingA.map((request) => [request.host, request.path])).toStrictEqual([
			["a.example.test", "/api/auth/client/refresh"],
			["a.example.test", "/api/auth/client/logout"],
			["a.example.test", "/api/auth/client/logout"],
		]);
		// B's family was never sent anywhere by work that began under A, and is still stored.
		expect(sent.some((request) => request.carries.includes("-b"))).toBe(false);
		expect(current().credentials).toStrictEqual(family(B, "b"));
	});

	it("never pairs an API built for A with B's token", async () => {
		const { store, current } = worker({ generation: 1, epoch: 1, credentials: family(A, "a") });
		const sent = recordFetch(async () => json([]));
		// The instance and the credentials were read together — for A…
		const api = AuthenticatedApi.fromSnapshot(await store.snapshot(), store);
		// …and before the call goes out the store has moved to B.
		await store.signOut();
		await store.adopt(family(B, "b"), current().epoch);
		const revokesOfA = sent.length;
		await expect(api?.workspaces()).rejects.toMatchObject({ code: "stale" });
		expect(sent.slice(revokesOfA)).toStrictEqual([]);
	});

	it("goes to the issuer of the stored family, not to a newer configuration", async () => {
		const { store } = worker({ generation: 1, epoch: 1, credentials: family(A, "a") });
		const sent = recordFetch(async () => json([]));
		const api = AuthenticatedApi.fromSnapshot(await store.snapshot(), store);
		await api?.workspaces();
		expect(sent.map((request) => [request.host, request.carries.trim()])).toStrictEqual([
			["a.example.test", "Bearer access-a"],
		]);
	});
});

describe("redirects", () => {
	it("marks every API request as not following redirects", async () => {
		const sent = recordFetch(async () => json({ registered: true }));
		await publicApi.isRegistered(A.apiBase, "id");
		expect(sent.map((request) => request.redirect)).toStrictEqual(["error"]);
	});

	it("does not re-send a refresh secret to where a 307 points", async () => {
		const received: string[] = [];
		const server = createServer(redirectingRefresh(received));
		server.listen(0, "127.0.0.1");
		await once(server, "listening");
		const port = portOf(server.address());
		try {
			await expect(
				publicApi.refresh(`http://127.0.0.1:${port}/api`, "refresh-secret"),
			).resolves.toStrictEqual({ status: "unavailable" });
			expect(received).toStrictEqual(["/api/auth/client/refresh"]);
		} finally {
			server.close();
		}
	});
});

describe("the report's calls", () => {
	async function api(respond: (request: Request) => Promise<Response>) {
		const { store } = worker({ generation: 1, epoch: 1, credentials: family(A, "a") });
		const requests: Request[] = [];
		recordFetch(async (request) => {
			requests.push(request);
			return respond(request);
		});
		const built = AuthenticatedApi.fromSnapshot(await store.snapshot(), store);
		if (built === undefined) {
			throw new Error("No session");
		}
		return { built, requests };
	}

	it("asks for the reader's own observations on one exact piece of work", async () => {
		const { built, requests } = await api(async () => json({ content: [], totalElements: 0 }));
		await built.ownObservations("team", "scm.pull_request", 16, 25);
		const url = new URL(required(requests[0], "the request").url);
		expect(url.pathname).toBe("/api/workspaces/team/practices/observations");
		expect(Object.fromEntries(url.searchParams)).toStrictEqual({
			artifactKind: "scm.pull_request",
			artifactId: "16",
			displayableOnly: "true",
			sort: "SEVERITY",
			size: "25",
		});
	});

	it("posts one review request, and returns a refusal as the server's answer", async () => {
		const { built, requests } = await api(async () =>
			json({ status: "REFUSED", reason: "COOLDOWN_ACTIVE", reasonDescription: "Too soon." }),
		);
		await expect(built.requestReview("team", "scm.pull_request", 16)).resolves.toMatchObject({
			status: "REFUSED",
			reasonDescription: "Too soon.",
		});
		expect(requests).toHaveLength(1);
		expect(requests[0]?.method).toBe("POST");
		await expect(requests[0]?.json()).resolves.toStrictEqual({
			artifactKind: "scm.pull_request",
			artifactId: 16,
		});
	});

	it("says a forbidden request as such, not as a server failure", async () => {
		const forbidden = await api(async () => json({ title: "Forbidden" }, 403));
		await expect(forbidden.built.requestReview("team", "scm.issue", 1)).rejects.toMatchObject({
			code: "forbidden",
		});
	});

	it("asks for the reader's own comments on the work by its address, with the session's bearer", async () => {
		const answer = {
			work: { id: "16", kind: "scm.pull_request", label: "#16" },
			hasMore: false,
			feedback: [
				{
					id: "5c1f7a90-1b2c-4d3e-8f90-a1b2c3d4e5f6",
					deliveredAt: "2026-09-26T11:00:00Z",
					practices: [{ slug: "clear-work", name: "Clear work" }],
					placements: [
						{
							id: "6d2a8b01-2c3d-4e5f-9a01-b2c3d4e5f607",
							type: "SUMMARY",
							commentRef: "IC_kwDOA",
							permalink: "https://github.com/octo/app/pull/16#issuecomment-1",
						},
					],
				},
			],
		};
		const { built, requests } = await api(async () => json(answer));
		const work = "https://github.com/octo/app/pull/16";
		await expect(built.workFeedback("team", work)).resolves.toStrictEqual(answer);
		expect(requests).toHaveLength(1);
		const [request] = requests;
		const url = new URL(required(request, "the request").url);
		expect(request?.method).toBe("GET");
		expect(url.origin).toBe(A.origin);
		expect(url.pathname).toBe("/api/workspaces/team/practices/feedback/on-work");
		expect(Object.fromEntries(url.searchParams)).toStrictEqual({ url: work });
		expect(request?.headers.get("Authorization")).toBe("Bearer access-a");
		expect(request?.credentials).toBe("omit");
	});

	it("fails a refused read of the reader's comments rather than answering with none", async () => {
		for (const status of [403, 404]) {
			const { built } = await api(async () => json({ title: "Refused" }, status));
			await expect(
				built.workFeedback("team", "https://github.com/octo/app/pull/16"),
			).rejects.toMatchObject({ code: "server", message: "Hephaestus refused the request." });
		}
	});

	it("never reads the in-app feedback endpoint, which records delivery", async () => {
		const { built, requests } = await api(async () => json({ content: [], totalElements: 0 }));
		await built.ownObservations("team", "scm.pull_request", 16, 25);
		await built.workFeedback("team", "https://github.com/octo/app/pull/16").catch(() => undefined);
		expect(requests.some((request) => request.url.includes("/feedback/in-app"))).toBe(false);
	});
});
