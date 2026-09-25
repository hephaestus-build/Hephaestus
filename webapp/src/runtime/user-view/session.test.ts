import { afterEach, describe, expect, it, vi } from "vitest";

import { storeUserView } from "@/test/user-view";

import { applyUserViewHeaders, clearUserView, getUserViewSession } from "./session";

vi.mock("@/environment", () => ({ default: { serverUrl: "https://example.test/api" } }));

describe("user view request context", () => {
	afterEach(clearUserView);

	it("keeps the selected user and reason on normal app reads", () => {
		storeUserView({ reason: "Check practice page" });
		const request = applyUserViewHeaders(
			new Request("https://example.test/api/workspaces/engineering/practices"),
		);

		expect(request.headers.get("X-User-View-Workspace")).toBe("engineering");
		expect(request.headers.get("X-User-View-User")).toBe("11");
		expect(request.headers.get("X-User-View-Reason")).toBe("Check%20practice%20page");
	});

	it.each(["/auth/refresh", "/api/user", "/api/identity-providers", "/api/user/identities"])(
		"keeps the administrator's own request %s outside the viewed identity",
		(path) => {
			storeUserView();
			const request = applyUserViewHeaders(new Request(`https://example.test${path}`));

			expect(request.headers.has("X-User-View-User")).toBe(false);
		},
	);

	it.each([
		"/workspaces/engineering/practices/standings",
		"/api/workspaces/engineering/practices/standings",
	])("attaches the viewed identity to the workspace read %s", (path) => {
		storeUserView();
		const request = applyUserViewHeaders(new Request(`https://example.test${path}`));

		expect(request.headers.get("X-User-View-User")).toBe("11");
	});

	it("ignores invalid stored identities", () => {
		storeUserView({ userId: -1 });

		expect(getUserViewSession()).toBeUndefined();
	});
});
