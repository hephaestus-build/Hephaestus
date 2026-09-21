import type { AutonomyAssignment, Practice } from "@/api/types.gen";

export type PracticeAutonomy = Practice["autonomy"]["effective"];

export const PRACTICE_AUTONOMY_ORDER = [
	"OFF",
	"HUMAN_APPROVAL",
	"AUTOMATIC",
] as const satisfies readonly PracticeAutonomy[];

export const PRACTICE_AUTONOMY_LABELS: Record<PracticeAutonomy, string> = {
	OFF: "Off",
	HUMAN_APPROVAL: "Review before sending",
	AUTOMATIC: "Send automatically",
};

export const PRACTICE_AUTONOMY_DESCRIPTIONS: Record<PracticeAutonomy, string> = {
	OFF: "Not reviewed at all.",
	HUMAN_APPROVAL: "Reviewed and composed. An authorized reviewer decides whether to send it.",
	AUTOMATIC: "Reviewed, with eligible feedback sent automatically.",
};

export const PRACTICE_AUTONOMY_ADDS: Record<PracticeAutonomy, string> = {
	OFF: "Nothing runs. No review, no record, nothing said.",
	HUMAN_APPROVAL:
		"Adds assisted delivery. Feedback waits for an authorized reviewer to approve or reject it.",
	AUTOMATIC: "Adds automatic delivery. Eligible feedback is sent without waiting for approval.",
};

export const WORKSPACE_DEFAULT_SOURCE = "the workspace default";

/**
 * Where a practice's autonomy came from — a total answer, so a caller cannot invent one.
 */
export type AutonomySource = { kind: "inherited"; from: string } | { kind: "chosen" };

export function autonomySourceOf(
	assignment: AutonomyAssignment,
	inheritedFrom: string | null,
): AutonomySource {
	if (!assignment.inherited) {
		return { kind: "chosen" };
	}
	if (assignment.source === "WORKSPACE") {
		return { kind: "inherited", from: WORKSPACE_DEFAULT_SOURCE };
	}
	return { kind: "inherited", from: inheritedFrom ?? "its group" };
}

export function autonomySourceSentence(source: AutonomySource): string {
	return source.kind === "inherited" ? `Follows ${source.from}` : "Set for this practice";
}

export interface AutonomyCount {
	autonomy: PracticeAutonomy;
	count: number;
}

export function autonomyDistribution(counts: Record<string, number>): AutonomyCount[] {
	return PRACTICE_AUTONOMY_ORDER.map((autonomy) => ({
		autonomy,
		count: counts[autonomy] ?? 0,
	})).filter(({ count }) => count > 0);
}

export function autonomyTotal(counts: Record<string, number>): number {
	return PRACTICE_AUTONOMY_ORDER.reduce((total, autonomy) => total + (counts[autonomy] ?? 0), 0);
}

export function autonomyDistributionSentence(counts: Record<string, number>): string {
	const parts = autonomyDistribution(counts).map(
		({ autonomy, count }) => `${count} ${PRACTICE_AUTONOMY_LABELS[autonomy].toLowerCase()}`,
	);
	if (parts.length === 0) {
		return "No practices yet.";
	}
	const total = autonomyTotal(counts);
	const listed =
		parts.length === 1 ? parts[0] : `${parts.slice(0, -1).join(", ")} and ${parts.at(-1)}`;
	return `${total} ${total === 1 ? "practice" : "practices"}: ${listed}.`;
}
