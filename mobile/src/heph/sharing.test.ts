import { beforeEach, describe, expect, it } from "vitest";

import { clearSharing, grantSharing, sharingGranted } from "./sharing";

const scope = { sessionKey: "session-a", workspaceSlug: "team", threadId: "t1" };

beforeEach(() => {
	clearSharing();
});

describe("Heph sharing permission", () => {
	it("is not granted until the person agrees", () => {
		expect(sharingGranted(scope)).toBe(false);
		grantSharing(scope);
		expect(sharingGranted(scope)).toBe(true);
	});

	it("covers one conversation, in one workspace, for one account", () => {
		grantSharing(scope);

		expect(sharingGranted({ ...scope, threadId: "t2" })).toBe(false);
		expect(sharingGranted({ ...scope, workspaceSlug: "other-team" })).toBe(false);
		expect(sharingGranted({ ...scope, sessionKey: "session-b" })).toBe(false);
	});

	it("is forgotten with the session", () => {
		grantSharing(scope);
		clearSharing();
		expect(sharingGranted(scope)).toBe(false);
	});
});
