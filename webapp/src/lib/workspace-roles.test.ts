import { describe, expect, it } from "vitest";

import { hasMinimumWorkspaceRole, type WorkspaceRole } from "./workspace-roles";

describe("hasMinimumWorkspaceRole", () => {
	const cases: [WorkspaceRole | undefined | null, WorkspaceRole, boolean][] = [
		["OWNER", "MEMBER", true],
		["OWNER", "ADMIN", true],
		["OWNER", "OWNER", true],
		["ADMIN", "MEMBER", true],
		["ADMIN", "ADMIN", true],
		["ADMIN", "OWNER", false],
		["MEMBER", "MEMBER", true],
		["MEMBER", "ADMIN", false],
		["MEMBER", "OWNER", false],
		[undefined, "MEMBER", false],
		[undefined, "ADMIN", false],
		[null, "OWNER", false],
	];

	it.each(cases)("role %s with minRole %s → %s", (role, minRole, expected) => {
		expect(hasMinimumWorkspaceRole(role, minRole)).toBe(expected);
	});

	it("fails closed on a role the client does not know, so the server can ship one first", () => {
		expect(hasMinimumWorkspaceRole("SUPER_OWNER", "MEMBER")).toBe(false);
	});
});
