import type { EvidenceCitation, ObservationDetail, PracticeGroupStanding } from "@/api/types.gen";

/**
 * The practice words the web app shows (`webapp/src/components/practice-vocabulary/`), so a person
 * reads the same verdict on either surface. `vocabulary.test.ts` fails when they drift.
 */
interface Def {
	label: string;
	description: string;
}

type Tone = "neutral" | "accent" | "success" | "warning";

export type Standing = PracticeGroupStanding["standing"];

export const STANDING: Record<Standing, Def & { shortLabel: string; tone: Tone }> = {
	DEVELOPING: {
		shortLabel: "Needs attention",
		label: "Needs attention",
		description: "Recent reviews here were mostly problems.",
		tone: "warning",
	},
	MIXED: {
		shortLabel: "Mixed",
		label: "Mixed feedback",
		description: "Recent reviews found both strengths and problems here.",
		tone: "neutral",
	},
	STRENGTH: {
		shortLabel: "Going well",
		label: "Going well",
		description: "Recent reviews here were almost entirely positive.",
		tone: "success",
	},
	NO_OPPORTUNITY: {
		shortLabel: "Nothing to report",
		label: "Nothing to report yet",
		description:
			"Your work was reviewed, but nothing here could be judged: either these practices did not apply to it, or the evidence did not settle the question.",
		tone: "neutral",
	},
	NOT_OBSERVED: {
		shortLabel: "Not observed",
		label: "Not observed yet",
		description: "No practice in this group has been observed in your work yet.",
		tone: "neutral",
	},
};

/** The order standings are listed in, most in need of attention first, as on the web. */
export const STANDING_ORDER: readonly Standing[] = [
	"DEVELOPING",
	"MIXED",
	"STRENGTH",
	"NO_OPPORTUNITY",
	"NOT_OBSERVED",
];

export const TREND: Record<NonNullable<PracticeGroupStanding["direction"]>, Def> = {
	IMPROVING: {
		label: "More positive recently",
		description: "Recent reviewed work carried more strengths than the stretch before it.",
	},
	DECLINING: {
		label: "More difficulties recently",
		description: "Recent reviewed work carried more problems than the stretch before it.",
	},
	UNCERTAIN: {
		label: "Direction unclear",
		description: "The two stretches were compared and did not separate far enough to call.",
	},
	INSUFFICIENT_EVIDENCE: {
		label: "Not enough to compare yet",
		description: "There is not yet enough reviewed work on both sides to compare.",
	},
};

export const FEEDBACK_RESOLUTION = {
	ADDRESSED: { label: "Addressed", description: "You changed the work in response to this." },
	DISPUTED: {
		label: "Disputed",
		description: "You disagree with this observation. Say why, so a human can weigh it.",
	},
	NOT_APPLICABLE: {
		label: "Not applicable",
		description: "The observation does not apply to this work.",
	},
} as const satisfies Record<string, Def>;

export const FEEDBACK_USEFULNESS = {
	HELPFUL: { label: "Helpful", description: "This told you something you could act on." },
	UNHELPFUL: { label: "Not helpful", description: "This was not worth the read." },
} as const satisfies Record<string, Def>;

/** What one observation found, in the words the web's practice profile uses. */
export const OUTCOME = {
	POSITIVE: {
		label: "Positive outcome",
		description:
			"Desirable behaviour is present, or undesirable behaviour is absent from the applicable, fully searched evidence.",
	},
	NEGATIVE: {
		label: "Negative outcome",
		description: "Undesirable behaviour is present, or required desirable behaviour is missing.",
	},
} as const satisfies Record<NonNullable<ObservationDetail["outcome"]>, Def>;

/** Why an observation has no outcome: nothing to judge, or evidence that did not settle it. */
export const UNASSESSED = {
	NOT_APPLICABLE: {
		label: "Not applicable",
		description: "Nothing in this work called for the practice, so there was nothing to judge.",
	},
	UNDETERMINED: {
		label: "Undetermined",
		description:
			"The evidence was captured and read, but it did not settle the question. This is not a collection failure.",
	},
} as const satisfies Record<Exclude<ObservationDetail["assessmentStatus"], "ASSESSED">, Def>;

export const SEVERITY = {
	CRITICAL: {
		label: "Critical",
		description: "Serious enough to hold up the work until it is addressed.",
	},
	MAJOR: { label: "Major", description: "Worth fixing before this work is considered done." },
	MINOR: {
		label: "Minor",
		description: "Worth knowing about, but it does not block anything.",
	},
	INFO: {
		label: "Informational",
		description: "Context for the author, with nothing being asked of them.",
	},
} as const satisfies Record<NonNullable<ObservationDetail["severity"]>, Def>;

/**
 * An observation made under review rules that have changed since, or whose rules were not recorded. A
 * current one says nothing: it is what every observation is expected to be.
 */
export const NOT_CURRENT = {
	STALE: {
		label: "Historical observation",
		description: "The practice or the reviewed work changed after this observation.",
	},
	UNVERIFIABLE: {
		label: "Rules version unknown",
		description:
			"The record of which practice text the review read was not kept, so there is no way to say whether the practice has changed since. Treat it as you would any observation you have not checked.",
	},
} as const satisfies Record<Exclude<ObservationDetail["claimCurrentness"], "CURRENT">, Def>;

/** What occasioned a review, for any origin but the ordinary one: the work changing. */
export const NOT_LIVE_ORIGIN = {
	BACKFILL: "Reviewed while catching up on work that predates the connection.",
	MANUAL: "Someone asked for this review.",
} as const satisfies Record<Exclude<ObservationDetail["origin"], "LIVE">, string>;

/** Which side of a change a quote was taken from. */
export const DIFF_SIDE = {
	OLD: "Before",
	NEW: "After",
} as const satisfies Record<NonNullable<EvidenceCitation["side"]>, string>;
