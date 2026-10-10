import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ZodError } from "zod";

import environment from "@/environment";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { csrfFetch, refetchCsrfToken } from "./csrf";

const api = "http://localhost:8080";
const originalEnabled = environment.workspaceSubdomains.enabled;
let issued = 0;

beforeEach(() => {
	environment.workspaceSubdomains.enabled = true;
	issued = 0;
	server.use(
		http.get(`${api}/auth/csrf`, () => {
			issued += 1;
			return HttpResponse.json({ token: `token-${issued}`, headerName: "X-XSRF-TOKEN" });
		}),
	);
});
afterEach(() => {
	environment.workspaceSubdomains.enabled = originalEnabled;
});

async function mutation() {
	return csrfFetch(`${api}/settings`, { method: "POST", credentials: "include", body: "payload" });
}

function csrfRefusal() {
	return HttpResponse.json({ type: "urn:hephaestus:csrf" }, { status: 403 });
}

function replayResponse(bodies: string[], headers: (string | null)[]) {
	return async ({ request }: { request: Request }) => {
		bodies.push(await request.text());
		headers.push(request.headers.get("X-XSRF-TOKEN"));
		return request.headers.get("X-XSRF-TOKEN") === "token-1"
			? HttpResponse.json({ type: "urn:hephaestus:csrf" }, { status: 403 })
			: new HttpResponse(null, { status: 204 });
	};
}

describe("apex CSRF transport", () => {
	it("refuses malformed discovery without sending a credentialed mutation", async () => {
		let mutations = 0;
		server.use(
			http.get(`${api}/auth/csrf`, () =>
				HttpResponse.json({ token: 42, headerName: "X-XSRF-TOKEN" }),
			),
			http.post(`${api}/settings`, () => {
				mutations += 1;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		await expect(refetchCsrfToken()).rejects.toThrow(ZodError);
		await expect(mutation()).rejects.toThrow(ZodError);
		expect(mutations).toBe(0);
	});
	it("discovers a token with credentials before sending the mutation", async () => {
		const headers: (string | null)[] = [];
		server.use(
			http.post(`${api}/settings`, ({ request }) => {
				headers.push(request.headers.get("X-XSRF-TOKEN"));
				return new HttpResponse(null, { status: 204 });
			}),
		);
		await refetchCsrfToken();
		const response = await mutation();
		expect(response.status).toBe(204);
		expect(issued).toBe(1);
		expect(headers).toStrictEqual(["token-1"]);
	});
	it("refetches and replays a definitive CSRF refusal once, with the original body", async () => {
		const bodies: string[] = [];
		const headers: (string | null)[] = [];
		server.use(http.post(`${api}/settings`, replayResponse(bodies, headers)));

		await refetchCsrfToken();
		const response = await mutation();
		expect(response.status).toBe(204);
		expect(issued).toBe(2);
		expect(headers).toStrictEqual(["token-1", "token-2"]);
		expect(bodies).toStrictEqual(["payload", "payload"]);
	});
	it("shares one token replacement across concurrent CSRF refusals", async () => {
		const bodies: string[] = [];
		const headers: (string | null)[] = [];
		server.use(http.post(`${api}/settings`, replayResponse(bodies, headers)));
		await refetchCsrfToken();
		const responses = await Promise.all([mutation(), mutation()]);
		expect(responses.map(({ status }) => status)).toStrictEqual([204, 204]);
		expect(issued).toBe(2);
		expect(headers).toStrictEqual(["token-1", "token-1", "token-2", "token-2"]);
	});
	it("recovers when another request has already failed to refresh the token", async () => {
		const delayedRefusal = deferred<undefined>();
		await refetchCsrfToken();
		const discovery = vi
			.fn()
			.mockReturnValueOnce(HttpResponse.json({ token: 42, headerName: "X-XSRF-TOKEN" }))
			.mockReturnValue(HttpResponse.json({ token: "token-3", headerName: "X-XSRF-TOKEN" }));
		const handleMutation = vi
			.fn()
			.mockImplementationOnce(csrfRefusal)
			.mockImplementationOnce(async () => {
				await delayedRefusal.promise;
				return csrfRefusal();
			})
			.mockReturnValue(new HttpResponse(null, { status: 204 }));
		server.use(
			http.get(`${api}/auth/csrf`, discovery),
			http.post(`${api}/settings`, handleMutation),
		);
		const first = mutation();
		const second = mutation();
		await expect(first).rejects.toThrow(ZodError);
		delayedRefusal.resolve(undefined);
		await expect(second).resolves.toHaveProperty("status", 204);
		expect(discovery).toHaveBeenCalledTimes(2);
	});

	it("does not replay an authorization refusal", async () => {
		let calls = 0;
		server.use(
			http.post(`${api}/settings`, () => {
				calls += 1;
				return HttpResponse.json({ type: "about:blank", status: 403 }, { status: 403 });
			}),
		);
		await refetchCsrfToken();
		const response = await mutation();
		expect(response.status).toBe(403);
		expect(calls).toBe(1);
		expect(issued).toBe(1);
	});
	it("stops after the second CSRF refusal", async () => {
		let calls = 0;
		server.use(
			http.post(`${api}/settings`, () => {
				calls += 1;
				return HttpResponse.json({ type: "urn:hephaestus:csrf" }, { status: 403 });
			}),
		);
		await refetchCsrfToken();
		const response = await mutation();
		expect(response.status).toBe(403);
		expect(calls).toBe(2);
	});
	it("leaves the transport unchanged with the switch off", async () => {
		environment.workspaceSubdomains.enabled = false;
		server.use(http.get(`${api}/user`, () => HttpResponse.json({ id: 1 })));
		const response = await csrfFetch(`${api}/user`);
		expect(response.status).toBe(200);
		expect(issued).toBe(0);
	});
});
