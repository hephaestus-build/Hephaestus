import { describe, expect, it } from "vitest";

import { getTeamAvatarUrl, getWorkspaceAvatarUrl } from "@/lib/provider/avatar";

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

describe("getTeamAvatarUrl", () => {
	it("returns GitHub team avatar URL with team ID", () => {
		expect(getTeamAvatarUrl("GITHUB", 12_345)).toBe(
			"https://avatars.githubusercontent.com/t/12345?s=512&v=4",
		);
	});

	it("returns null for GitLab", () => {
		expect(getTeamAvatarUrl("GITLAB", 12_345)).toBeNull();
	});
});
