import {
	type CapturedPublicStatement,
	type PublicReviewHistory,
	SAME_WORK_LIMITS,
} from "./pi-review-brief.ts";

export const CHANNELS = ["IN_CONTEXT", "IN_APP", "IN_CHAT"] as const;
export type Channel = (typeof CHANNELS)[number];

/** The developer's practice pages and the mentor conversation; the reviewed work takes a review instead. */
export const PRIVATE_CHANNELS = ["IN_APP", "IN_CHAT"] as const;
export type PrivateChannel = (typeof PRIVATE_CHANNELS)[number];

export const ACTIONS = ["NEW", "SUPERSEDE", "WITHHOLD"] as const;
export type FeedbackAction = (typeof ACTIONS)[number];

export const WITHHOLD_REASONS = ["NO_MATERIAL_CHANGE", "ALREADY_SAID", "BELOW_BAR"] as const;
export type WithholdReason = (typeof WITHHOLD_REASONS)[number];

/** The reasons that say this work already received the advice, so each names where it was given. */
export const PRIOR_ADVICE_REASONS: ReadonlySet<WithholdReason> = new Set([
	"ALREADY_SAID",
	"NO_MATERIAL_CHANGE",
]);

/** ComposedReview.CONTRACT_VERSION: the server delivers no review from an envelope without it. */
export const REVIEW_CONTRACT_VERSION = 2;

/** ComposedReview's bounds; a review over them is refused so the composer can correct it, never cut. */
export const REVIEW_LIMITS = { summaryChars: 8000, inlineChars: 2000, inlineNotes: 30 } as const;

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

export interface ReviewAnchor {
	observationId: string;
	citationIndex: number;
}

export interface ReviewSummary {
	body: string;
	basedOn: string[];
}

export interface ReviewInlineNote {
	body: string;
	basedOn: string[];
	anchor: ReviewAnchor;
}

export interface ReviewWithheld {
	basedOn: string[];
	reason: WithholdReason;
}

export interface SelectionWithheld {
	basedOn: string[];
	reason: WithholdReason;
	witnessIds?: string[];
}

/** Held only by the composition: the stored review repeats its decisions, never the selection itself. */
export interface ReviewSelection {
	selected: string[];
	withheld: SelectionWithheld[];
}

export interface PriorAdviceWitness {
	eligibleForPriorAdvice: boolean;
}

/** What the reviewed work is told: one summary, the notes it places on lines, and what it leaves unsaid. */
export interface ComposedReview {
	summary: ReviewSummary | null;
	inline: ReviewInlineNote[];
	withheld: ReviewWithheld[];
}

export interface ComposedFeedbackEnvelope {
	contractVersion?: number;
	admissionDigest?: string | null;
	observations?: unknown[];
	preparedTargets?: PreparedFeedbackTarget[];
	units?: ComposedFeedbackUnit[];
	review?: ComposedReview | null;
}

/** The fields of an admitted observation the same-lines grouping reads. */
export interface CitedObservation {
	id: string;
	practiceSlug: string;
	citations: readonly Record<string, unknown>[];
	[key: string]: unknown;
}

/** The fields of an admitted observation a review is checked against. */
export interface ReviewedObservation {
	summaryOnly?: boolean;
	practiceSlug: string;
	outcome: unknown;
	citations: readonly Record<string, unknown>[];
}

/**
 * The NOT_MET observations that quote the same lines, grouped: two practices that cite one line
 * usually measured one event from two angles, and the review says one thing about one event. The
 * grouping names the candidates; whether they are one event is the composer's call.
 */
export function sameLinesNote(observations: readonly CitedObservation[]): string {
	const byLine = new Map<string, CitedObservation[]>();
	for (const observation of observations) {
		if (observation.outcome !== "NOT_MET") {
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
		: `NOT_MET measurements that quote the same line, so likely one event seen from two practices — say it once, resting on all of them in basedOn, unless they are separate events:\n${groups.map((g) => `- ${g}`).join("\n")}\n\n`;
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

/** Private feedback rests on a problem of its own practice; history alone authorizes nothing. */
export function validateFeedbackEvidence(
	primaryPractice: string,
	basedOn: readonly string[],
	observations: ReadonlyMap<string, { practiceSlug: string; outcome: unknown }>,
): string | null {
	const unknown = basedOn.find((id) => !observations.has(id));
	if (unknown !== undefined) {
		// The ids of this practice's own observations are named, so the correction is one edit away:
		// a session that wrote a digest or a citation here is looking at the wrong field.
		const own = [...observations]
			.filter(([, observation]) => observation.practiceSlug === primaryPractice)
			.map(([id]) => id);
		const hint =
			own.length > 0
				? `the admitted observation id(s) of ${primaryPractice} are: ${own.join(", ")}`
				: `no admitted observation belongs to ${primaryPractice}`;
		return `Evidence '${unknown}' does not name an admitted observation from this run (basedOn takes the \`id\` field of the admitted observations; ${hint}); skipped.`;
	}
	if (
		!basedOn.some((id) => {
			const observation = observations.get(id);
			return observation?.practiceSlug === primaryPractice && observation.outcome === "NOT_MET";
		})
	) {
		return `At least one basedOn observation must be NOT_MET for the primary practice '${primaryPractice}'; skipped.`;
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

/** Kind and provider URL distinguish work without treating a display number as its identity. */
export function workIdentity(kind: unknown, url: unknown): string | undefined {
	return typeof kind === "string" &&
		kind.trim() !== "" &&
		typeof url === "string" &&
		url.trim() !== ""
		? `${kind}:${url}`
		: undefined;
}

const isObject = (value: unknown): value is Record<string, unknown> =>
	typeof value === "object" && value !== null && !Array.isArray(value);

/** Where the server writes its own markers into a comment; a text carrying one would be read as the server's. */
const RESERVED_MARKER = "<!--";

/** The tail a broken JSON envelope leaves on a text: DeveloperTextSanitizer.ENVELOPE_TAIL. */
const ENVELOPE_TAIL = /["'\\]*[}\]]["'\\]+\s*$/u;

function listOf(value: unknown): unknown[] {
	if (Array.isArray(value)) {
		return value;
	}
	return value === undefined || value === null ? [] : [value];
}

/**
 * The ids as sent, or null when basedOn is not an array of non-empty strings. Nothing is dropped from it: a text
 * whose support names something that is not an id says something no observation backs, so it is refused whole.
 */
function idsOf(value: unknown): string[] | null {
	if (!Array.isArray(value)) {
		return null;
	}
	const ids: string[] = [];
	for (const id of value) {
		if (typeof id !== "string" || id.trim() === "") {
			return null;
		}
		ids.push(id.trim());
	}
	return ids;
}

const NOT_AN_ID_LIST =
	"basedOn must be an array of observation id strings; a text resting on anything else is refused whole";

function integerOf(value: unknown): number | undefined {
	if (typeof value === "number" && Number.isInteger(value)) {
		return value;
	}
	return typeof value === "string" && /^\d+$/u.test(value.trim()) ? Number(value) : undefined;
}

/** The problems with a text that is published as written: missing, too long, or carrying a reserved artifact. */
function textProblem(value: unknown, limit: number): string | null {
	if (typeof value !== "string" || value.trim() === "") {
		return "body is required: the complete text, as the developer will read it";
	}
	if (value.length > limit) {
		return `body must be at most ${limit} characters; this one is ${value.length}. Shorten it`;
	}
	if (value.includes(RESERVED_MARKER)) {
		return "body may not contain an HTML comment (`<!--`); the server writes its own markers there";
	}
	if (ENVELOPE_TAIL.test(value)) {
		return "body ends in quote and bracket characters left over from a JSON envelope; end it on its last sentence";
	}
	return null;
}

/** The problems with what a part rests on: every id an admitted observation that decided something. */
function supportProblem(
	basedOn: readonly string[] | null,
	observations: ReadonlyMap<string, ReviewedObservation>,
): string | null {
	if (basedOn === null) {
		return NOT_AN_ID_LIST;
	}
	if (basedOn.length === 0) {
		return "basedOn is required: the id of every admitted observation this text speaks about";
	}
	const unknown = basedOn.filter((id) => !observations.has(id));
	if (unknown.length > 0) {
		return `basedOn names ${unknown.join(", ")}, which is not one of the observations this review may rest on (basedOn takes their \`id\` field)`;
	}
	const undecided = basedOn.filter((id) => {
		const outcome = observations.get(id)?.outcome;
		return outcome !== "MET" && outcome !== "NOT_MET";
	});
	if (undecided.length > 0) {
		return `basedOn names ${undecided.join(", ")}, which decided nothing (not MET, not NOT_MET) and cannot carry a claim about the work`;
	}
	return null;
}

function anchorProblem(
	observationId: string,
	citationIndex: number,
	observations: ReadonlyMap<string, ReviewedObservation>,
	anchors: ReadonlySet<string>,
): string | null {
	const citation = observations.get(observationId)?.citations[citationIndex];
	if (citation === undefined) {
		return `${observationId} has no citation ${citationIndex}`;
	}
	if (citation.anchorable !== true) {
		return `citation ${citationIndex} of ${observationId} is not a line of this change, so no note can sit on it`;
	}
	return anchors.has(`${observationId}#${citationIndex}`)
		? `another note already sits on citation ${citationIndex} of ${observationId}`
		: null;
}

function decisionProblems(
	review: ComposedReview,
	observations: ReadonlyMap<string, ReviewedObservation>,
): string[] {
	const { summary, inline, withheld } = review;
	const errors: string[] = [];
	const said = new Set([...(summary?.basedOn ?? []), ...inline.flatMap((note) => note.basedOn)]);
	const both = [...new Set(withheld.flatMap((decision) => decision.basedOn))].filter((id) =>
		said.has(id),
	);
	if (both.length > 0) {
		errors.push(`${both.join(", ")} is both said and withheld; an observation is one or the other`);
	}
	const held = new Set(withheld.flatMap((decision) => decision.basedOn));
	const missing = [...observations]
		.filter(
			([id, observation]) => observation.outcome === "NOT_MET" && !said.has(id) && !held.has(id),
		)
		.map(([id]) => id);
	if (missing.length > 0) {
		errors.push(
			`${missing.join(", ")} has no decision; speak about each NOT_MET observation or explicitly withhold it`,
		);
	}
	return errors;
}

function withholdReasonOf(value: unknown): WithholdReason | undefined {
	const word = typeof value === "string" ? value.trim().toUpperCase().replaceAll("-", "_") : "";
	return WITHHOLD_REASONS.find((candidate) => candidate === word);
}

function namedTwice(ids: readonly string[]): string[] {
	return [...new Set(ids.filter((id, index) => ids.indexOf(id) !== index))];
}

function inlineSupportProblem(
	basedOn: string[] | null,
	observations: ReadonlyMap<string, ReviewedObservation>,
): string | null {
	return basedOn !== null && basedOn.some((id) => observations.get(id)?.summaryOnly === true)
		? "this note rests on a practice that requires summary placement"
		: null;
}

/**
 * Reads one review as the composer sent it, or every reason it cannot be stored. A review is stored whole or not
 * at all: its parts were written together, so one wrong part sends the whole review back to be corrected.
 *
 * @param lineNotes whether this work has lines a note can be placed on
 */
export function readReview(
	value: unknown,
	observations: ReadonlyMap<string, ReviewedObservation>,
	lineNotes: boolean,
): { review: ComposedReview } | { errors: string[] } {
	if (!isObject(value)) {
		return { errors: ["the review is one object with summary, inline and withheld"] };
	}
	const errors: string[] = [];
	const unknownFields = Object.keys(value).filter(
		(key) => key !== "summary" && key !== "inline" && key !== "withheld",
	);
	if (unknownFields.length > 0) {
		errors.push(
			`unknown review field(s): ${unknownFields.join(", ")} — a review takes summary, inline and withheld`,
		);
	}

	let summary: ReviewSummary | null = null;
	if (value.summary !== undefined && value.summary !== null) {
		const raw = value.summary;
		if (isObject(raw)) {
			const basedOn = idsOf(raw.basedOn);
			const problems = [
				textProblem(raw.body, REVIEW_LIMITS.summaryChars),
				supportProblem(basedOn, observations),
			].filter((problem): problem is string => problem !== null);
			errors.push(...problems.map((problem) => `summary: ${problem}`));
			if (problems.length === 0 && typeof raw.body === "string" && basedOn !== null) {
				summary = { body: raw.body, basedOn: [...new Set(basedOn)] };
			}
		} else {
			errors.push("summary: is one object with body and basedOn");
		}
	}

	const notes = listOf(value.inline);
	if (notes.length > REVIEW_LIMITS.inlineNotes) {
		errors.push(
			`inline: at most ${REVIEW_LIMITS.inlineNotes} line notes; this review has ${notes.length}`,
		);
	}
	if (notes.length > 0 && !lineNotes) {
		errors.push("inline: this work has no lines to place a note on; say it in the summary");
	}
	const inline: ReviewInlineNote[] = [];
	const anchors = new Set<string>();
	for (const [index, raw] of notes.entries()) {
		const label = `inline #${index + 1}`;
		if (!isObject(raw)) {
			errors.push(`${label}: is one object with body, basedOn and anchor`);
			continue;
		}
		const basedOn = idsOf(raw.basedOn);
		const problems = [
			textProblem(raw.body, REVIEW_LIMITS.inlineChars),
			supportProblem(basedOn, observations),
		].filter((problem): problem is string => problem !== null);
		const placementProblem = inlineSupportProblem(basedOn, observations);
		if (placementProblem !== null) {
			problems.push(placementProblem);
		}
		const anchor = isObject(raw.anchor) ? raw.anchor : {};
		const observationId =
			typeof anchor.observationId === "string" ? anchor.observationId.trim() : "";
		const citationIndex = integerOf(anchor.citationIndex);
		if (observationId === "" || citationIndex === undefined) {
			problems.push(
				"anchor needs observationId and citationIndex, the line comes from that citation",
			);
		} else if (basedOn === null) {
			// The support is already refused above; the anchor cannot be checked against it.
		} else if (basedOn.includes(observationId)) {
			const problem = anchorProblem(observationId, citationIndex, observations, anchors);
			if (problem !== null) {
				problems.push(problem);
			}
		} else {
			problems.push(`anchor names ${observationId}, which this note's basedOn does not`);
		}
		errors.push(...problems.map((problem) => `${label}: ${problem}`));
		if (
			problems.length === 0 &&
			typeof raw.body === "string" &&
			citationIndex !== undefined &&
			basedOn !== null
		) {
			anchors.add(`${observationId}#${citationIndex}`);
			inline.push({
				body: raw.body,
				basedOn: [...new Set(basedOn)],
				anchor: { observationId, citationIndex },
			});
		}
	}

	const withheld: ReviewWithheld[] = [];
	for (const [index, raw] of listOf(value.withheld).entries()) {
		const label = `withheld #${index + 1}`;
		if (!isObject(raw)) {
			errors.push(`${label}: is one object with basedOn and reason`);
			continue;
		}
		const basedOn = idsOf(raw.basedOn);
		const reason = withholdReasonOf(raw.reason);
		const problems: string[] = [];
		if (basedOn === null) {
			problems.push(NOT_AN_ID_LIST);
		} else if (basedOn.length === 0) {
			problems.push("basedOn is required: the observation(s) you decided not to raise");
		}
		const notProblems = (basedOn ?? []).filter((id) => observations.get(id)?.outcome !== "NOT_MET");
		if (notProblems.length > 0) {
			problems.push(
				`basedOn names ${notProblems.join(", ")}; only an admitted NOT_MET observation can be withheld`,
			);
		}
		if (reason === undefined) {
			problems.push(`reason must be one of ${WITHHOLD_REASONS.join(", ")}`);
		}
		errors.push(...problems.map((problem) => `${label}: ${problem}`));
		if (problems.length === 0 && reason !== undefined && basedOn !== null) {
			withheld.push({ basedOn: [...new Set(basedOn)], reason });
		}
	}

	errors.push(...decisionProblems({ summary, inline, withheld }, observations));
	return errors.length > 0 ? { errors } : { review: { summary, inline, withheld } };
}

/** A witness proves only where advice was given; whether it made the same point stays the composer's judgement. */
export function readSelection(
	value: unknown,
	observations: ReadonlyMap<string, ReviewedObservation>,
	witnesses: ReadonlyMap<string, PriorAdviceWitness>,
): { selection: ReviewSelection } | { errors: string[] } {
	if (!isObject(value)) {
		return { errors: ["the selection is one object with selected and withheld"] };
	}
	const errors: string[] = [];
	const unknownFields = Object.keys(value).filter(
		(key) => key !== "selected" && key !== "withheld",
	);
	if (unknownFields.length > 0) {
		errors.push(
			`unknown selection field(s): ${unknownFields.join(", ")} — a selection takes selected and withheld`,
		);
	}

	const selected =
		value.selected === undefined || value.selected === null ? [] : idsOf(value.selected);
	if (selected === null) {
		errors.push("selected must be an array of observation id strings");
	} else {
		const unknown = selected.filter((id) => !observations.has(id));
		if (unknown.length > 0) {
			errors.push(
				`selected names ${unknown.join(", ")}, which is not one of the observations this review may rest on (selected takes their \`id\` field)`,
			);
		}
		const undecided = selected.filter((id) => {
			const outcome = observations.get(id)?.outcome;
			return observations.has(id) && outcome !== "MET" && outcome !== "NOT_MET";
		});
		if (undecided.length > 0) {
			errors.push(
				`selected names ${undecided.join(", ")}, which decided nothing (not MET, not NOT_MET) and cannot carry a claim about the work`,
			);
		}
		const twice = namedTwice(selected);
		if (twice.length > 0) {
			errors.push(`selected names ${twice.join(", ")} more than once`);
		}
	}

	const withheld: SelectionWithheld[] = [];
	const heldIds: string[] = [];
	const container = value.withheld;
	if (container !== undefined && container !== null && !Array.isArray(container)) {
		errors.push("withheld must be an array of withholding decisions");
	}
	for (const [index, raw] of (Array.isArray(container) ? container : []).entries()) {
		const label = `withheld #${index + 1}`;
		if (!isObject(raw)) {
			errors.push(
				`${label}: is one object with basedOn, reason and, for a prior advice, witnessIds`,
			);
			continue;
		}
		const basedOn = idsOf(raw.basedOn);
		const reason = withholdReasonOf(raw.reason);
		const witnessIds =
			raw.witnessIds === undefined || raw.witnessIds === null ? [] : idsOf(raw.witnessIds);
		const problems: string[] = [];
		if (basedOn === null) {
			problems.push(NOT_AN_ID_LIST);
		} else if (basedOn.length === 0) {
			problems.push("basedOn is required: the observation(s) you decided not to raise");
		}
		heldIds.push(...(basedOn ?? []));
		const notProblems = (basedOn ?? []).filter((id) => observations.get(id)?.outcome !== "NOT_MET");
		if (notProblems.length > 0) {
			problems.push(
				`basedOn names ${notProblems.join(", ")}; only an admitted NOT_MET observation can be withheld`,
			);
		}
		if (reason === undefined) {
			problems.push(`reason must be one of ${WITHHOLD_REASONS.join(", ")}`);
		}
		if (witnessIds === null) {
			problems.push("witnessIds must be an array of witnessId strings from what was already said");
		} else {
			const unknown = witnessIds.filter((id) => !witnesses.has(id));
			if (unknown.length > 0) {
				problems.push(
					`witnessIds names ${unknown.join(", ")}, which is not a statement shown under what was already said on this work`,
				);
			}
			const ineligible = witnessIds.filter(
				(id) => witnesses.has(id) && witnesses.get(id)?.eligibleForPriorAdvice !== true,
			);
			if (ineligible.length > 0) {
				problems.push(
					`witnessIds names ${ineligible.join(", ")}, which is shown as context but cannot stand as advice this work already received (eligibleForPriorAdvice is false)`,
				);
			}
			if (reason !== undefined && PRIOR_ADVICE_REASONS.has(reason) && witnessIds.length === 0) {
				problems.push(
					`${reason} names in witnessIds where this work already received the advice: the witnessId of a statement marked eligibleForPriorAdvice; without one, raise it or choose another reason`,
				);
			}
		}
		errors.push(...problems.map((problem) => `${label}: ${problem}`));
		if (problems.length === 0 && reason !== undefined && basedOn !== null && witnessIds !== null) {
			withheld.push({
				basedOn: [...new Set(basedOn)],
				reason,
				...(witnessIds.length > 0 ? { witnessIds: [...new Set(witnessIds)] } : {}),
			});
		}
	}

	const twiceHeld = namedTwice(heldIds);
	if (twiceHeld.length > 0) {
		errors.push(
			`${twiceHeld.join(", ")} is withheld more than once; each observation takes one decision`,
		);
	}
	const chosen = new Set(selected);
	const both = [...new Set(heldIds)].filter((id) => chosen.has(id));
	if (both.length > 0) {
		errors.push(
			`${both.join(", ")} is both selected and withheld; an observation is one or the other`,
		);
	}
	const held = new Set(heldIds);
	const missing = [...observations]
		.filter(
			([id, observation]) => observation.outcome === "NOT_MET" && !chosen.has(id) && !held.has(id),
		)
		.map(([id]) => id);
	if (missing.length > 0) {
		errors.push(
			`${missing.join(", ")} has no decision; select each NOT_MET observation to speak about it, or withhold it with its reason`,
		);
	}
	return errors.length > 0 || selected === null
		? { errors }
		: { selection: { selected, withheld } };
}

function withholdingReasons(decisions: readonly { basedOn: string[]; reason: WithholdReason }[]) {
	return new Map(
		decisions.flatMap((decision) => decision.basedOn.map((id) => [id, decision.reason] as const)),
	);
}

/** Withholding decisions are compared per observation, so their grouping may differ from the selection's. */
export function selectionMismatch(review: ComposedReview, selection: ReviewSelection): string[] {
	const errors: string[] = [];
	const spoken = new Set([
		...(review.summary?.basedOn ?? []),
		...review.inline.flatMap((note) => note.basedOn),
	]);
	const chosen = new Set(selection.selected);
	const unselected = [...spoken].filter((id) => !chosen.has(id));
	if (unselected.length > 0) {
		errors.push(
			`the review speaks about ${unselected.join(", ")}, which the accepted selection does not select; leave it out of the text, or select again first`,
		);
	}
	const unspoken = selection.selected.filter((id) => !spoken.has(id));
	if (unspoken.length > 0) {
		errors.push(
			`the accepted selection selects ${unspoken.join(", ")}, which no text speaks about; speak about it, or select again without it`,
		);
	}
	const sent = withholdingReasons(review.withheld);
	const accepted = withholdingReasons(selection.withheld);
	const differing = [...new Set([...sent.keys(), ...accepted.keys()])].filter(
		(id) => sent.get(id) !== accepted.get(id),
	);
	if (differing.length > 0) {
		const expected = [...accepted].map(([id, reason]) => `${id} as ${reason}`);
		errors.push(
			`withheld differs from the accepted selection for ${differing.join(", ")}; it repeats the selection's decisions exactly: ${expected.length > 0 ? expected.join(", ") : "none"}`,
		);
	}
	return errors;
}

/** Every observation the review decided about: what it says and what it withholds. */
export function decidedByReview(review: ComposedReview | null | undefined): Set<string> {
	if (!review) {
		return new Set();
	}
	return new Set([
		...(review.summary?.basedOn ?? []),
		...review.inline.flatMap((note) => note.basedOn),
		...review.withheld.flatMap((decision) => decision.basedOn),
	]);
}

/**
 * The admitted observations the review on the work may rest on, whole: those admission marked publicEligible and
 * that decided something (MET or NOT_MET). The server decides eligibility (PublicReviewEligibility) and admits a
 * composed review by the same rule, so an observation it did not mark is left out entirely rather than shown with
 * parts removed — its words may carry what it drew on. Nothing of a decided observation is shortened; only the
 * verification digests of its citations are dropped, which say nothing a composer can use.
 */
export function publicObservations(
	observations: readonly Record<string, unknown>[],
): Record<string, unknown>[] {
	return observations
		.filter(
			(observation) =>
				observation.publicEligible === true &&
				(observation.outcome === "MET" || observation.outcome === "NOT_MET"),
		)
		.map((observation) => {
			const { evidence, citations, ...rest } = observation;
			const { citations: _measured, ...branches } = isObject(evidence) ? evidence : {};
			return {
				...rest,
				...(Object.keys(branches).length > 0 ? { evidence: branches } : {}),
				citations: (Array.isArray(citations) ? citations : []).map((citation: unknown) => {
					if (!isObject(citation)) {
						return citation;
					}
					const { verification: _verification, ...shown } = citation;
					return shown;
				}),
			};
		});
}

/**
 * The practices this review looked at and could not decide, by slug and outcome only: a bound on what the review
 * may claim, distinct from the practices it never reached. Their prose and citations stay out, since they support
 * no claim about the work.
 */
export function uncertainOutcomes(
	observations: readonly Record<string, unknown>[],
): { practiceSlug: string; outcome: string }[] {
	return observations.flatMap((observation) =>
		observation.outcome === "NOT_APPLICABLE" || observation.outcome === "UNDETERMINED"
			? [{ practiceSlug: String(observation.practiceSlug), outcome: observation.outcome }]
			: [],
	);
}

export interface OwnPriorFeedback {
	/** Null when the history names no feedback id: such an entry is context only. */
	witnessId: string | null;
	id?: unknown;
	reviewedRevision?: unknown;
	basedOn?: unknown;
	deliveredAt: unknown;
	body: unknown;
	recordedClaimCurrentness: unknown;
	withdrawn: unknown;
	eligibleForPriorAdvice: boolean;
}

export interface OwnHistoryOmissions {
	oversizedEntries: number;
	budgetEntries: number;
}

export interface OwnPriorFeedbackView {
	feedback: OwnPriorFeedback[];
	omissions: OwnHistoryOmissions;
}

function ownHistoryText(
	feedback: readonly OwnPriorFeedback[],
	omissions?: OwnHistoryOmissions,
): string {
	const shown =
		feedback.length === 0
			? "No same-work delivered feedback is shown here.\n"
			: `What Hephaestus delivered on this work:\n\`\`\`json\n${JSON.stringify({ alreadySaid: feedback }, null, 1)}\n\`\`\`\n`;
	const omitted =
		omissions !== undefined && (omissions.oversizedEntries > 0 || omissions.budgetEntries > 0)
			? `Prior feedback omitted whole: ${omissions.oversizedEntries} entries exceed the per-entry limit; ${omissions.budgetEntries} exceed the total history limit. Their contents are not shown and cannot be prior-advice witnesses.\n`
			: "";
	return `${shown}${omitted}`;
}

const FEEDBACK_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;

function deliveredBy(deliveredAt: unknown, capturedAt: string | null): boolean {
	if (typeof deliveredAt !== "string" || capturedAt === null) {
		return false;
	}
	const delivered = Date.parse(deliveredAt);
	const captured = Date.parse(capturedAt);
	return Number.isFinite(delivered) && Number.isFinite(captured) && delivered <= captured;
}

/**
 * What was already said on this very piece of work, from the feedback history: only the review on the work, never
 * what reached the developer's own pages or conversations, and never anything about other work. Nothing missing is
 * filled in: an entry without an id, a time or a body is context, never prior advice.
 */
export function priorPublicFeedback(
	history: unknown,
	thisWork: string | undefined,
	capturedAt: string | null,
	limits: { sourceChars: number; totalChars: number } = SAME_WORK_LIMITS,
): OwnPriorFeedbackView {
	const omissions: OwnHistoryOmissions = { oversizedEntries: 0, budgetEntries: 0 };
	if (thisWork === undefined || !isObject(history) || !Array.isArray(history.feedback)) {
		return { feedback: [], omissions };
	}
	const projected = history.feedback.flatMap((entry: unknown): OwnPriorFeedback[] => {
		if (!isObject(entry) || entry.channel !== "IN_CONTEXT" || entry.publicEligible !== true) {
			return [];
		}
		const artifact = isObject(entry.artifact) ? entry.artifact : {};
		if (workIdentity(artifact.kind, artifact.url) !== thisWork) {
			return [];
		}
		const {
			id,
			reviewedRevision,
			basedOn,
			deliveredAt,
			body,
			recordedClaimCurrentness,
			withdrawn,
		} = entry;
		const witnessId = typeof id === "string" && FEEDBACK_ID.test(id) ? `feedback:${id}` : null;
		return [
			{
				witnessId,
				...(id === undefined ? {} : { id }),
				...(reviewedRevision === undefined ? {} : { reviewedRevision }),
				...(basedOn === undefined ? {} : { basedOn }),
				deliveredAt,
				body,
				recordedClaimCurrentness,
				withdrawn,
				eligibleForPriorAdvice:
					witnessId !== null &&
					typeof body === "string" &&
					body.trim() !== "" &&
					recordedClaimCurrentness === "CURRENT" &&
					withdrawn !== true &&
					deliveredBy(deliveredAt, capturedAt),
			},
		];
	});
	const feedback: OwnPriorFeedback[] = [];
	// Reserve the count-only omission notice before adding whole entries; omitted identities are never rendered.
	const reserved = { oversizedEntries: projected.length, budgetEntries: projected.length };
	for (const entry of projected) {
		if (JSON.stringify(entry, null, 1).length > limits.sourceChars) {
			omissions.oversizedEntries += 1;
		} else if (ownHistoryText([...feedback, entry], reserved).length > limits.totalChars) {
			omissions.budgetEntries += 1;
		} else {
			feedback.push(entry);
		}
	}
	return { feedback, omissions };
}

export function priorAdviceWitnesses(
	own: readonly OwnPriorFeedback[],
	captured: readonly CapturedPublicStatement[],
): Map<string, PriorAdviceWitness> {
	const witnesses = new Map<string, PriorAdviceWitness>();
	for (const statement of own) {
		if (statement.witnessId !== null) {
			witnesses.set(statement.witnessId, {
				eligibleForPriorAdvice: statement.eligibleForPriorAdvice,
			});
		}
	}
	for (const statement of captured) {
		witnesses.set(statement.witnessId, {
			eligibleForPriorAdvice: statement.eligibleForPriorAdvice,
		});
	}
	return witnesses;
}

export const SELECTION_TOOL_DESCRIPTION =
	"Choose, before writing, what the review on this piece of work will speak about: the observations it will " +
	"discuss, and each NOT_MET observation you decide not to raise, with your reason. Nothing is published or stored " +
	"by this call. An accepted selection replaces the one before it, until the review is final; a refused selection " +
	"leaves the one before it standing and names every reason.";

/** What report_review tells the model it does. The rules are applied by readReview, with every reason at once. */
export const REVIEW_TOOL_DESCRIPTION =
	"Store the final review on this piece of work: the summary comment, any notes placed on lines of the change, and " +
	"the NOT_MET observations you decided not to raise here. It rests on exactly the accepted selection: it speaks " +
	"about every selected observation and no other, and repeats the selection's withholding decisions. Each body is " +
	"published whole as written, with provider safety formatting and a fixed disclosure; nothing is assembled from " +
	"fragments. An accepted call is final and ends the composition. Invalid support, eligibility, placement or a " +
	"mismatch with the selection refuses the whole review, with every reason, so it can be corrected, or selected " +
	"again, and sent again.";

/** Preserve admission’s complete rows and qualifications; private and unselected rows are not echoed. */
export function selectionText(
	selection: ReviewSelection,
	reviewable: readonly Record<string, unknown>[],
	stagedCriteria: (practiceSlug: string) => string | null,
): string {
	const chosen = new Set(selection.selected);
	const selectedObservations = reviewable.filter(
		(observation) => observation.publicEligible === true && chosen.has(String(observation.id)),
	);
	const practices = [
		...new Set(selectedObservations.map((observation) => String(observation.practiceSlug))),
	];
	const guidance =
		"Write from these selected assessments, preserving their evidence qualifications. A declared affordance " +
		"supports its bounded benefit, not an unobserved runtime or test outcome. Keep the remedy focused on the " +
		"recorded gap and preserve unrelated behavior. Do not make another change a prerequisite unless the admitted " +
		"evidence establishes that dependency.";
	const reference =
		"The criteria the selected assessments were made against, as staged. They explain the standard and the " +
		"responses it accepts; the assessments above alone establish what this review raises.";
	const criteria =
		practices.length === 0
			? ""
			: `\n\n${reference}\n${practices.map((slug) => criteriaBlock(slug, stagedCriteria(slug))).join("\n")}`;
	return `\`\`\`json\n${JSON.stringify({ acceptedSelection: selection, selectedObservations }, null, 1)}\n\`\`\`\n${guidance}${criteria}`;
}

/** One practice's staged criteria, whole and fenced so their own code blocks cannot close it; never cut or rewritten. */
function criteriaBlock(slug: string, criteria: string | null): string {
	if (criteria === null) {
		return `### Criteria of \`${slug}\` — not available for this review; nothing is known about them here\n`;
	}
	if (criteria.length === 0) {
		return `### Criteria of \`${slug}\` — staged empty; no standard is available here\n`;
	}
	const longestRun = Math.max(0, ...[...criteria.matchAll(/`+/gu)].map((run) => run[0].length));
	const fence = "`".repeat(Math.max(3, longestRun + 1));
	return `### Criteria of \`${slug}\`\n${fence}markdown\n${criteria}\n${fence}\n`;
}

/** Offered before the body to orient generation; field order is not enforced. */
const SUPPORT_FIRST =
	"Choose these before writing the body: the id of every observation behind an assessment this body states, and no " +
	"other. An observation named by another body does not support this one. A MET observation supports an " +
	"acknowledgement, never a corrective request. Then write the body from exactly these.";

/** An id list the schema can offer: the ids themselves when there are any, since an empty enum is invalid. */
function idList(ids: readonly string[], description: string) {
	return {
		type: "array",
		minItems: 1,
		items: ids.length > 0 ? { type: "string", enum: [...ids] } : { type: "string" },
		description,
	};
}

/**
 * The parameters of report_review for one run, built from the same observations readReview checks against: each
 * text names only decided observations of this run, a note sits only on one with a line of this change, and only a
 * NOT_MET observation can be withheld. A list with nothing it could name takes no items. The required fields of each
 * part are required here, so a missing one is answered by the provider's own validation; the top-level parts stay
 * optional, since an empty review is a decision. readReview stays the final check and answers each part by name.
 *
 * @param lineNotes whether this work has lines a note can be placed on
 */
export function reviewToolParameters(
	observations: ReadonlyMap<string, ReviewedObservation>,
	lineNotes: boolean,
) {
	const ids = [...observations.keys()];
	const decided = ids.filter((id) => {
		const outcome = observations.get(id)?.outcome;
		return outcome === "MET" || outcome === "NOT_MET";
	});
	if (decided.length === 0) {
		throw new Error("report_review needs at least one decided observation to rest on");
	}
	const notMet = ids.filter((id) => observations.get(id)?.outcome === "NOT_MET");
	const anchorable = lineNotes
		? decided.filter(
				(id) =>
					observations.get(id)?.citations.some((citation) => citation.anchorable === true) === true,
			)
		: [];
	return {
		type: "object",
		properties: {
			summary: {
				type: "object",
				required: ["basedOn", "body"],
				description:
					"The one comment on the work, complete as the developer will read it. Omit it when nothing on " +
					"this work earns a comment of its own.",
				properties: {
					basedOn: idList(
						decided,
						`${SUPPORT_FIRST} If any of them may not go out, the whole comment stays unsaid.`,
					),
					body: {
						type: "string",
						minLength: 1,
						maxLength: REVIEW_LIMITS.summaryChars,
						description: "The whole comment in Markdown, written from the observations in basedOn.",
					},
				},
			},
			inline: {
				type: "array",
				maxItems: anchorable.length > 0 ? REVIEW_LIMITS.inlineNotes : 0,
				description:
					"Notes intended for cited lines of the change, each complete on its own. Each placement can fail " +
					"independently.",
				items: {
					type: "object",
					required: ["basedOn", "body", "anchor"],
					properties: {
						basedOn: idList(decided, `${SUPPORT_FIRST} It includes the anchor's observation.`),
						body: {
							type: "string",
							minLength: 1,
							maxLength: REVIEW_LIMITS.inlineChars,
							description: "The whole note in Markdown, written from the observations in basedOn.",
						},
						anchor: {
							type: "object",
							required: ["observationId", "citationIndex"],
							description:
								"The line, as one citation of an observation in basedOn that is marked anchorable.",
							properties: {
								observationId:
									anchorable.length > 0 ? { type: "string", enum: anchorable } : { type: "string" },
								citationIndex: { type: "integer", minimum: 0 },
							},
						},
					},
				},
			},
			withheld: {
				type: "array",
				maxItems: notMet.length > 0 ? notMet.length : 0,
				description:
					"NOT_MET observations you decided not to raise on this work, with your reason.",
				items: {
					type: "object",
					required: ["basedOn", "reason"],
					properties: {
						basedOn: idList(notMet, "The NOT_MET observation(s) you decided not to raise."),
						reason: { type: "string", enum: [...WITHHOLD_REASONS] },
					},
				},
			},
		},
	};
}

/** readSelection stays the final check; an empty enum is invalid, so an empty list takes no items instead. */
export function selectionToolParameters(
	observations: ReadonlyMap<string, ReviewedObservation>,
	eligibleWitnesses: readonly string[],
) {
	const ids = [...observations.keys()];
	const decided = ids.filter((id) => {
		const outcome = observations.get(id)?.outcome;
		return outcome === "MET" || outcome === "NOT_MET";
	});
	if (decided.length === 0) {
		throw new Error("select_feedback needs at least one decided observation to choose from");
	}
	const notMet = ids.filter((id) => observations.get(id)?.outcome === "NOT_MET");
	return {
		type: "object",
		properties: {
			selected: {
				type: "array",
				items: { type: "string", enum: decided },
				description:
					"The id of each observation the review will speak about: every concern it raises and every " +
					"positive choice it acknowledges, and no other. Leave out a MET observation that earns no words.",
			},
			withheld: {
				type: "array",
				maxItems: notMet.length,
				description:
					"Each NOT_MET observation you decided not to raise on this work, with your reason. Every NOT_MET " +
					"observation is either selected or withheld.",
				items: {
					type: "object",
					required: ["basedOn", "reason"],
					properties: {
						basedOn: idList(notMet, "The NOT_MET observation(s) you decided not to raise."),
						reason: { type: "string", enum: [...WITHHOLD_REASONS] },
						witnessIds: {
							type: "array",
							...(eligibleWitnesses.length > 0
								? { items: { type: "string", enum: [...eligibleWitnesses] } }
								: { maxItems: 0, items: { type: "string" } }),
							description:
								"For ALREADY_SAID or NO_MATERIAL_CHANGE: the witnessId of each statement under what was " +
								"already said on this work that gave this advice.",
						},
					},
				},
			},
		},
	};
}

/** Explanatory context from the staged practice revision, not a new assessment. */
export interface ReviewPractice {
	slug: string;
	name: string;
	whyItMatters?: string;
	knownLimitations: readonly string[];
}

/** Everything the review on the work is composed from; nothing else reaches its session. */
export interface ReviewTurnInput {
	/** The captured record of this same work, from buildSameWorkContext: it orients; observations own assessments. */
	sameWork: string;
	/** The decided observations of this work the review may rest on, whole, from publicObservations. */
	observations: readonly Record<string, unknown>[];
	/** The practices looked at and not decided, by slug and outcome, from uncertainOutcomes. */
	undecided: readonly { practiceSlug: string; outcome: string }[];
	/** What Hephaestus already said on this same work, from priorPublicFeedback. */
	alreadySaid: readonly OwnPriorFeedback[];
	ownHistoryOmissions?: OwnHistoryOmissions;
	/** The bounded captured public discussion of this same work, from buildPublicReviewHistory. */
	captured: PublicReviewHistory;
	/** Context on the practices of the decided observations. */
	practices: readonly ReviewPractice[];
	/** Practices this review did not reach at all. */
	notReached: readonly string[];
	/** Whether this work has lines a note can be placed on. */
	lineNotes: boolean;
}

/** The one prompt of the review composition: every input inline, because its session can read nothing else. */
export function buildReviewTurn(input: ReviewTurnInput): string {
	const own = ownHistoryText(input.alreadySaid, input.ownHistoryOmissions);
	const others =
		input.captured.sources.length === 0 && input.captured.omitted === undefined
			? "What people and tools said on this work was not part of this capture, so it is unknown.\n"
			: `Captured public discussion on this work; omitted sources remain unknown:\n\`\`\`json\n${JSON.stringify(input.captured, null, 1)}\n\`\`\`\n`;
	const said = `${own}${others}A withholding decision that says this work already received the advice names the \`witnessId\` of a statement marked \`eligibleForPriorAdvice\`; any other statement is context only.\n`;
	const practices = `\`\`\`json\n${JSON.stringify({ practices: input.practices }, null, 1)}\n\`\`\`\n`;
	const undecided =
		input.undecided.length === 0
			? ""
			: `\nLooked at and not decided, so they support no claim either way: ${input.undecided
					.map((entry) => `${entry.practiceSlug} (${entry.outcome})`)
					.join(", ")}.\n`;
	const placement = input.lineNotes
		? "- Line notes: a note sits on one citation marked `anchorable`, named by `observationId` and " +
			"`citationIndex`. On GitHub it is a review comment on that line; on GitLab it is its own comment " +
			"headed by a link to the line. Each body stands on its own, and each placement can fail independently.\n"
		: "- This work has no lines a note can sit on; everything goes in the summary.\n";
	const cited: CitedObservation[] = input.observations.map((observation) => ({
		id: String(observation.id),
		practiceSlug: String(observation.practiceSlug),
		outcome: observation.outcome,
		citations: Array.isArray(observation.citations)
			? observation.citations.filter((citation: unknown): citation is Record<string, unknown> =>
					isObject(citation),
				)
			: [],
	}));
	return `## The review to write
The measurement of this work is finished. Below is the captured record of the work this review is about, then everything the review may rest on: the decided observations of this work, what was already said on this same work, and context on the practices they were measured against.

### The reviewed work, as captured
${input.sameWork}

### Decided observations of this work
\`\`\`json
${JSON.stringify({ observations: input.observations }, null, 1)}
\`\`\`
${undecided}
### Already said on this work
${said}
### Practice context
${practices}
### Where the words go
- The summary: one comment on the work.
${placement}
${sameLinesNote(cited)}${notReachedNote(input.notReached)}First choose what the review speaks about with one select_feedback call: every NOT_MET observation selected or withheld with your reason, and any MET observation whose choice earns an acknowledgement. Once a selection is accepted, store the whole review with one report_review call that speaks about exactly the selected observations and repeats the selection's withholding decisions. Writing nothing for the work is a decision too: it is still one final report_review call.`;
}
