import { afterEach, describe, expect, it } from "vitest";

import { applyUserViewHeaders, clearUserView, getUserViewSession } from "./session";

const session = {
	operatorAccountId: 7,
	workspaceSlug: "acme",
	userId: 42,
	login: "alex",
	name: "Alex",
	hasAccount: false,
	reason: "Check practice page",
};

describe("user view request context", () => {
	afterEach(clearUserView);

	it("keeps the selected user and reason on normal app reads", () => {
		sessionStorage.setItem("hephaestus.user-view", JSON.stringify(session));
		const request = applyUserViewHeaders(
			new Request("https://example.test/workspaces/acme/practices"),
		);

		expect(request.headers.get("X-User-View-Workspace")).toBe("acme");
		expect(request.headers.get("X-User-View-User")).toBe("42");
		expect(request.headers.get("X-User-View-Reason")).toBe("Check%20practice%20page");
	});

	it("does not attach the viewed identity to authentication requests", () => {
		sessionStorage.setItem("hephaestus.user-view", JSON.stringify(session));
		const request = applyUserViewHeaders(new Request("https://example.test/auth/refresh"));

		expect(request.headers.has("X-User-View-User")).toBe(false);
	});

	it("keeps the current account lookup outside the viewed identity", () => {
		sessionStorage.setItem("hephaestus.user-view", JSON.stringify(session));
		const request = applyUserViewHeaders(new Request("https://example.test/user"));

		expect(request.headers.has("X-User-View-User")).toBe(false);
	});

	it("ignores invalid stored identities", () => {
		sessionStorage.setItem("hephaestus.user-view", JSON.stringify({ ...session, userId: -1 }));

		expect(getUserViewSession()).toBeUndefined();
	});
});
