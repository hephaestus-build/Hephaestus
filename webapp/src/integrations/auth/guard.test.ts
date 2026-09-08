import { QueryClient } from "@tanstack/react-query";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { currentUser } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";

import { isAppAdmin, resolveCurrentUser, safeReturnTo } from "./guard";
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
			["backslash escape /\\evil", "/\\evil.com"],
			["scheme after slash /javascript:", "/javascript:alert(1)"],
			["scheme after slashes //javascript:", "//javascript:alert(1)"],
			["bare relative (no leading slash)", "dashboard"],
			["javascript scheme", "javascript:alert(1)"],
			["data scheme", "data:text/html,evil"],
		])("%s -> /", (_label, input) => {
			expect(safeReturnTo(input)).toBe("/");
		});

		it("rejects embedded control characters (NUL, newline, tab, CR, DEL)", () => {
			expect(safeReturnTo("/foo\x00bar")).toBe("/");
			expect(safeReturnTo("/foo\nbar")).toBe("/");
			expect(safeReturnTo("/foo\tbar")).toBe("/");
			expect(safeReturnTo("/foo\rbar")).toBe("/");
			expect(safeReturnTo("/foo\x7fbar")).toBe("/");
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

describe("resolveCurrentUser", () => {
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

	const goStale = (queryClient: QueryClient) =>
		queryClient.invalidateQueries({ refetchType: "none" });

	it("fetches when nothing is cached", async () => {
		serve(asAppRole("APP_ADMIN"));

		expect(await resolveCurrentUser(client())).toMatchObject({ appRole: "APP_ADMIN" });
	});

	it("answers null only when the server confirms the session is unauthenticated", async () => {
		serve(() => new HttpResponse(null, { status: 401 }));

		expect(await resolveCurrentUser(client())).toBeNull();
	});

	it.each([403, 503])(
		"surfaces HTTP %i on a cold load instead of asking for sign-in",
		async (status) => {
			serve(() => new HttpResponse(null, { status }));
			await expect(resolveCurrentUser(client())).rejects.toThrow("Could not verify your session.");
		},
	);

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
		expect(await resolveCurrentUser(queryClient)).toMatchObject({ appRole: "APP_ADMIN" });
		await vi.waitFor(() => expect(revoked.times).toBe(1));
		expect(await resolveCurrentUser(queryClient)).toMatchObject({ appRole: "APP_USER" });
	});

	it("keeps serving the cached user when the background refresh fails", async () => {
		const queryClient = client();
		serve(asAppRole("APP_ADMIN"));
		await resolveCurrentUser(queryClient);
		await goStale(queryClient);
		const failing = serve(() => HttpResponse.error());

		expect(await resolveCurrentUser(queryClient)).toMatchObject({ appRole: "APP_ADMIN" });
		await vi.waitFor(() => expect(failing.times).toBe(1));
	});
});
