import { describe, expect, it } from "vitest";

import {
	evidenceQualityRequirement,
	groupEvidenceSources,
} from "@/components/admin/practice-editor/evidence-presentation";
import { knownEvidenceSourceKinds } from "@/components/practice-vocabulary/evidence-source-defs";
import {
	mockDocumentWorkType,
	mockPracticeDefinitionOptions,
	mockPullRequestWorkType,
} from "@/mocks/fixtures/practice";

describe("groupEvidenceSources", () => {
	it("files every source the catalogue ships under a heading that names it", () => {
		const orphans = mockPracticeDefinitionOptions.workTypes.flatMap((workType) =>
			groupEvidenceSources(workType.allowedSources)
				.filter((group) => group.family === "other")
				.flatMap((group) => group.sources.map((source) => source.sourceKind)),
		);

		expect(orphans).toStrictEqual([]);
	});

	// The vocabulary and the family table live in different directories, and a kind added to one and
	// not the other degrades quietly: it files itself under "Other sources", which reads as an answer.
	it("gives every kind the vocabulary knows a family, not just the ones this catalogue ships", () => {
		const unfiled = knownEvidenceSourceKinds().filter(
			(sourceKind) =>
				groupEvidenceSources([
					{
						sourceKind,
						displayName: sourceKind,
						description: "",
						selectionScope: "",
						privacyClass: "INTERNAL",
						requiredQuality: "ANY_CAPTURE",
						supportsExhaustiveEvidence: false,
					},
				])[0]?.family === "other",
		);

		expect(unfiled).toStrictEqual([]);
	});

	// Both ways grouping can stop being a partition are silent: a source filed under no family
	// disappears from the screen, and families emitted in source order bury the work itself.
	it("partitions a pull request's sources, the work itself first and history last", () => {
		const groups = groupEvidenceSources(mockPullRequestWorkType.allowedSources);
		const filed = groups.flatMap((group) => group.sources.map((source) => source.sourceKind));

		expect(groups.map((group) => group.family)).toStrictEqual(["work", "around", "history"]);
		expect([...filed].sort()).toStrictEqual(
			mockPullRequestWorkType.allowedSources.map((source) => source.sourceKind).sort(),
		);
	});

	it("drops a family the work type has no source for", () => {
		expect(
			groupEvidenceSources(mockDocumentWorkType.allowedSources).map((group) => group.family),
		).toStrictEqual(["work", "history"]);
	});

	it("keeps a source this build has not been taught, under its own heading", () => {
		const groups = groupEvidenceSources([
			{
				sourceKind: "wiki.page.body",
				displayName: "Wiki page",
				description: "A page of the wiki.",
				selectionScope: "One page, whole.",
				privacyClass: "INTERNAL",
				requiredQuality: "ANY_CAPTURE",
				supportsExhaustiveEvidence: false,
			},
		]);

		expect(groups.map((group) => group.family)).toStrictEqual(["other"]);
	});
});

describe("evidenceQualityRequirement", () => {
	it("says nothing where the answer is the norm", () => {
		expect(evidenceQualityRequirement("ANY_CAPTURE")).toBeNull();
		expect(evidenceQualityRequirement(undefined)).toBeNull();
	});

	it("names the stricter contracts, which are the ones that turn reviews away", () => {
		expect(evidenceQualityRequirement("COMPLETE")).toBe("Must be captured whole");
		expect(evidenceQualityRequirement("COMPLETE_AND_NON_EMPTY")).toBe(
			"Must be captured whole, and not be empty",
		);
	});
});
