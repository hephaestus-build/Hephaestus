import { describe, expect, it } from "vitest";

import type { PracticeAutomatedReviewPolicy } from "@/api/types.gen";
import {
	type PracticeReviewFields,
	artifactKindOfSignals,
	artifactKindOfSignal,
	reviewSettingsProblem,
	normalizeReviewSettings,
	orderedWorkTypes,
	recommendedReviewSettings,
	roleOf,
	withRole,
} from "@/components/admin/practice-editor/review-settings";
import { mockPracticeDefinitionOptions, mockPullRequestWorkType } from "@/mocks/fixtures/practice";

const aiSupported = mockPullRequestWorkType.recommendedPolicy;
const guidanceOnly: PracticeAutomatedReviewPolicy = {
	...aiSupported,
	automatedReview: { mode: "NONE", evidenceSufficiency: "NONE" },
	knownLimitations: [],
};

function reviewFields(overrides: Partial<PracticeReviewFields> = {}): PracticeReviewFields {
	return {
		signals: ["scm.pull_request.opened"],
		reviewWhen: {},
		subject: "AUTHOR",
		evidenceRequirements: [{ sourceKind: "scm.pull-request.core", stance: "REQUIRED" }],
		...overrides,
	};
}

describe("artifactKindOfSignal", () => {
	it("reads the kind off everything before the last dot, as the server does", () => {
		expect(artifactKindOfSignal("scm.pull_request.merged")).toBe("scm.pull_request");
		expect(artifactKindOfSignal("chat.conversation_thread.settled")).toBe(
			"chat.conversation_thread",
		);
	});

	it("does not invent a kind for a occasion that names no signal", () => {
		expect(artifactKindOfSignals([])).toBeUndefined();
	});
});

describe("normalizeReviewSettings", () => {
	it("puts review fields in the shape the server stores them so an untouched form is not dirty", () => {
		const normalized = normalizeReviewSettings(
			reviewFields({
				signals: ["scm.pull_request.ready", "scm.pull_request.opened", "scm.pull_request.ready"],
				evidenceRequirements: [
					{ sourceKind: "scm.pull-request.diff", stance: "REQUIRED" },
					{ sourceKind: "scm.pull-request.core", stance: "REQUIRED" },
				],
			}),
		);

		expect(normalized.signals).toStrictEqual(["scm.pull_request.opened", "scm.pull_request.ready"]);
		expect(normalized.evidenceRequirements.map((need) => need.sourceKind)).toStrictEqual([
			"scm.pull-request.core",
			"scm.pull-request.diff",
		]);
	});

	it("keeps the gate and the person judged", () => {
		const gate = {
			skipReason: "the change has no Swift code",
			anyOf: [{ changedPathMatches: ["**/*.swift"] }],
		};
		expect(
			normalizeReviewSettings(reviewFields({ precondition: gate, subject: "REVIEWER" })),
		).toMatchObject({
			precondition: gate,
			subject: "REVIEWER",
		});
	});

	it("keeps unrestricted and explicit finite review conditions", () => {
		expect(normalizeReviewSettings(reviewFields({ reviewWhen: {} })).reviewWhen).toStrictEqual({});
		expect(
			normalizeReviewSettings(reviewFields({ reviewWhen: { draftStatus: ["NOT_DRAFT"] } }))
				.reviewWhen,
		).toStrictEqual({ draftStatus: ["NOT_DRAFT"] });
	});
});

describe("withRole", () => {
	it("moves a source between stances without leaving the old one behind", () => {
		const contextual = withRole(
			reviewFields().evidenceRequirements,
			"scm.pull-request.core",
			"CONTEXTUAL",
		);

		expect(contextual).toStrictEqual([
			{ sourceKind: "scm.pull-request.core", stance: "CONTEXTUAL" },
		]);
	});

	it("drops the source when it stops being used", () => {
		expect(
			withRole(reviewFields().evidenceRequirements, "scm.pull-request.core", "NOT_USED"),
		).toStrictEqual([]);
		expect(roleOf(reviewFields().evidenceRequirements, "scm.repository.tree")).toBe("NOT_USED");
	});

	it("keeps the evidenceRequirements sorted the way the server stores them", () => {
		const added = withRole(
			reviewFields().evidenceRequirements,
			"scm.pull-request.comments",
			"CONTEXTUAL",
		);

		expect(added.map((need) => need.sourceKind)).toStrictEqual([
			"scm.pull-request.comments",
			"scm.pull-request.core",
		]);
	});
});

describe("recommendedReviewSettings", () => {
	it("starts a practice on the recommended moments with the recommended evidence", () => {
		const fresh = recommendedReviewSettings(mockPullRequestWorkType);

		expect(fresh.signals).toStrictEqual([
			"scm.pull_request.opened",
			"scm.pull_request.ready",
			"scm.pull_request.synchronized",
		]);
		expect(fresh.evidenceRequirements).toStrictEqual(
			mockPullRequestWorkType.recommendedEvidenceRequirements,
		);
	});

	it("falls back to the first moment on a work type that recommends none", () => {
		const nothingRecommended = {
			...mockPullRequestWorkType,
			signals: mockPullRequestWorkType.signals.map((option) => ({ ...option, recommended: false })),
		};

		expect(recommendedReviewSettings(nothingRecommended).signals).toStrictEqual([
			"scm.pull_request.opened",
		]);
	});

	// The hand-asked review is carried apart from the moments on the wire, so nothing has to filter it
	// back out here — a reviewFields seeded with it would never fire on its own.
	it("never seeds the occasion with the hand-asked review", () => {
		expect(recommendedReviewSettings(mockPullRequestWorkType).signals).not.toContain(
			"scm.pull_request.manual_review",
		);
	});
});

describe("orderedWorkTypes", () => {
	it("leads with the kinds this build knows, in the order it offers them", () => {
		const shuffled = {
			...mockPracticeDefinitionOptions,
			workTypes: [...mockPracticeDefinitionOptions.workTypes].sort((left, right) =>
				left.artifactKind.localeCompare(right.artifactKind),
			),
		};

		expect(orderedWorkTypes(shuffled).map((option) => option.artifactKind)).toStrictEqual([
			"scm.pull_request",
			"scm.issue",
			"chat.conversation_thread",
			"docs.document",
		]);
	});

	it("keeps a kind it has never heard of rather than dropping it", () => {
		const withUnknown = {
			...mockPracticeDefinitionOptions,
			workTypes: [
				{ ...mockPullRequestWorkType, artifactKind: "docs.page" },
				...mockPracticeDefinitionOptions.workTypes,
			],
		};

		expect(orderedWorkTypes(withUnknown).map((option) => option.artifactKind)).toStrictEqual([
			"scm.pull_request",
			"scm.issue",
			"chat.conversation_thread",
			"docs.document",
			"docs.page",
		]);
	});
});

describe("reviewSettingsProblem", () => {
	it("accepts the shape a new practice starts in", () => {
		expect(
			reviewSettingsProblem(
				recommendedReviewSettings(mockPullRequestWorkType),
				aiSupported,
				mockPullRequestWorkType,
			),
		).toBeUndefined();
	});

	it("refuses a practice with nothing to start it, before a save", () => {
		const problem = reviewSettingsProblem(
			reviewFields({ signals: [] }),
			aiSupported,
			mockPullRequestWorkType,
		);

		expect(problem?.message).toBe("Choose when this practice is reviewed.");
		expect(problem?.focusId).toBe("practice-occasion-signals");
	});

	it("refuses a moment that belongs to another kind of work", () => {
		expect(
			reviewSettingsProblem(
				reviewFields({ signals: ["scm.issue.opened"] }),
				aiSupported,
				mockPullRequestWorkType,
			)?.message,
		).toBe("One of the chosen moments does not apply to this kind of work.");
	});

	it("refuses the hand-asked review, which the wire no longer offers as a moment", () => {
		expect(
			reviewSettingsProblem(
				reviewFields({ signals: ["scm.pull_request.manual_review"] }),
				aiSupported,
				mockPullRequestWorkType,
			)?.message,
		).toBe("One of the chosen moments does not apply to this kind of work.");
	});

	it("holds the review to naming evidence it cannot run without", () => {
		const problem = reviewSettingsProblem(
			reviewFields({
				evidenceRequirements: [{ sourceKind: "scm.pull-request.core", stance: "CONTEXTUAL" }],
			}),
			aiSupported,
			mockPullRequestWorkType,
		);

		expect(problem?.message).toBe("This review needs at least one source it cannot run without.");
		expect(problem?.focusId).toBe("practice-occasion-evidence");
	});

	it("counts an exhaustive stance as evidence the review cannot run without", () => {
		expect(
			reviewSettingsProblem(
				reviewFields({
					evidenceRequirements: [{ sourceKind: "scm.review-threads", stance: "EXHAUSTIVE" }],
				}),
				aiSupported,
				mockPullRequestWorkType,
			),
		).toBeUndefined();
	});

	it("refuses an absence claim resting on a source that can never be captured whole", () => {
		expect(
			reviewSettingsProblem(
				reviewFields({
					evidenceRequirements: [{ sourceKind: "scm.linked-work-items", stance: "EXHAUSTIVE" }],
				}),
				aiSupported,
				mockPullRequestWorkType,
			)?.message,
		).toBe(
			"One source can never be captured whole, so nothing this review says about what is absent from it can rest on it.",
		);
	});

	it("refuses evidence on a practice that runs no automated review", () => {
		expect(
			reviewSettingsProblem(reviewFields(), guidanceOnly, mockPullRequestWorkType)?.message,
		).toBe("Guidance only cannot read any evidence.");
		expect(
			reviewSettingsProblem(
				reviewFields({ evidenceRequirements: [] }),
				guidanceOnly,
				mockPullRequestWorkType,
			),
		).toBeUndefined();
	});
});

describe("descriptor-supported review conditions", () => {
	it("uses descriptor recommendations and omits unrestricted dimensions", () => {
		expect(recommendedReviewSettings(mockPullRequestWorkType).reviewWhen).toStrictEqual({
			draftStatus: ["NOT_DRAFT"],
		});
	});
	it.each<Record<string, string[]>>([{ state: [] }, { unknown: ["OPEN"] }, { state: ["INVALID"] }])(
		"refuses unsupported or empty selection %j",
		(reviewWhen) => {
			expect(
				reviewSettingsProblem(reviewFields({ reviewWhen }), aiSupported, mockPullRequestWorkType)
					?.message,
			).toBe("Choose review conditions supported by this kind of work.");
		},
	);
});
