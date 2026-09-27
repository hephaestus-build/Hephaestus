import { describe, expect, it } from "vitest";

import type {
	PracticeGroup,
	PracticeGroupStanding,
	PracticeStanding,
	PracticeStandingObservation,
	ReviewedPractice,
} from "@/api/types.gen";

import {
	basedOn,
	buildProfile,
	isObserved,
	leadingNextStep,
	practiceNextStep,
	coverageNote,
	tallyText,
} from "./profile";

const group = (slug: string, displayOrder: number): PracticeGroup => ({
	slug,
	name: slug,
	displayOrder,
	id: displayOrder,
	createdAt: new Date(0),
	visibleInPracticeDashboards: true,
	autonomy: { effective: "AUTOMATIC", inherited: true, source: "WORKSPACE" },
});

const groupStanding = (
	groupSlug: string,
	standing: PracticeGroupStanding["standing"],
	guidance?: string,
): PracticeGroupStanding => ({
	groupSlug,
	groupName: groupSlug,
	standing,
	observations: [],
	sources: [],
	...(guidance === undefined ? {} : { guidance }),
});

const reviewed = (slug: string, groupSlug: string): ReviewedPractice => ({
	slug,
	name: slug,
	groupSlug,
});

const practiceStanding = (
	slug: string,
	standing: PracticeStanding["standing"],
	extra: Partial<PracticeStanding> = {},
): PracticeStanding => ({
	slug,
	name: slug,
	groupSlug: "g",
	groupName: "g",
	standing,
	toWorkOn: [],
	strengths: [],
	...extra,
});

const observation = (title: string, deliveredFeedback?: string): PracticeStandingObservation => ({
	observationId: "o",
	title,
	kind: "OMISSION_GAP",
	workKind: "scm.pull_request",
	reviewedWorkId: 1,
	origin: "LIVE",
	...(deliveredFeedback === undefined ? {} : { deliveredFeedback }),
});

describe("buildProfile", () => {
	it("keeps every catalog group and practice, calling what has no standing not observed yet", () => {
		const profile = buildProfile({
			groups: [group("testing", 1)],
			groupStandings: [],
			reviewed: [reviewed("tests-with-change", "testing")],
			practiceStandings: [],
		});
		expect(profile).toHaveLength(1);
		expect(profile[0]?.standing).toBe("NOT_OBSERVED");
		expect(
			profile[0]?.practices.map((practice) => [practice.slug, practice.standing]),
		).toStrictEqual([["tests-with-change", "NOT_OBSERVED"]]);
	});

	it("leaves out standings for groups and practices the catalog does not show", () => {
		const profile = buildProfile({
			groups: [group("testing", 1)],
			groupStandings: [groupStanding("hidden", "DEVELOPING")],
			reviewed: [reviewed("a", "testing")],
			practiceStandings: [practiceStanding("not-reviewed", "DEVELOPING", { groupSlug: "testing" })],
		});
		expect(profile.map((entry) => entry.group.slug)).toStrictEqual(["testing"]);
		expect(profile[0]?.practices.map((practice) => practice.slug)).toStrictEqual(["a"]);
	});

	it("orders groups most in need of attention first, then as the workspace orders them", () => {
		const profile = buildProfile({
			groups: [group("quiet", 1), group("strong", 2), group("weak", 3), group("mixed", 4)],
			groupStandings: [
				groupStanding("strong", "STRENGTH"),
				groupStanding("weak", "DEVELOPING"),
				groupStanding("mixed", "MIXED"),
			],
			reviewed: [],
			practiceStandings: [],
		});
		expect(profile.map((entry) => entry.group.slug)).toStrictEqual([
			"weak",
			"mixed",
			"strong",
			"quiet",
		]);
	});

	it("orders practices by standing and keeps the catalog order among equals", () => {
		const profile = buildProfile({
			groups: [group("g", 1)],
			groupStandings: [],
			reviewed: [reviewed("first", "g"), reviewed("second", "g"), reviewed("third", "g")],
			practiceStandings: [practiceStanding("third", "DEVELOPING")],
		});
		expect(profile[0]?.practices.map((practice) => practice.slug)).toStrictEqual([
			"third",
			"first",
			"second",
		]);
	});
});

describe("the profile's summary", () => {
	const profile = buildProfile({
		groups: [group("a", 1), group("b", 2), group("c", 3)],
		groupStandings: [
			groupStanding("a", "STRENGTH", "Keep it up."),
			groupStanding("b", "DEVELOPING", "  Test the retry limit.  "),
		],
		reviewed: [],
		practiceStandings: [],
	});

	it("counts groups with observations without scoring them", () => {
		expect(tallyText(profile.filter(isObserved))).toBe("1 needs attention · 1 going well");
	});

	it("keeps groups nothing has observed as quiet coverage, not a result", () => {
		expect(coverageNote(profile)).toBe("1 more practice group is not observed yet.");
		const fresh = buildProfile({
			groups: [group("a", 1)],
			groupStandings: [],
			reviewed: [],
			practiceStandings: [],
		});
		expect(coverageNote(fresh)).toBeUndefined();
	});

	it("leads with the server's guidance for the group most in need of attention", () => {
		expect(leadingNextStep(profile)).toMatchObject({
			entry: { group: { slug: "b" } },
			guidance: "Test the retry limit.",
		});
	});

	it("leads with nothing when no observed group has guidance", () => {
		const quiet = buildProfile({
			groups: [group("a", 1)],
			groupStandings: [groupStanding("a", "NO_OPPORTUNITY", "Nothing applied.")],
			reviewed: [],
			practiceStandings: [],
		});
		expect(leadingNextStep(quiet)).toBeUndefined();
	});
});

describe("basedOn", () => {
	it("names the reviewed work in the provider's words", () => {
		expect(
			basedOn(
				[
					{ workKind: "scm.pull_request", count: 9 },
					{ workKind: "scm.issue", count: 1 },
				],
				"GITLAB",
			),
		).toBe("Based on 9 merge requests and 1 issue");
		expect(basedOn([], "GITHUB")).toBeUndefined();
	});
});

describe("practiceNextStep", () => {
	const entry = (
		standing: PracticeStanding["standing"],
		verdict?: Partial<PracticeStanding>,
		whatGoodLooksLike?: string,
	) => ({
		slug: "p",
		name: "Include tests",
		groupSlug: "g",
		standing,
		verdict: verdict === undefined ? undefined : practiceStanding("p", standing, verdict),
		whyItMatters: undefined,
		whatGoodLooksLike,
	});

	it("quotes the feedback delivered about the latest thing to work on", () => {
		expect(
			practiceNextStep(
				entry("DEVELOPING", {
					toWorkOn: [observation("Cover the retry", "Add a test for the last retry.")],
				}),
			),
		).toBe("Add a test for the last retry.");
	});

	it("falls back to the observation's title only when it says more than the practice name", () => {
		expect(
			practiceNextStep(entry("DEVELOPING", { toWorkOn: [observation("Include tests")] }, "Good")),
		).toBe("Good");
		expect(
			practiceNextStep(entry("DEVELOPING", { toWorkOn: [observation("Cover the retry")] })),
		).toBe("Cover the retry");
	});

	it("says what fits a standing with nothing to act on", () => {
		expect(practiceNextStep(entry("STRENGTH", {}, "Small, focused changes."))).toBe(
			"Keep doing this: Small, focused changes.",
		);
		expect(practiceNextStep(entry("NOT_OBSERVED"))).toContain("No focused next step yet");
		expect(practiceNextStep(entry("NO_OPPORTUNITY", {}))).toContain("Nothing to act on yet");
	});
});
