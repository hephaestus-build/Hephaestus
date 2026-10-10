import { describe, expect, it } from "vitest";

import { teamPaths } from "./team-paths";

describe("teamPaths", () => {
	it("names a sub-team by its path, in path order", () => {
		expect(
			teamPaths([
				{ id: 2, key: "payments", name: "Payments", parentId: 1 },
				{ id: 1, key: "platform", name: "Platform" },
				{ id: 3, key: "orphan", name: "Orphan", parentId: 9 },
			]),
		).toStrictEqual([
			{ key: "orphan", label: "Orphan" },
			{ key: "platform", label: "Platform" },
			{ key: "payments", label: "Platform / Payments" },
		]);
	});
});
