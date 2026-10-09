import { describe, expect, it } from "vitest";

import { isHephPanelExcludedRoute } from "./-heph-panel-route";

describe("isHephPanelExcludedRoute", () => {
	it.each([
		"/admin",
		"/admin/users",
		"/w/acme/admin",
		"/w/acme/admin/practices/reviews",
		"/mentor",
		"/w/acme/mentor",
		"/settings",
		"/privacy",
	])("excludes %s", (pathname) => {
		expect(isHephPanelExcludedRoute(pathname)).toBe(true);
	});

	it.each(["/", "/administrator", "/w/acme", "/w/acme/user/ada", "/w/acme/teams"])(
		"allows %s",
		(pathname) => {
			expect(isHephPanelExcludedRoute(pathname)).toBe(false);
		},
	);
});
