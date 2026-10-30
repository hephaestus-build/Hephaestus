import { describe, expect, it } from "vitest";

import { isConversationPanelExcludedRoute } from "./-conversation-panel-route";

describe("isConversationPanelExcludedRoute", () => {
	it.each([
		"/admin",
		"/admin/users",
		"/w/acme/admin",
		"/w/acme/admin/practices/reviews",
		"/conversation",
		"/w/acme/conversation",
		"/settings",
		"/privacy",
	])("excludes %s", (pathname) => {
		expect(isConversationPanelExcludedRoute(pathname)).toBe(true);
	});

	it.each(["/", "/administrator", "/w/acme", "/w/acme/user/ada", "/w/acme/teams"])(
		"allows %s",
		(pathname) => {
			expect(isConversationPanelExcludedRoute(pathname)).toBe(false);
		},
	);
});
