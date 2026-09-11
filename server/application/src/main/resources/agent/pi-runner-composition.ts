export const CHANNELS = ["IN_CONTEXT", "IN_APP", "IN_CHAT"] as const;
export type Channel = (typeof CHANNELS)[number];

export const ACTIONS = ["NEW", "SUPERSEDE", "WITHHOLD"] as const;
export type FeedbackAction = (typeof ACTIONS)[number];

/** Partial view for coherence checks; pi-runner.ts owns the complete tool schema. */
export interface ComposedFeedbackUnit {
	action?: FeedbackAction;
	channel?: Channel;
	practiceSlug?: string;
	supersedesThreadKey?: string;
	[key: string]: unknown;
}

export interface PreparedFeedbackTarget {
	threadKey: string;
	channel: Channel;
	practiceSlug: string;
}

export interface ComposedFeedbackEnvelope {
	admissionDigest?: string | null;
	observations?: unknown[];
	preparedTargets?: PreparedFeedbackTarget[];
	units?: ComposedFeedbackUnit[];
	lead?: string | null;
}

/** Unevaluated practices support neither positive nor negative claims. */
export function notReachedNote(notReached: readonly string[]): string {
	if (notReached.length === 0) return "";
	const subject =
		notReached.length === 1 ? "one of its practices" : `${notReached.length} of its practices`;
	return (
		`\nThis review did not settle ${subject}: ${notReached.join(", ")}. ` +
		`Say nothing about them, for or against, and do not describe this review as complete.\n\n`
	);
}

export function validateFeedbackEvidence(
	primaryPractice: string,
	basedOn: readonly string[],
	observationPractices: ReadonlyMap<string, string>,
): string | null {
	const unknown = basedOn.find((id) => !observationPractices.has(id));
	if (unknown)
		return `Evidence '${unknown}' does not name an admitted observation from this run; skipped.`;
	if (!basedOn.some((id) => observationPractices.get(id) === primaryPractice)) {
		return `At least one basedOn observation must belong to the primary practice '${primaryPractice}'; skipped.`;
	}
	return null;
}

export function undeliverableUnits(
	envelope?: ComposedFeedbackEnvelope | null,
): ComposedFeedbackUnit[] {
	const prepared = new Set(
		envelope?.preparedTargets?.map(
			(target) => `${target.threadKey}\u0000${target.channel}\u0000${target.practiceSlug}`,
		),
	);
	return (envelope?.units ?? []).filter((unit) => {
		if (unit.action !== "SUPERSEDE") return false;
		const target = unit.supersedesThreadKey;
		return (
			target === undefined ||
			unit.channel === undefined ||
			unit.practiceSlug === undefined ||
			!prepared.has(`${target}\u0000${unit.channel}\u0000${unit.practiceSlug}`)
		);
	});
}
