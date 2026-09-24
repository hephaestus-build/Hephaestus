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

/** The fields of an admitted observation the same-lines grouping reads. */
export interface CitedObservation {
	id: string;
	practiceSlug: string;
	citations: readonly Record<string, unknown>[];
	[key: string]: unknown;
}

/**
 * The NEGATIVE observations that quote the same lines, grouped: two practices that cite one line
 * usually measured one event from two angles, and the composer's rule is one message per event. The
 * grouping names the candidates; whether they are one event is the composer's call.
 */
export function sameLinesNote(observations: readonly CitedObservation[]): string {
	const byLine = new Map<string, CitedObservation[]>();
	for (const observation of observations) {
		if (observation.outcome !== "NEGATIVE") {
			continue;
		}
		const lines = new Set(
			observation.citations
				.filter((c) => typeof c.path === "string" && typeof c.startLine === "number")
				.map((c) => `${String(c.path)}:${String(c.startLine)}`),
		);
		for (const line of lines) {
			byLine.set(line, [...(byLine.get(line) ?? []), observation]);
		}
	}
	const groups = [...byLine.entries()]
		.filter(([, members]) => new Set(members.map((m) => m.practiceSlug)).size > 1)
		.map(
			([line, members]) =>
				`${line}: ${members.map((m) => `${m.practiceSlug} (${m.id})`).join(", ")}`,
		);
	return groups.length === 0
		? ""
		: `NEGATIVE measurements that quote the same line, so likely one event seen from two practices — one message, the best-named practice as its practiceSlug and the rest in basedOn, unless they are separate events:\n${groups.map((g) => `- ${g}`).join("\n")}\n\n`;
}

/** Unevaluated practices support neither positive nor negative claims. */
export function notReachedNote(notReached: readonly string[]): string {
	if (notReached.length === 0) {
		return "";
	}
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
	if (unknown !== undefined) {
		// The ids of this practice's own observations are named, so the correction is one edit away:
		// a session that wrote a digest or a citation here is looking at the wrong field.
		const own = [...observationPractices]
			.filter(([, practice]) => practice === primaryPractice)
			.map(([id]) => id);
		const hint =
			own.length > 0
				? `the admitted observation id(s) of ${primaryPractice} are: ${own.join(", ")}`
				: `no admitted observation belongs to ${primaryPractice}`;
		return `Evidence '${unknown}' does not name an admitted observation from this run (basedOn takes the \`id\` field of work/composition/observations.json; ${hint}); skipped.`;
	}
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
		if (unit.action !== "SUPERSEDE") {
			return false;
		}
		const target = unit.supersedesThreadKey;
		return (
			target === undefined ||
			unit.channel === undefined ||
			unit.practiceSlug === undefined ||
			!prepared.has(`${target}\u0000${unit.channel}\u0000${unit.practiceSlug}`)
		);
	});
}
