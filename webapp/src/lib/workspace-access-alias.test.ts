import { describe, expect, it } from "vitest";

import { workspaceAccessAlias } from "./workspace-access-alias";

describe("workspace access aliases", () => {
	it("uses the configured canonical origin and only the hostname's single workspace label", () => {
		expect(
			workspaceAccessAlias(
				"team.heph.example",
				"https://heph.example/ignored?query=ignored#ignored",
			),
		).toBe("https://heph.example/w/team/request-access");
		expect(workspaceAccessAlias("team.localhost", "http://localhost:4200")).toBe(
			"http://localhost:4200/w/team/request-access",
		);
	});

	it.each([
		"heph.example",
		"team.heph.example.attacker.test",
		"teamheph.example",
		"one.two.heph.example",
		"../team.heph.example",
		"team@heph.example",
		"ab.heph.example",
		"-team.heph.example",
		"team_name.heph.example",
		`${"a".repeat(52)}.heph.example`,
	])("does not derive a workspace from an unrelated or invalid hostname: %s", (hostname) => {
		expect(workspaceAccessAlias(hostname, "https://heph.example")).toBeUndefined();
	});
});
