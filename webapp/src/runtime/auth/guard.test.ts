import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { currentUser } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";

import { safeReturnTo } from "@/runtime/auth/return-to";

import {
	currentUserQueryOptions,
	isAppAdmin,
	IDENTITY_READ_TIMEOUT_MS,
	resolveCurrentUser,
	resolveWorkspaceMembership,
	serverUnreachable,
} from "./guard";

describe("safeReturnTo", () => {
	describe("accepts same-origin absolute paths", () => {
		it.each(["/", "/dashboard", "/w/acme/overview", "/a/b?x=1&y=2", "/path#frag", "/with-dash_x"])(
			"returns %s unchanged",
			(input) => {
				expect(safeReturnTo(input)).toBe(input);
			},
		);
	});

	describe("falls back to / for unsafe or absent values", () => {
		it.each([
			["empty string", ""],
			["undefined", undefined],
			["protocol-relative //evil", "//evil.com"],
			["protocol-relative ///evil", "///evil.com"],
			["absolute https URL", "https://evil.com"],
			["absolute http URL", "http://evil.com/path"],
			[String.raw`backslash escape /\evil`, String.raw`/\evil.com`],
			["scheme after slash /javascript:", "/javascript:alert(1)"],
			["scheme after slashes //javascript:", "//javascript:alert(1)"],
			["bare relative (no leading slash)", "dashboard"],
			["javascript scheme", "javascript:alert(1)"],
			["data scheme", "data:text/html,evil"],
		])("%s -> /", (_label, input) => {
			expect(safeReturnTo(input)).toBe("/");
		});

		it("rejects embedded control characters (NUL, newline, tab, CR, DEL)", () => {
			expect(safeReturnTo("/foo\u0000bar")).toBe("/");
			expect(safeReturnTo("/foo\nbar")).toBe("/");
			expect(safeReturnTo("/foo\tbar")).toBe("/");
			expect(safeReturnTo("/foo\rbar")).toBe("/");
			expect(safeReturnTo("/foo\u007Fbar")).toBe("/");
		});

		it("rejects raw whitespace that could hide an escape", () => {
			expect(safeReturnTo("/ /evil")).toBe("/");
			expect(safeReturnTo("/foo bar")).toBe("/");
		});
		describe("decode-then-check defeats percent-encoded escapes", () => {
			it.each([
				["encoded protocol-relative //evil", "/%2f%2fevil.com"],
				["encoded protocol-relative (mixed case)", "/%2F%2Fevil.com"],
				["double-encoded protocol-relative", "/%252f%252fevil.com"],
				["encoded tab then path", "/%09/evil"],
				["encoded space then path", "/%20/evil"],
				["encoded leading backslash", "/%5cevil.com"],
				["encoded newline", "/foo%0abar"],
				["encoded NUL", "/foo%00bar"],
			])("%s -> /", (_label, input) => {
				expect(safeReturnTo(input)).toBe("/");
			});

			it("rejects a literal userinfo @ host trick", () => {
				expect(safeReturnTo("/@evil")).toBe("/");
				expect(safeReturnTo("/%40evil")).toBe("/");
			});

			it("preserves the original value for a safe path with legitimately-encoded query bytes", () => {
				expect(safeReturnTo("/search?q=a%26b")).toBe("/search?q=a%26b");
			});

			it("does not loop forever on a decode bomb / malformed encoding", () => {
				expect(safeReturnTo("/foo%")).toBe("/foo%");
				expect(safeReturnTo("/%2525252f%2525252fevil")).toBe("/");
			});
		});
	});
});

describe("isAppAdmin", () => {
	it("is true when appRole is APP_ADMIN", () => {
		expect(isAppAdmin({ appRole: "APP_ADMIN" })).toBe(true);
	});
	it("is false for a plain user regardless of any roles claim", () => {
		expect(isAppAdmin({ appRole: "APP_USER" })).toBe(false);
	});
	it.each([null, undefined])("is false for %s", (u) => {
		expect(isAppAdmin(u)).toBe(false);
	});
});

function client() {
	return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function serve(answer: () => Response) {
	const asked = { times: 0 };
	server.use(
		http.get("*/user", () => {
			asked.times += 1;
			return answer();
		}),
	);
	return asked;
}

const asAppRole = (appRole: "APP_ADMIN" | "APP_USER") => () =>
	HttpResponse.json({ ...currentUser, appRole });

const goStale = async (queryClient: QueryClient) =>
	queryClient.invalidateQueries({ refetchType: "none" });

/** How a resolution ended, so a test can await either outcome without a rejection escaping. */
async function outcome<T>(resolution: Promise<T>) {
	try {
		return { user: await resolution };
	} catch (error) {
		return { error };
	}
}

describe("resolveCurrentUser", () => {
	it("fetches when nothing is cached", async () => {
		serve(asAppRole("APP_ADMIN"));

		await expect(resolveCurrentUser(client())).resolves.toMatchObject({ appRole: "APP_ADMIN" });
	});

	it("finishes route identity resolution when the last UI observer unmounts", async () => {
		const queryClient = client();
		const response = deferred<Response>();
		const requested = vi.fn(async () => response.promise);
		server.use(http.get("*/user", requested));
		const observer = new QueryObserver(queryClient, currentUserQueryOptions());
		const unsubscribe = observer.subscribe(vi.fn());
		const resolved = outcome(resolveCurrentUser(queryClient));
		await vi.waitFor(() => expect(requested).toHaveBeenCalledOnce());
		unsubscribe();
		response.resolve(HttpResponse.json(currentUser));
		await expect(resolved).resolves.toStrictEqual({ user: currentUser });
		queryClient.clear();
	});

	it("does not restore identity after an explicit session cancellation", async () => {
		const queryClient = client();
		const response = deferred<Response>();
		const requested = vi.fn(async () => response.promise);
		server.use(http.get("*/user", requested));
		const resolved = outcome(resolveCurrentUser(queryClient));
		await vi.waitFor(() => expect(requested).toHaveBeenCalledOnce());
		await queryClient.cancelQueries({ queryKey: currentUserQueryOptions().queryKey });
		response.resolve(HttpResponse.json(currentUser));
		await expect(resolved).resolves.toHaveProperty("error");
		expect(queryClient.getQueryData(currentUserQueryOptions().queryKey)).toBeUndefined();
		queryClient.clear();
	});

	it.each([401, 403])(
		"answers null when the server refuses the session with HTTP %i",
		async (status) => {
			serve(() => new HttpResponse(null, { status }));

			await expect(resolveCurrentUser(client())).resolves.toBeNull();
		},
	);

	it("surfaces HTTP 503 on a cold load instead of asking for sign-in", async () => {
		serve(() => new HttpResponse(null, { status: 503 }));
		await expect(resolveCurrentUser(client())).rejects.toThrow("Could not verify your session.");
	});

	it("surfaces a server that never answers as unreachable once the read times out", async () => {
		vi.useFakeTimers({ shouldAdvanceTime: true });
		// AbortSignal.timeout runs on a platform timer that fake timers do not move.
		vi.spyOn(AbortSignal, "timeout").mockImplementation((ms) => {
			const controller = new AbortController();
			setTimeout(() => {
				controller.abort(new DOMException("The read timed out.", "TimeoutError"));
			}, ms);
			return controller.signal;
		});
		try {
			const never = deferred<Response>();
			server.use(http.get("*/user", async () => never.promise));
			const read = outcome(resolveCurrentUser(client()));
			await vi.advanceTimersByTimeAsync(IDENTITY_READ_TIMEOUT_MS);
			const { error } = await read;
			expect(serverUnreachable(error)).toBe(true);
		} finally {
			vi.useRealTimers();
			vi.restoreAllMocks();
		}
	});

	it("surfaces a connection failure on a cold load", async () => {
		serve(() => HttpResponse.error());
		await expect(resolveCurrentUser(client())).rejects.toThrow("Could not verify your session.");
	});

	it("answers from a stale cache and refreshes behind it", async () => {
		const queryClient = client();
		serve(asAppRole("APP_ADMIN"));
		await resolveCurrentUser(queryClient);
		await goStale(queryClient);
		const revoked = serve(asAppRole("APP_USER"));

		// The stale answer is still instant — the navigation never waits on the network…
		await expect(resolveCurrentUser(queryClient)).resolves.toMatchObject({ appRole: "APP_ADMIN" });
		// …and the revocation fetched behind it lands for the next one.
		await vi.waitFor(() => expect(revoked.times).toBe(1));
		await vi.waitFor(async () =>
			expect(resolveCurrentUser(queryClient)).resolves.toMatchObject({ appRole: "APP_USER" }),
		);
	});

	it("keeps serving the cached user when the background refresh fails", async () => {
		const queryClient = client();
		serve(asAppRole("APP_ADMIN"));
		await resolveCurrentUser(queryClient);
		await goStale(queryClient);
		const failing = serve(() => HttpResponse.error());

		await expect(resolveCurrentUser(queryClient)).resolves.toMatchObject({ appRole: "APP_ADMIN" });
		await vi.waitFor(() => expect(failing.times).toBe(1));
	});
});

describe("serverUnreachable", () => {
	it.each([500, 502, 503])("reads HTTP %i as a server that cannot answer", async (status) => {
		serve(() => new HttpResponse(null, { status }));
		await expect(client().query(currentUserQueryOptions())).rejects.toSatisfy(serverUnreachable);
	});

	it("reads a lost connection as a server that cannot answer", async () => {
		serve(() => HttpResponse.error());
		await expect(client().query(currentUserQueryOptions())).rejects.toSatisfy(serverUnreachable);
	});

	it.each([401, 403])("reads HTTP %i as an answer about the session", async (status) => {
		serve(() => new HttpResponse(null, { status }));
		await expect(client().query(currentUserQueryOptions())).rejects.toSatisfy(
			(error) => !serverUnreachable(error),
		);
	});
});

const membership = (answer: () => Response) =>
	server.use(http.get("*/workspaces/:workspaceSlug/members/me", answer));

describe("resolveWorkspaceMembership", () => {
	it.each([400, 403, 404])("reads HTTP %i as no membership", async (status) => {
		membership(() => HttpResponse.json({ status }, { status }));
		await expect(resolveWorkspaceMembership(client(), "acme")).resolves.toBeNull();
	});

	it("passes on a server that cannot answer instead of reading it as no membership", async () => {
		vi.useFakeTimers({ shouldAdvanceTime: true });
		try {
			membership(() => HttpResponse.json({ status: 503 }, { status: 503 }));
			// The membership read asks twice more, with TanStack Query's backoff, before it gives up.
			await Promise.all([
				expect(resolveWorkspaceMembership(client(), "acme")).rejects.toSatisfy(serverUnreachable),
				vi.advanceTimersByTimeAsync(5000),
			]);
		} finally {
			vi.useRealTimers();
		}
	});
});
