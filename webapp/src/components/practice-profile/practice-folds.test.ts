import { describe, expect, it } from "vitest";

import type { PracticeStanding } from "@/api/types.gen";
import { practiceStandingOf } from "@/stories/practice-profile-story-mock-data";

import {
	groupHasNoStandingYet,
	groupNeverApplied,
	noStandingYetReason,
	splitFoldedPractices,
} from "./practice-folds";

const standing = (
	slug: string,
	value: PracticeStanding["standing"],
	doesNotApplyOnly = false,
): PracticeStanding => practiceStandingOf({ slug, name: slug, standing: value, doesNotApplyOnly });

describe("splitFoldedPractices", () => {
	it("folds every practice with no standing yet into one fold, whatever the reason", () => {
		const { shown, noStandingYet } = splitFoldedPractices([
			standing("a", "NO_OPPORTUNITY", true),
			standing("b", "NO_OPPORTUNITY", false),
			standing("c", "NOT_OBSERVED"),
			standing("d", "DEVELOPING"),
		]);
		expect(noStandingYet.map((practice) => practice.slug)).toStrictEqual(["a", "b", "c"]);
		expect(shown.map((practice) => practice.slug)).toStrictEqual(["d"]);
	});

	it("names a group whose every practice never applied, for the sentence behind its badge", () => {
		expect(groupNeverApplied([standing("a", "NO_OPPORTUNITY", true)])).toBe(true);
		expect(groupNeverApplied([standing("a", "NO_OPPORTUNITY", true), standing("b", "MIXED")])).toBe(
			false,
		);
		expect(groupNeverApplied([])).toBe(false);
	});

	it("folds a group only when no practice in it has a standing yet", () => {
		expect(
			groupHasNoStandingYet([
				standing("a", "NO_OPPORTUNITY", true),
				standing("b", "NO_OPPORTUNITY"),
				standing("c", "NOT_OBSERVED"),
			]),
		).toBe(true);
		expect(groupHasNoStandingYet([standing("a", "NOT_OBSERVED"), standing("b", "MIXED")])).toBe(
			false,
		);
		expect(groupHasNoStandingYet([])).toBe(false);
	});
});

describe("noStandingYetReason", () => {
	it("says a practice never applied, or why its standing is unsettled", () => {
		expect(noStandingYetReason("NO_OPPORTUNITY", "practice", true)).toMatch(
			/found that this practice did not apply to your work/u,
		);
		expect(noStandingYetReason("NOT_OBSERVED", "practice", false)).toBe(
			"No review has looked at this practice in your work yet.",
		);
		expect(noStandingYetReason("NO_OPPORTUNITY", "practice", false)).toMatch(
			/could not be judged on it/u,
		);
		expect(noStandingYetReason(undefined, "group", false)).toBe(
			"No review has looked at a practice in this group in your work yet.",
		);
		expect(noStandingYetReason("NO_OPPORTUNITY", "group", true)).toMatch(
			/practices of this group did not apply/u,
		);
	});
});
