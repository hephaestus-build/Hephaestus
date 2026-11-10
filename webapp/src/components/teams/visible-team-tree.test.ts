import { describe, expect, it } from "vitest";

import { visibleTeamTree } from "./visible-team-tree";

function team(id: number, name: string, parentId?: number, hidden = false) {
	return { id, name, parentId, hidden };
}

const names = (teams: { name: string }[] | undefined) =>
	(teams ?? []).map((candidate) => candidate.name);

describe("visibleTeamTree", () => {
	it("re-parents a team under a hidden middle ancestor onto the nearest visible one", () => {
		const tree = visibleTeamTree([
			team(1, "Platform"),
			team(2, "Secret", 1, true),
			team(3, "Payments", 2),
		]);

		expect(names(tree.roots)).toStrictEqual(["Platform"]);
		expect(names(tree.childrenOf.get(1))).toStrictEqual(["Payments"]);
	});

	it("makes a team under a hidden root a root of its own", () => {
		const tree = visibleTeamTree([team(1, "Secret", undefined, true), team(2, "Payments", 1)]);

		expect(names(tree.roots)).toStrictEqual(["Payments"]);
	});

	it("reads a cycle through hidden teams as no parent rather than looping", () => {
		const tree = visibleTeamTree([team(1, "Loop", 2), team(2, "Hidden", 1, true)]);

		expect(names(tree.roots)).toStrictEqual(["Loop"]);
		expect(tree.childrenOf.size).toBe(0);
	});

	it("leaves out teams that only reach each other round a cycle", () => {
		const tree = visibleTeamTree([team(1, "Root"), team(2, "Ping", 3), team(3, "Pong", 2)]);

		expect(names(tree.roots)).toStrictEqual(["Root"]);
		expect(tree.childrenOf.size).toBe(0);
	});
});
