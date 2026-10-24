import type { ReviewedBy, ReviewedByInput, ReviewLimitInput } from "@/api/types.gen";
import { hasText } from "@/lib/text";

import type { ReviewedByKind } from "./review-settings";

/**
 * Who reviews a practice, in words. A plain map rather than a `StatusDefs` registry: Reviewed by is a
 * setting a practice was given, not a state anything is in. Keyed on the wire union, so a value the
 * server adds fails the build here.
 */
export const REVIEWED_BY_LABELS: Record<ReviewedByKind, string> = {
	HEPHAESTUS: "Hephaestus",
	PEOPLE: "People",
	NOBODY: "Nobody",
};

export const REVIEWED_BY_DESCRIPTIONS: Record<ReviewedByKind, string> = {
	HEPHAESTUS:
		"An automated review reads the work and records an observation. When a source it must read cannot be captured, the review is withheld rather than guessed.",
	PEOPLE:
		"A developer, peer or mentor reviews this practice. No automated review runs, and the practice still offers its guidance.",
	NOBODY: "This practice offers guidance without automated review.",
};

export const MAX_REASON_LENGTH = 500;

/** Keep reasons and limits across draft choices; discard incompatible fields only on save. */
export interface ReviewedByDraft {
	by: ReviewedByKind;
	humanReason: ReviewLimitInput;
	limits: ReviewLimitInput[];
}

export function reviewedByDraftOf(reviewedBy: ReviewedBy): ReviewedByDraft {
	return {
		by: reviewedBy.by,
		humanReason: reviewedBy.humanReason ?? { description: "" },
		limits: reviewedBy.limits,
	};
}

export function reviewedByOf(draft: ReviewedByDraft): ReviewedByInput {
	return {
		by: draft.by,
		humanReason: draft.by === "PEOPLE" ? trimmed(draft.humanReason) : null,
		limits: draft.by === "HEPHAESTUS" ? draft.limits.map(trimmed) : [],
	};
}

function trimmed(limit: ReviewLimitInput): ReviewLimitInput {
	return { ...limit, description: limit.description.trim() };
}

export const HUMAN_REASON_FIELD_ID = "practice-human-reason";
export const REVIEWED_BY_FIELD_ID = "practice-reviewed-by";

export function limitFieldId(index: number): string {
	return `practice-limit-description-${index}`;
}

export interface ReviewedByProblem {
	message: string;
	focusId: string;
}

export function reviewedByProblem(draft: ReviewedByDraft): ReviewedByProblem | undefined {
	if (draft.by === "PEOPLE") {
		const reason = draft.humanReason.description;
		if (!hasText(reason.trim()) || reason.length > MAX_REASON_LENGTH) {
			return {
				message: "Say why people review this practice, in at most 500 characters.",
				focusId: HUMAN_REASON_FIELD_ID,
			};
		}
	}
	if (draft.by !== "HEPHAESTUS") {
		return undefined;
	}
	const invalid = draft.limits.findIndex(
		(limit) => !hasText(limit.description.trim()) || limit.description.length > MAX_REASON_LENGTH,
	);
	if (invalid !== -1) {
		return {
			message: "Each limit needs a description of 1–500 characters.",
			focusId: limitFieldId(invalid),
		};
	}
	return new Set(draft.limits.map((limit) => limit.description.trim())).size === draft.limits.length
		? undefined
		: { message: "Describe each limit once.", focusId: REVIEWED_BY_FIELD_ID };
}
