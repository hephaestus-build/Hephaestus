import type { FeedbackModeAssignment, FeedbackModeRollup } from "@/api/types.gen";

import { andList } from "@/lib/text";

export type FeedbackMode = FeedbackModeAssignment["effective"];

/** The level that set a feedback mode. A practice inherits from its group, then the workspace. */
export type FeedbackModeLevel = FeedbackModeAssignment["source"];

export const FEEDBACK_MODE_ORDER = [
	"OFF",
	"APPROVE_FIRST",
	"AUTOMATIC",
] as const satisfies readonly FeedbackMode[];

export const FEEDBACK_MODE_LABELS: Record<FeedbackMode, string> = {
	OFF: "Off",
	APPROVE_FIRST: "Approve first",
	AUTOMATIC: "Automatic",
};

export const FEEDBACK_MODE_DESCRIPTIONS: Record<FeedbackMode, string> = {
	OFF: "Off keeps observations and stops feedback on the work, on practice pages, and in conversation.",
	APPROVE_FIRST:
		"Feedback on the work waits for an admin to approve it. Feedback on practice pages and in conversations needs no approval.",
	AUTOMATIC: "Feedback is sent without waiting for approval.",
};

export const WORKSPACE_DEFAULT_SOURCE = "the workspace default";

/**
 * Where a feedback mode came from, relative to the level that shows it. A total answer, so a caller
 * cannot invent one.
 */
export type FeedbackModeSource = { kind: "inherited"; from: string } | { kind: "chosen" };

export function feedbackModeSourceOf(
	assignment: FeedbackModeAssignment,
	level: FeedbackModeLevel,
	inheritedFrom: string | null,
): FeedbackModeSource {
	if (assignment.source === level) {
		return { kind: "chosen" };
	}
	if (assignment.source === "WORKSPACE" || assignment.source === "INSTANCE") {
		return { kind: "inherited", from: WORKSPACE_DEFAULT_SOURCE };
	}
	return { kind: "inherited", from: inheritedFrom ?? "its group" };
}

export function feedbackModeSourceSentence(source: FeedbackModeSource): string {
	return source.kind === "inherited" ? `Follows ${source.from}` : "Set for this practice";
}

export function feedbackModeCounts(
	rollup: Pick<FeedbackModeRollup, "off" | "approveFirst" | "automatic">,
): Record<FeedbackMode, number> {
	return { OFF: rollup.off, APPROVE_FIRST: rollup.approveFirst, AUTOMATIC: rollup.automatic };
}

export interface FeedbackModeCount {
	feedbackMode: FeedbackMode;
	count: number;
}

export function feedbackModeDistribution(
	counts: Record<FeedbackMode, number>,
): FeedbackModeCount[] {
	return FEEDBACK_MODE_ORDER.map((feedbackMode) => ({
		feedbackMode,
		count: counts[feedbackMode],
	})).filter(({ count }) => count > 0);
}

export function feedbackModeTotal(counts: Record<FeedbackMode, number>): number {
	return FEEDBACK_MODE_ORDER.reduce((total, feedbackMode) => total + counts[feedbackMode], 0);
}

export function feedbackModeDistributionSentence(counts: Record<FeedbackMode, number>): string {
	const parts = feedbackModeDistribution(counts).map(
		({ feedbackMode, count }) =>
			`${count} set to ${FEEDBACK_MODE_LABELS[feedbackMode].toLowerCase()}`,
	);
	if (parts.length === 0) {
		return "No practices yet.";
	}
	const total = feedbackModeTotal(counts);
	return `${total} ${total === 1 ? "practice" : "practices"}: ${andList.format(parts)}.`;
}
