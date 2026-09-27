import { describe, expect, it } from "vitest";

import { parsePushPayload } from "./payload";

describe("parsePushPayload", () => {
	it("accepts the practice feedback nudge", () => {
		const data = {
			kind: "practice-feedback",
			workspaceSlug: "team-ws",
			nativeSessionId: "7a1f3c5e-9b2d-4f6a-8c1e-3d5f7a9b1c2e",
		};

		expect(parsePushPayload(data)).toStrictEqual(data);
	});

	it("refuses a slug that could steer navigation elsewhere", () => {
		expect(
			parsePushPayload({
				kind: "practice-feedback",
				workspaceSlug: "../admin",
				nativeSessionId: "7a1f3c5e-9b2d-4f6a-8c1e-3d5f7a9b1c2e",
			}),
		).toBeUndefined();
		expect(
			parsePushPayload({ kind: "practice-feedback", workspaceSlug: "Team WS" }),
		).toBeUndefined();
	});

	it("refuses a nudge that does not say which sign-in it is for", () => {
		expect(
			parsePushPayload({ kind: "practice-feedback", workspaceSlug: "team-ws" }),
		).toBeUndefined();
		expect(
			parsePushPayload({
				kind: "practice-feedback",
				workspaceSlug: "team-ws",
				nativeSessionId: "1",
			}),
		).toBeUndefined();
	});

	it("ignores kinds this build does not know", () => {
		expect(parsePushPayload({ kind: "marketing", url: "https://example.org" })).toBeUndefined();
		expect(parsePushPayload(null)).toBeUndefined();
	});
});
