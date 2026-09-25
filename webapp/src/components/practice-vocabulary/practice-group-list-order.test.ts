import { describe, expect, it } from "vitest";

import type { PracticeGroup, PracticeGroupStanding } from "@/api/types.gen";

import { DEFAULT_PRACTICE_GROUP_SORT, sortPracticeGroups } from "./practice-group-list-order";

function group(slug: string, name: string, displayOrder = 0): PracticeGroup {
	return {
		id: slug.length,
		slug,
		name,
		displayOrder,
		visibleInPracticeDashboards: true,
		autonomy: { effective: "AUTOMATIC", inherited: true, source: "WORKSPACE" },
		createdAt: new Date("2026-01-01T00:00:00Z"),
	};
}

function standing(slug: string, value: PracticeGroupStanding["standing"]): PracticeGroupStanding {
	return { groupSlug: slug, groupName: slug, standing: value, observations: [], sources: [] };
}

const slugsOf = (groups: PracticeGroup[]) => groups.map((entry) => entry.slug);

describe("sortPracticeGroups", () => {
	const groups = [
		group("well", "Going well", 3),
		group("attention", "Needs attention", 2),
		group("mixed", "Mixed", 1),
		group("silent", "Not observed", 0),
	];
	const standings = {
		well: standing("well", "STRENGTH"),
		attention: standing("attention", "DEVELOPING"),
		mixed: standing("mixed", "MIXED"),
	};

	it("puts what needs attention first when ascending, what no review reached last", () => {
		expect(slugsOf(sortPracticeGroups(groups, standings, "asc"))).toStrictEqual([
			"attention",
			"mixed",
			"well",
			"silent",
		]);
	});

	it("reverses the standings when descending", () => {
		expect(slugsOf(sortPracticeGroups(groups, standings, "desc"))).toStrictEqual([
			"silent",
			"well",
			"mixed",
			"attention",
		]);
	});

	it("breaks ties by the catalog's display order, then the name, in both directions", () => {
		const tied = [group("b", "Bravo", 2), group("c", "Charlie", 1), group("a", "Alpha", 1)];
		const all = Object.fromEntries(
			tied.map((entry) => [entry.slug, standing(entry.slug, "MIXED")]),
		);
		expect(slugsOf(sortPracticeGroups(tied, all, "asc"))).toStrictEqual(["a", "c", "b"]);
		expect(slugsOf(sortPracticeGroups(tied, all, "desc"))).toStrictEqual(["a", "c", "b"]);
	});

	it("returns a copy", () => {
		const sorted = sortPracticeGroups(groups, standings, DEFAULT_PRACTICE_GROUP_SORT);
		expect(sorted).not.toBe(groups);
		expect(slugsOf(groups)[0]).toBe("well");
	});
});
