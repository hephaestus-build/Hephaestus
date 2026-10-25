import { describe, expect, it } from "vitest";

import type { PracticeStanding } from "@/api/types.gen";
import { practiceStandingOf } from "@/stories/practice-profile-story-mock-data";

import { standingDefs } from "@/components/practice-vocabulary/practice-group-standing-defs";

import {
	groupHasNoStandingYet,
	groupNeverApplied,
	groupNoStandingYetReason,
	NEVER_APPLIED,
	NO_STANDING_YET,
	practiceNoStandingYetReason,
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

describe("practiceNoStandingYetReason", () => {
	it("says a practice never applied, or gives its unsettled standing's own reason", () => {
		expect(practiceNoStandingYetReason(standing("a", "NO_OPPORTUNITY", true))).toBe(
			NEVER_APPLIED.description,
		);
		expect(practiceNoStandingYetReason(standing("a", "NOT_OBSERVED"))).toBe(
			standingDefs("practice").NOT_OBSERVED.description,
		);
		expect(practiceNoStandingYetReason(standing("a", "NO_OPPORTUNITY"))).toBe(
			standingDefs("practice").NO_OPPORTUNITY.description,
		);
	});
});

describe("groupNoStandingYetReason", () => {
	it("reads the same practices that fold the group", () => {
		expect(
			groupNoStandingYetReason([standing("a", "NO_OPPORTUNITY", true)], "NO_OPPORTUNITY"),
		).toBe(NEVER_APPLIED.oneGroupDescription);
		expect(groupNoStandingYetReason([standing("a", "NOT_OBSERVED")], undefined)).toBe(
			NO_STANDING_YET.oneGroupDescription,
		);
	});

	it("never gives a settled group standing's sentence when its practices have no standing", () => {
		const practices = [standing("a", "NOT_OBSERVED"), standing("b", "NO_OPPORTUNITY")];
		expect(groupHasNoStandingYet(practices)).toBe(true);
		for (const settled of ["STRENGTH", "MIXED", "DEVELOPING"] as const) {
			expect(groupNoStandingYetReason(practices, settled)).toBe(
				NO_STANDING_YET.oneGroupDescription,
			);
		}
	});

	it("gives the group standing's own sentence when only the group has none", () => {
		expect(groupNoStandingYetReason([standing("a", "MIXED")], "NOT_OBSERVED")).toBe(
			standingDefs("group").NOT_OBSERVED.description,
		);
	});
});
