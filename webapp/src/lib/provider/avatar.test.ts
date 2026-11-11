import { describe, expect, it } from "vitest";

import { getWorkspaceAvatarUrl } from "@/lib/provider/avatar";

describe("getWorkspaceAvatarUrl", () => {
	it("returns GitHub avatar URL with login", () => {
		expect(getWorkspaceAvatarUrl("GITHUB", "ls1intum")).toBe("https://github.com/ls1intum.png");
	});

	it("handles GitHub logins with hyphens", () => {
		expect(getWorkspaceAvatarUrl("GITHUB", "my-org")).toBe("https://github.com/my-org.png");
	});

	it("encodes special characters in the login", () => {
		expect(getWorkspaceAvatarUrl("GITHUB", "a b")).toBe("https://github.com/a%20b.png");
	});

	it("returns null for GitLab (instance-specific, backend-provided)", () => {
		expect(getWorkspaceAvatarUrl("GITLAB", "some-group")).toBeNull();
	});
});
