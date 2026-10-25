import { once } from "node:events";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";

import { afterEach, describe, expect, it, vi } from "vitest";

import type {
	DeliveredWorkFeedback,
	ItemRef,
	OwnItemTrace,
	PageResponseDtoProfileReview,
	ReviewRef,
	ReviewRequestOutcome,
} from "~/api/types.gen";
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

const REVIEW = "0b7c1a52-0d3e-4c55-8a0b-1d2e3f4a5b6c";
const LATER_REVIEW = "7d5e0c3a-1b2c-4d3e-8f40-000000000002";

function itemRef(id: string): ItemRef {
	return {
		id,
		kind: "scm.pull_request",
		parentId: null,
		provider: "GITHUB",
		label: `#${id}`,
		title: "Add login",
		url: `https://github.com/octo/app/pull/${id}`,
		container: "octo/app",
		path: null,
		removedAt: null,
	};
}

function reviewRef(id: string, item: ItemRef): ReviewRef {
	return {
		id,
		kind: "ITEM",
		item,
		itemVersion: "abc",
		origin: "EVENT",
		status: "COMPLETE",
		createdAt: "2026-09-26T11:00:00Z",
		completedAt: "2026-09-26T11:05:00Z",
	};
}

function ownTrace(review: ReviewRef, item = review.item): OwnItemTrace {
	return { item, review, events: [], results: [] };
}

function ownReviews(...reviews: ReviewRef[]): PageResponseDtoProfileReview {
	const content = reviews.map((review) => ({
		review,
		results: [],
		feedback: [],
		positionOnWork: 1,
		totalOnWork: 1,
	}));
	const sort = { empty: false, sorted: true, unsorted: false };
	return {
		content,
		totalElements: content.length,
		totalPages: content.length === 0 ? 0 : 1,
		number: 0,
		size: 10,
		numberOfElements: content.length,
		first: true,
		last: true,
		empty: content.length === 0,
		sort,
		pageable: { offset: 0, pageNumber: 0, pageSize: 10, paged: true, unpaged: false, sort },
	};
}

function ownReviewResponse(latest: () => ReviewRef, trace: unknown, status = 200) {
	return async (request: Request) =>
		new URL(request.url).pathname.endsWith("/reviews")
			? json(ownReviews(latest()))
			: json(trace, status);
}

function latestOwnReview(latest: () => ReviewRef) {
	return async (request: Request) =>
		new URL(request.url).pathname.endsWith("/reviews")
			? json(ownReviews(latest()))
			: json(ownTrace(latest()));
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

async function reportApi(respond: (request: Request) => Promise<Response>) {
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

describe("the report's calls", () => {
	it("asks for the reader's own results on one exact item, most actionable first", async () => {
		const { built, requests } = await reportApi(async () =>
			json({ content: [], totalElements: 0 }),
		);
		await built.ownResults("team", "16", 25);
		const url = new URL(required(requests[0], "the request").url);
		expect(url.pathname).toBe("/api/workspaces/team/practices/results");
		expect(Object.fromEntries(url.searchParams)).toStrictEqual({
			itemId: "16",
			displayableOnly: "true",
			sort: "ACTIONABILITY",
			size: "25",
		});
	});

	it("names the newest own review of the item instead of requesting an aggregate trace", async () => {
		const answer = ownTrace(reviewRef(REVIEW, itemRef("16")));
		const { built, requests } = await reportApi(ownReviewResponse(() => answer.review, answer));
		await expect(built.trace("team", "16")).resolves.toStrictEqual(answer);
		const urls = requests.map((request) => new URL(request.url));
		expect(urls.map((url) => url.pathname)).toStrictEqual([
			"/api/workspaces/team/practices/items/16/reviews",
			"/api/workspaces/team/practices/items/16/own-trace",
		]);
		expect(Object.fromEntries(required(urls[0], "own review request").searchParams)).toStrictEqual({
			size: "1",
		});
		expect(Object.fromEntries(required(urls[1], "named trace request").searchParams)).toStrictEqual(
			{ reviewId: REVIEW },
		);
	});

	it("does not request an aggregate trace when the reader has no own review", async () => {
		const { built, requests } = await reportApi(async () => json(ownReviews()));
		await expect(built.trace("team", "16")).resolves.toBeNull();
		expect(requests).toHaveLength(1);
	});

	it("keeps a missing named trace unavailable and a refused own-review read an error", async () => {
		const missing = await reportApi(
			ownReviewResponse(() => reviewRef(REVIEW, itemRef("16")), { title: "Not found" }, 404),
		);
		await expect(missing.built.trace("team", "16")).resolves.toBeNull();
		const refused = await reportApi(async () => json({ title: "Forbidden" }, 403));
		await expect(refused.built.trace("team", "16")).rejects.toMatchObject({ code: "server" });
		expect(refused.requests).toHaveLength(1);
	});

	it("refuses an own review returned for another item before asking for its trace", async () => {
		const { built, requests } = await reportApi(async () =>
			json(ownReviews(reviewRef(LATER_REVIEW, itemRef("99")))),
		);
		await expect(built.trace("team", "16")).rejects.toMatchObject({ code: "server" });
		expect(requests).toHaveLength(1);
	});

	it.each([
		["about another item", ownTrace(reviewRef(REVIEW, itemRef("16")), itemRef("99"))],
		["of another review", ownTrace(reviewRef(LATER_REVIEW, itemRef("16")))],
	])("refuses a trace %s than the one named", async (_name, trace) => {
		const { built } = await reportApi(
			ownReviewResponse(() => reviewRef(REVIEW, itemRef("16")), trace),
		);
		await expect(built.trace("team", "16")).rejects.toMatchObject({ code: "server" });
	});

	it("discovers a newly recorded own review on the next read", async () => {
		let latest = reviewRef(REVIEW, itemRef("16"));
		const { built, requests } = await reportApi(latestOwnReview(() => latest));
		await built.trace("team", "16");
		latest = reviewRef(LATER_REVIEW, itemRef("16"));
		await built.trace("team", "16");
		expect(
			requests
				.filter((request) => new URL(request.url).pathname.endsWith("/own-trace"))
				.map((request) => new URL(request.url).searchParams.get("reviewId")),
		).toStrictEqual([REVIEW, LATER_REVIEW]);
	});

	it("posts one review request, and returns a refusal as the server's answer", async () => {
		const refusal: ReviewRequestOutcome = {
			status: "REFUSED",
			reviewId: null,
			reason: "COOLDOWN_ACTIVE",
		};
		const { built, requests } = await reportApi(async () => json(refusal));
		await expect(built.requestReview("team", "16")).resolves.toStrictEqual(refusal);
		expect(requests).toHaveLength(1);
		const [request] = requests;
		expect(request?.method).toBe("POST");
		expect(new URL(required(request, "the request").url).pathname).toBe(
			"/api/workspaces/team/practices/review-requests",
		);
		await expect(request?.json()).resolves.toStrictEqual({ itemId: "16" });
	});

	it("says a forbidden request as such, not as a server failure", async () => {
		for (const status of [403, 404]) {
			const forbidden = await reportApi(async () => json({ title: "Forbidden" }, status));
			await expect(forbidden.built.requestReview("team", "1")).rejects.toMatchObject({
				code: "forbidden",
			});
		}
	});

	it("asks for the reader's own comments on the work by its address, with the session's bearer", async () => {
		const answer: DeliveredWorkFeedback = {
			item: itemRef("16"),
			feedback: [
				{
					id: "5c1f7a90-1b2c-4d3e-8f90-a1b2c3d4e5f6",
					deliveredAt: "2026-09-26T11:00:00Z",
					practices: [{ slug: "clear-work", name: "Clear work" }],
					copies: [
						{
							id: "6d2a8b01-2c3d-4e5f-9a01-b2c3d4e5f607",
							kind: "SUMMARY",
							externalRef: "IC_kwDOA",
							externalUrl: "https://github.com/octo/app/pull/16#issuecomment-1",
							path: null,
							startLine: null,
							endLine: null,
							side: null,
							postedAt: "2026-09-26T11:00:00Z",
						},
					],
				},
			],
		};
		const { built, requests } = await reportApi(async () => json(answer));
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
			const { built } = await reportApi(async () => json({ title: "Refused" }, status));
			await expect(
				built.workFeedback("team", "https://github.com/octo/app/pull/16"),
			).rejects.toMatchObject({
				code: "server",
				message:
					"Hephaestus did not accept the request. Try again. If it keeps failing, contact your instance operator.",
			});
		}
	});

	it("never reads the in-app feedback endpoint, which records delivery", async () => {
		const { built, requests } = await reportApi(async () =>
			json({ content: [], totalElements: 0 }),
		);
		await built.ownResults("team", "16", 25);
		await built.workFeedback("team", "https://github.com/octo/app/pull/16").catch(() => undefined);
		expect(requests.some((request) => request.url.includes("/feedback/in-app"))).toBe(false);
	});
});
