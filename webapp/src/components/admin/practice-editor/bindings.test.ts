import { describe, expect, it } from "vitest";

import type { PracticeAutomatedReviewPolicy, PracticeBinding } from "@/api/types.gen";
import {
	artifactKindOfBindings,
	artifactKindOfSignal,
	bindingsProblem,
	normalizeBinding,
	orderedWorkTypes,
	recommendedBinding,
	roleOf,
	soleBinding,
	withRole,
} from "@/components/admin/practice-editor/bindings";
import {
	mockMergeBinding,
	mockPracticeDefinitionOptions,
	mockPullRequestWorkType,
} from "@/mocks/fixtures/practice";

const aiSupported = mockPullRequestWorkType.recommendedPolicy;
const guidanceOnly: PracticeAutomatedReviewPolicy = {
	...aiSupported,
	automatedReview: { mode: "NONE", evidenceSufficiency: "NONE" },
	knownLimitations: [],
};

function binding(overrides: Partial<PracticeBinding> = {}): PracticeBinding {
	return {
		signals: ["scm.pull_request.opened"],
		needs: [{ sourceKind: "scm.pull-request.core", stance: "REQUIRED" }],
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

	it("does not invent a kind for a set of bindings that names no signal", () => {
		expect(artifactKindOfBindings([])).toBeUndefined();
		expect(artifactKindOfBindings([binding({ signals: [] })])).toBeUndefined();
	});
});

describe("soleBinding", () => {
	it("reads the one occasion a practice is reviewed on", () => {
		expect(soleBinding([mockMergeBinding])).toBe(mockMergeBinding);
	});

	it("answers a practice with no occasion with an empty one, so there is still a strip to tick", () => {
		expect(soleBinding([])).toStrictEqual({ signals: [], needs: [] });
	});

	it("reads a practice stored with two occasions as its first, which is what saving it leaves", () => {
		expect(soleBinding([binding(), mockMergeBinding])).toStrictEqual(binding());
	});
});

describe("normalizeBinding", () => {
	it("puts a binding in the shape the server stores it so an untouched form is not dirty", () => {
		const normalized = normalizeBinding({
			signals: ["scm.pull_request.ready", "scm.pull_request.opened", "scm.pull_request.ready"],
			needs: [
				{ sourceKind: "scm.pull-request.diff", stance: "REQUIRED" },
				{ sourceKind: "scm.pull-request.core", stance: "REQUIRED" },
			],
		});

		expect(normalized.signals).toStrictEqual(["scm.pull_request.opened", "scm.pull_request.ready"]);
		expect(normalized.needs.map((need) => need.sourceKind)).toStrictEqual([
			"scm.pull-request.core",
			"scm.pull-request.diff",
		]);
	});

	it("omits onDrafts entirely when it is false, matching a binding that never mentions drafts", () => {
		expect(normalizeBinding(binding({ onDrafts: false }))).not.toHaveProperty("onDrafts");
		expect(normalizeBinding(binding({ onDrafts: true })).onDrafts).toBe(true);
	});
});

describe("withRole", () => {
	it("moves a source between stances without leaving the old one behind", () => {
		const contextual = withRole(binding().needs, "scm.pull-request.core", "CONTEXTUAL");

		expect(contextual).toStrictEqual([
			{ sourceKind: "scm.pull-request.core", stance: "CONTEXTUAL" },
		]);
	});

	it("drops the source when it stops being used", () => {
		expect(withRole(binding().needs, "scm.pull-request.core", "NOT_USED")).toStrictEqual([]);
		expect(roleOf(binding().needs, "scm.repository.tree")).toBe("NOT_USED");
	});

	it("keeps the needs sorted the way the server stores them", () => {
		const added = withRole(binding().needs, "scm.pull-request.comments", "CONTEXTUAL");

		expect(added.map((need) => need.sourceKind)).toStrictEqual([
			"scm.pull-request.comments",
			"scm.pull-request.core",
		]);
	});
});

describe("recommendedBinding", () => {
	it("starts a practice on the recommended moments with the recommended evidence", () => {
		const fresh = recommendedBinding(mockPullRequestWorkType);

		expect(fresh.signals).toStrictEqual([
			"scm.pull_request.opened",
			"scm.pull_request.ready",
			"scm.pull_request.synchronized",
		]);
		expect(fresh.needs).toStrictEqual(mockPullRequestWorkType.recommendedNeeds);
	});

	it("falls back to the first moment on a work type that recommends none", () => {
		const nothingRecommended = {
			...mockPullRequestWorkType,
			signals: mockPullRequestWorkType.signals.map((option) => ({ ...option, recommended: false })),
		};

		expect(recommendedBinding(nothingRecommended).signals).toStrictEqual([
			"scm.pull_request.opened",
		]);
	});

	// The hand-asked review is carried apart from the moments on the wire, so nothing has to filter it
	// back out here — a binding seeded with it would never fire on its own.
	it("never seeds the occasion with the hand-asked review", () => {
		expect(recommendedBinding(mockPullRequestWorkType).signals).not.toContain(
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

describe("bindingsProblem", () => {
	it("accepts the shape a new practice starts in", () => {
		expect(
			bindingsProblem(
				recommendedBinding(mockPullRequestWorkType),
				aiSupported,
				mockPullRequestWorkType,
			),
		).toBeUndefined();
	});

	it("refuses a practice with nothing to start it, which the server answers with a 500", () => {
		const problem = bindingsProblem(binding({ signals: [] }), aiSupported, mockPullRequestWorkType);

		expect(problem?.message).toBe("Choose when this practice is reviewed.");
		expect(problem?.focusId).toBe("practice-occasion-signals");
	});

	it("refuses a moment that belongs to another kind of work", () => {
		expect(
			bindingsProblem(
				binding({ signals: ["scm.issue.opened"] }),
				aiSupported,
				mockPullRequestWorkType,
			)?.message,
		).toBe("One of the chosen moments does not apply to this kind of work.");
	});

	it("refuses the hand-asked review, which the wire no longer offers as a moment", () => {
		expect(
			bindingsProblem(
				binding({ signals: ["scm.pull_request.manual_review"] }),
				aiSupported,
				mockPullRequestWorkType,
			)?.message,
		).toBe("One of the chosen moments does not apply to this kind of work.");
	});

	it("holds the review to naming evidence it cannot run without", () => {
		const problem = bindingsProblem(
			binding({ needs: [{ sourceKind: "scm.pull-request.core", stance: "CONTEXTUAL" }] }),
			aiSupported,
			mockPullRequestWorkType,
		);

		expect(problem?.message).toBe("This review needs at least one source it cannot run without.");
		expect(problem?.focusId).toBe("practice-occasion-evidence");
	});

	it("counts an exhaustive stance as evidence the review cannot run without", () => {
		expect(
			bindingsProblem(
				binding({ needs: [{ sourceKind: "scm.review-threads", stance: "EXHAUSTIVE" }] }),
				aiSupported,
				mockPullRequestWorkType,
			),
		).toBeUndefined();
	});

	it("refuses an absence claim resting on a source that can never be captured whole", () => {
		expect(
			bindingsProblem(
				binding({ needs: [{ sourceKind: "scm.linked-work-items", stance: "EXHAUSTIVE" }] }),
				aiSupported,
				mockPullRequestWorkType,
			)?.message,
		).toBe(
			"One source can never be captured whole, so nothing this review says about what is absent from it can rest on it.",
		);
	});

	it("refuses evidence on a practice that runs no automated review", () => {
		expect(bindingsProblem(binding(), guidanceOnly, mockPullRequestWorkType)?.message).toBe(
			"Guidance only cannot read any evidence.",
		);
		expect(
			bindingsProblem(binding({ needs: [] }), guidanceOnly, mockPullRequestWorkType),
		).toBeUndefined();
	});
});
