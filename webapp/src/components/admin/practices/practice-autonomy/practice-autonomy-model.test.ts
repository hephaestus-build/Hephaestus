import { assert, describe, expect, it } from "vitest";

import {
	autonomyDistribution,
	autonomyDistributionSentence,
	autonomyTotal,
} from "@/lib/practice-autonomy";

import { buildAutonomyFixture, scaleFixture } from "./fixtures";
import {
	countOverrides,
	groupPracticesByGroup,
	isOverridden,
	reviewableByHephaestus,
	UNASSIGNED_GROUP_KEY,
} from "./practice-autonomy-model";

const fixture = buildAutonomyFixture({
	workspaceDefault: "HUMAN_APPROVAL",
	groups: [
		{
			slug: "hygiene",
			name: "Hygiene",
			practices: [{ name: "One" }, { name: "Two", override: "AUTOMATIC" }],
		},
		{ slug: "testing", name: "Testing", override: "OFF", practices: [{ name: "Three" }] },
		{ slug: null, name: null, practices: [{ name: "Four" }] },
	],
});

describe("groupPracticesByGroup", () => {
	it("keeps the server's group order and its counts", () => {
		const groups = groupPracticesByGroup(fixture.rollup, fixture.practices);

		expect(groups.map((group) => group.name)).toStrictEqual(["Hygiene", "Testing", "Unassigned"]);
		const [hygiene] = groups;
		const [hygieneRollup] = fixture.rollup.groups;
		assert(hygiene);
		assert(hygieneRollup);
		expect(hygiene.counts).toStrictEqual(hygieneRollup.counts);
	});

	it("keys the no-group group so it can be rendered, and leaves it without a group slug", () => {
		const groups = groupPracticesByGroup(fixture.rollup, fixture.practices);
		const unassigned = groups.at(-1);

		expect(unassigned?.key).toBe(UNASSIGNED_GROUP_KEY);
		expect(unassigned?.groupSlug).toBeNull();
		expect(unassigned?.practices.map((practice) => practice.name)).toStrictEqual(["Four"]);
	});

	it("keeps a group the rollup lists with no practices, so its own autonomy stays reachable", () => {
		const empty = buildAutonomyFixture({
			groups: [{ slug: "quiet", name: "Quiet", override: "OFF", practices: [] }],
		});

		const groups = groupPracticesByGroup(empty.rollup, empty.practices);

		expect(groups).toHaveLength(1);
		const [quiet] = groups;
		assert(quiet);
		expect(quiet.practices).toStrictEqual([]);
		expect(quiet.groupSlug).toBe("quiet");
	});

	it("does not drop a practice whose group the rollup has not caught up with", () => {
		const stale = { ...fixture.rollup, groups: fixture.rollup.groups.slice(1) };

		const groups = groupPracticesByGroup(stale, fixture.practices);

		expect(groups.at(-1)?.practices.map((practice) => practice.name)).toStrictEqual(["One", "Two"]);
	});
});

describe("the overrides-only filter", () => {
	it("keeps only the practices somebody set by hand", () => {
		const groups = groupPracticesByGroup(fixture.rollup, fixture.practices, {
			overridesOnly: true,
		});

		expect(
			groups.flatMap((group) => group.practices).map((practice) => practice.name),
		).toStrictEqual(["Two"]);
	});

	it("keeps a group that decided for itself even when none of its practices did", () => {
		const groups = groupPracticesByGroup(fixture.rollup, fixture.practices, {
			overridesOnly: true,
		});

		const testing = groups.find((group) => group.groupSlug === "testing");
		expect(testing).toBeDefined();
		expect(testing?.practices).toStrictEqual([]);
		expect(testing?.totalPractices).toBe(1);
	});

	it("drops the no-group group, which cannot hold a decision of its own", () => {
		const groups = groupPracticesByGroup(fixture.rollup, fixture.practices, {
			overridesOnly: true,
		});

		expect(groups.some((group) => group.groupSlug === null)).toBe(false);
	});
});

describe("isOverridden", () => {
	it("reads the override, not the level that decided the autonomy", () => {
		const [hygiene, testing] = fixture.rollup.groups;
		assert(hygiene);
		assert(testing);

		expect(testing.autonomy.source).toBe("GROUP");
		expect(isOverridden(testing.autonomy)).toBe(true);
		expect(isOverridden(hygiene.autonomy)).toBe(false);
	});
});

describe("countOverrides", () => {
	it("counts both levels, because an admin who only set group autonomies has still decided something", () => {
		expect(countOverrides(fixture.rollup)).toStrictEqual({ practices: 1, groups: 1 });
	});
});

describe("reviewableByHephaestus", () => {
	it("refuses the modes the server refuses, so the control is never offered a choice that fails", () => {
		const unreviewable = buildAutonomyFixture({
			groups: [{ slug: "a", name: "A", practices: [{ name: "Manual", reviewable: false }] }],
		});

		const [manual] = unreviewable.practices;
		const [reviewable] = fixture.practices;
		assert(manual);
		assert(reviewable);
		expect(reviewableByHephaestus(manual.automatedReviewPolicy)).toBe(false);
		expect(reviewableByHephaestus(reviewable.automatedReviewPolicy)).toBe(true);
	});
});

describe("the workspace summary", () => {
	it("drops the autonomies the rollup sends at zero, and keeps ladder order", () => {
		expect(
			autonomyDistribution({ OFF: 3, HUMAN_APPROVAL: 0, AUTOMATIC: 1 }).map(
				({ autonomy }) => autonomy,
			),
		).toStrictEqual(["OFF", "AUTOMATIC"]);
		expect(
			autonomyDistribution(fixture.rollup.counts).map(({ autonomy }) => autonomy),
		).toStrictEqual(["OFF", "HUMAN_APPROVAL", "AUTOMATIC"]);
	});

	it("reads as a sentence for the live region, not as middot-separated fragments", () => {
		expect(autonomyDistributionSentence(fixture.rollup.counts)).toBe(
			"4 practices: 1 off, 2 review before sending and 1 send automatically.",
		);
	});

	it("says something rather than nothing for a workspace with no practices", () => {
		expect(autonomyDistributionSentence({})).toBe("No practices yet.");
		expect(autonomyTotal({})).toBe(0);
	});

	it("answers for a hundred practices from the rollup alone", () => {
		const scale = scaleFixture();

		expect(autonomyTotal(scale.rollup.counts)).toBe(100);
		expect(scale.rollup.groups).toHaveLength(25);
		expect(autonomyDistributionSentence(scale.rollup.counts)).toBe(
			"100 practices: 6 off, 89 review before sending and 5 send automatically.",
		);
	});
});
