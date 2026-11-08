import { describe, expect, it, vi } from "vitest";

import {
	definitionOf,
	mockDefinitionOptions,
	mockPullRequestItemType,
	mockScan,
	practiceValueOf,
} from "./fixtures";
import {
	blankDraft,
	clearedBySave,
	definitionOfDraft,
	draftErrors,
	draftOf,
	NO_GROUP,
	type PracticeDefinitionDraft,
} from "./practice-definition-draft";

const reveals = { revealTechnical: vi.fn(), revealGuide: vi.fn(), revealScript: vi.fn() };

function draft(overrides: Partial<PracticeDefinitionDraft> = {}): PracticeDefinitionDraft {
	return { ...draftOf(practiceValueOf()), ...overrides };
}

function saved(value: PracticeDefinitionDraft) {
	return definitionOfDraft(value, mockPullRequestItemType, mockDefinitionOptions.sourceContract);
}

describe("definitionOfDraft", () => {
	it("gives back an untouched practice exactly as it was loaded", () => {
		expect(saved(draft())).toStrictEqual(practiceValueOf());
		expect(saved(draftOf(practiceValueOf({ scan: mockScan })))).toStrictEqual(
			practiceValueOf({ scan: mockScan }),
		);
	});

	it("saves a blank script as the built-in review", () => {
		expect(saved(draft({ script: "  \n" })).definition.script).toBeNull();
		expect(saved(draft({ script: "export default 1;" })).definition.script).toBe(
			"export default 1;",
		);
	});

	it("keeps no reads, script, scan or limits when people review the practice", () => {
		const value = saved(
			draft({
				script: "export default 1;",
				scan: mockScan,
				reviewedBy: {
					by: "PEOPLE",
					humanReason: { code: "MEETING_NOT_CAPTURED", description: " Decided in a meeting. " },
					limits: [{ code: "RUNTIME_NOT_OBSERVED", description: "Runtime not observed." }],
				},
			}),
		).definition;

		expect(value.reads.sources).toStrictEqual([]);
		expect(value.script).toBeNull();
		expect(value.scan).toBeNull();
		expect(value.reviewedBy).toStrictEqual({
			by: "PEOPLE",
			humanReason: { code: "MEETING_NOT_CAPTURED", description: "Decided in a meeting." },
			limits: [],
		});
	});

	it("drops a saved human reason when the practice goes back to Hephaestus", () => {
		const people = practiceValueOf({
			reviewedBy: {
				by: "PEOPLE",
				humanReason: { code: "MEETING_NOT_CAPTURED", description: "Decided in a meeting." },
				limits: [],
			},
		});
		const value = draftOf(people);
		expect(
			saved({ ...value, reviewedBy: { ...value.reviewedBy, by: "HEPHAESTUS" } }).definition
				.reviewedBy,
		).toStrictEqual({ by: "HEPHAESTUS", humanReason: null, limits: [] });
	});

	it("saves Going well and the other guidance trimmed, and blank guidance as null", () => {
		const { guidance } = saved(
			draft({ goingWell: "  You keep changes small.  ", whyItMatters: " " }),
		).definition;
		expect(guidance.goingWell).toBe("You keep changes small.");
		expect(guidance.whyItMatters).toBeNull();
	});

	it("writes the source contract the options offer and moves the practice out of its group", () => {
		const value = saved(draft({ groupSlug: NO_GROUP }));
		expect(value.groupSlug).toBeNull();
		expect(
			definitionOfDraft(draft(), mockPullRequestItemType, "2.1.0").definition.reads.contract,
		).toBe("2.1.0");
	});

	it("preserves the saved scan block during an unrelated edit and a source contract update", () => {
		const scan = {
			...mockScan,
			include: ["src/**/*.ts", "src/**/*.ts"],
			reads: { ...mockScan.reads, contract: "1.0.0" },
		};
		const loaded = draftOf(practiceValueOf({ scan }));
		const value = definitionOfDraft(
			{ ...loaded, name: "Explain every change" },
			mockPullRequestItemType,
			"2.1.0",
		);
		expect(value.definition.name).toBe("Explain every change");
		expect(value.definition.scan).toStrictEqual(scan);
	});
});

describe("clearedBySave", () => {
	it("names nothing while Hephaestus reviews the practice", () => {
		expect(clearedBySave(draft({ script: "export default 1;" }))).toStrictEqual([]);
	});

	it("names what a save with Nobody removes", () => {
		const value = draft({
			script: "export default 1;",
			scan: mockScan,
		});
		expect(
			clearedBySave({ ...value, reviewedBy: { ...value.reviewedBy, by: "NOBODY" } }),
		).toStrictEqual(["the read sources", "the practice script", "the scan settings"]);
	});
});

const errorsOf = (value: PracticeDefinitionDraft, mode: "create" | "edit" = "edit") =>
	draftErrors(value, mode, mockDefinitionOptions, reveals).summary.map((entry) => entry.message);

describe("draftErrors", () => {
	it("accepts a loaded practice", () => {
		expect(errorsOf(draft())).toStrictEqual([]);
	});

	it("requires a reason when people review the practice", () => {
		const value = draft();
		expect(
			errorsOf({
				...value,
				reviewedBy: { ...value.reviewedBy, by: "PEOPLE" },
			}),
		).toStrictEqual(["Say why people review this practice, in at most 500 characters."]);
	});

	it("requires a source Hephaestus must read", () => {
		expect(errorsOf(draft({ settings: { ...draft().settings, reads: [] } }))).toStrictEqual([
			"Set at least one source to Required. A review cannot run without one.",
		]);
	});

	it("refuses a role the item type does not have, and accepts the work itself", () => {
		expect(errorsOf(draft({ about: "REVIEWER" }))).toStrictEqual([
			"Choose a role this kind of work has, or The work.",
		]);
		expect(errorsOf(draft({ about: null }))).toStrictEqual([]);
	});

	it("lists every refused field of a blank practice in the order the form shows them", () => {
		expect(errorsOf(blankDraft(mockPullRequestItemType), "create")).toStrictEqual([
			"Enter a name of 3 to 128 characters.",
			"Enter what to look for.",
			"Set at least one source to Required. A review cannot run without one.",
			"Enter an identifier of 1–64 lowercase letters, numbers and single hyphens.",
		]);
	});

	it("keeps MERGER where the item type offers it", () => {
		expect(errorsOf(draft({ about: "MERGER" }))).toStrictEqual([]);
		expect(definitionOf({ about: "MERGER" }).about).toBe("MERGER");
	});
});

describe("practice route identifiers", () => {
	it.each(["scans", "reviews", "definition-options"])(
		"refuses reserved %s before submit",
		(slug) => {
			const errors = draftErrors(draft({ slug }), "create", mockDefinitionOptions, reveals);
			expect(errors.summary).toContainEqual(
				expect.objectContaining({
					message: "This identifier is not available. Choose a different one.",
				}),
			);
		},
	);
	it.each(["p", "ab", "scans-summary", "a".repeat(64)])("keeps accepted identifier %s", (slug) => {
		const errors = draftErrors(draft({ slug }), "create", mockDefinitionOptions, reveals);
		expect(errors.byField["practice-slug"]).toBeUndefined();
	});
	it.each(["A", "p--q", "a".repeat(65), "p\n"])("refuses malformed identifier %s", (slug) => {
		const errors = draftErrors(draft({ slug }), "create", mockDefinitionOptions, reveals);
		expect(errors.summary).toContainEqual(
			expect.objectContaining({
				message: "Enter an identifier of 1–64 lowercase letters, numbers and single hyphens.",
			}),
		);
	});
});
