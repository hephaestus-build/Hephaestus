import { describe, expect, it } from "vitest";

import { isCopilotExcludedRoute } from "./copilot-route";

describe("isCopilotExcludedRoute", () => {
	it.each([
		"/admin",
		"/admin/users",
		"/w/acme/admin",
		"/w/acme/admin/practices/reviews",
		"/mentor",
		"/w/acme/mentor/thread-1",
		"/settings",
		"/privacy",
	])("excludes %s", (pathname) => {
		expect(isCopilotExcludedRoute(pathname)).toBe(true);
	});

	it.each(["/", "/administrator", "/w/acme", "/w/acme/user/ada", "/w/acme/teams"])(
		"allows %s",
		(pathname) => {
			expect(isCopilotExcludedRoute(pathname)).toBe(false);
		},
	);
});
