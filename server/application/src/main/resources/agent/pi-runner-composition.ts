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

/**
 * Two cutoffs, two questions. A statement delivered before this work was captured is advice the work could have
 * answered; one delivered later, before the review was composed, was still said here, but nothing about the captured
 * work can respond to it.
 */
export interface PriorAdviceWitness {
	eligibleForPriorAdvice: boolean;
	eligibleForAlreadySaid: boolean;
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

/** A list as sent: omitted or null is empty, and anything else that is not a list is null, never read as one item. */
function listOf(value: unknown): unknown[] | null {
	if (Array.isArray(value)) {
		const list: unknown[] = value;
		return list;
	}
	return value === undefined || value === null ? [] : null;
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

/** What the review decides for one NOT_MET observation: raise it, or withhold it for one of the reasons. */
export const DISPOSITIONS = ["RAISE", ...WITHHOLD_REASONS] as const;
export type Disposition = (typeof DISPOSITIONS)[number];

function dispositionOf(value: unknown): Disposition | undefined {
	const word = typeof value === "string" ? value.trim().toUpperCase().replaceAll("-", "_") : "";
	return DISPOSITIONS.find((candidate) => candidate === word);
}

/** The observations a sent text names in basedOn, read from what was sent so a refused part still counts as spoken. */
function spokenOf(value: Record<string, unknown>): Set<string> {
	const summary = isObject(value.summary) ? (idsOf(value.summary.basedOn) ?? []) : [];
	const notes = (listOf(value.inline) ?? []).flatMap((note) =>
		isObject(note) ? (idsOf(note.basedOn) ?? []) : [],
	);
	return new Set([...summary, ...notes]);
}

/**
 * Every NOT_MET observation takes one decision, and the text keeps to it: a raised one is spoken about, a withheld one
 * is not. An observation whose decision was sent but refused is answered by that refusal, not as undecided.
 */
function coverageProblems(
	decided: ReadonlyMap<string, Disposition>,
	named: ReadonlySet<string>,
	spoken: ReadonlySet<string>,
	observations: ReadonlyMap<string, ReviewedObservation>,
): string[] {
	const errors: string[] = [];
	const missing = [...observations]
		.filter(([id, observation]) => observation.outcome === "NOT_MET" && !named.has(id))
		.map(([id]) => id);
	if (missing.length > 0) {
		errors.push(
			`${missing.join(", ")} has no decision; decide each NOT_MET observation: RAISE it, or withhold it with its reason`,
		);
	}
	for (const [id, disposition] of decided) {
		if (disposition === "RAISE" && !spoken.has(id)) {
			errors.push(
				`${id} is decided RAISE, and no text speaks about it; speak about it, or decide again`,
			);
		} else if (disposition !== "RAISE" && spoken.has(id)) {
			errors.push(
				`${id} is withheld as ${disposition}, and a text speaks about it; an observation is raised or withheld, never both`,
			);
		}
	}
	return errors;
}

function inlineSupportProblem(
	basedOn: string[] | null,
	observations: ReadonlyMap<string, ReviewedObservation>,
): string | null {
	return basedOn !== null && basedOn.some((id) => observations.get(id)?.summaryOnly === true)
		? "this note rests on a practice that requires summary placement"
		: null;
}

/** What a review is read against besides the observations it may rest on. */
export interface ReviewContext {
	/** The statements a withholding may name as where this work already received the advice. */
	witnesses: ReadonlyMap<string, PriorAdviceWitness>;
	/** The practices whose complete MET grounds and standard the model could read when it wrote this review. */
	standardsInView: ReadonlySet<string>;
}

/** An acknowledgement is weighed against its whole standard, so a MET observation needs it in view. */
function standardProblem(
	basedOn: readonly string[] | null,
	observations: ReadonlyMap<string, ReviewedObservation>,
	standardsInView: ReadonlySet<string>,
): string | null {
	const unread = [
		...new Set(
			(basedOn ?? []).flatMap((id) => {
				const observation = observations.get(id);
				return observation?.outcome === "MET" && !standardsInView.has(observation.practiceSlug)
					? [observation.practiceSlug]
					: [];
			}),
		),
	];
	return unread.length === 0
		? null
		: `it rests on a MET observation of ${unread.join(", ")}, whose complete MET reference this review was not written with; read it with read_practice, and send the review in a later turn`;
}

/** ALREADY_SAID needs a recorded communication; NO_MATERIAL_CHANGE also needs advice available at capture. */
function witnessProblems(
	reason: WithholdReason,
	witnessIds: readonly string[] | null,
	witnesses: ReadonlyMap<string, PriorAdviceWitness>,
): string[] {
	if (witnessIds === null) {
		return ["witnessIds must be an array of witnessId strings from what was already said"];
	}
	const problems: string[] = [];
	const unknown = witnessIds.filter((id) => !witnesses.has(id));
	if (unknown.length > 0) {
		problems.push(
			`witnessIds names ${unknown.join(", ")}, which is not a statement shown under what was already said on this work`,
		);
	}
	const flag = reason === "ALREADY_SAID" ? "eligibleForAlreadySaid" : "eligibleForPriorAdvice";
	const ineligible = witnessIds.filter(
		(id) => witnesses.has(id) && witnesses.get(id)?.[flag] !== true,
	);
	if (ineligible.length > 0) {
		problems.push(
			`witnessIds names ${ineligible.join(", ")}, which is shown as context but cannot stand as advice for ${reason} (${flag} is false)`,
		);
	}
	if (PRIOR_ADVICE_REASONS.has(reason) && witnessIds.length === 0) {
		problems.push(
			`${reason} names in witnessIds where this work already received the advice: the witnessId of a statement marked ${flag}; without one, raise it or choose another reason`,
		);
	}
	return problems;
}

/** The summary as sent, whole, or every reason it cannot be stored; an omitted or null summary is none. */
function readSummary(
	value: unknown,
	observations: ReadonlyMap<string, ReviewedObservation>,
	standardsInView: ReadonlySet<string>,
): { summary: ReviewSummary | null; errors: string[] } {
	if (value === undefined || value === null) {
		return { summary: null, errors: [] };
	}
	if (!isObject(value)) {
		return { summary: null, errors: ["summary: is one object with body and basedOn"] };
	}
	const basedOn = idsOf(value.basedOn);
	const problems = [
		textProblem(value.body, REVIEW_LIMITS.summaryChars),
		supportProblem(basedOn, observations),
		standardProblem(basedOn, observations, standardsInView),
	].filter((problem): problem is string => problem !== null);
	if (problems.length > 0 || typeof value.body !== "string" || basedOn === null) {
		return { summary: null, errors: problems.map((problem) => `summary: ${problem}`) };
	}
	return { summary: { body: value.body, basedOn: [...new Set(basedOn)] }, errors: [] };
}

/**
 * The decisions as sent, one per NOT_MET observation: each valid one by observation, every observation a decision
 * names, and every reason one is refused. A prior-advice reason names the statement that gave the advice.
 */
function readDecisions(
	value: unknown,
	observations: ReadonlyMap<string, ReviewedObservation>,
	witnesses: ReadonlyMap<string, PriorAdviceWitness>,
): { decided: Map<string, Disposition>; named: Set<string>; errors: string[] } {
	const decided = new Map<string, Disposition>();
	const named = new Set<string>();
	if (!Array.isArray(value)) {
		return {
			decided,
			named,
			errors: [
				"decisions is required: an array with one decision for each NOT_MET observation, empty when there is none",
			],
		};
	}
	const sent: unknown[] = value;
	const errors: string[] = [];
	for (const [index, raw] of sent.entries()) {
		const label = `decisions #${index + 1}`;
		if (!isObject(raw)) {
			errors.push(
				`${label}: is one object with observationId, disposition and, for a prior advice, witnessIds`,
			);
			continue;
		}
		const id = typeof raw.observationId === "string" ? raw.observationId.trim() : "";
		const disposition = dispositionOf(raw.disposition);
		const witnessIds =
			raw.witnessIds === undefined || raw.witnessIds === null ? [] : idsOf(raw.witnessIds);
		const problems: string[] = [];
		const unknown = Object.keys(raw).filter(
			(key) => key !== "observationId" && key !== "disposition" && key !== "witnessIds",
		);
		if (unknown.length > 0) {
			problems.push(`unknown decision field(s): ${unknown.join(", ")}`);
		}
		if (id === "") {
			problems.push("observationId is required: the `id` of one NOT_MET observation");
		} else if (!observations.has(id)) {
			problems.push(`${id} is not one of the observations this review may rest on`);
		} else if (observations.get(id)?.outcome !== "NOT_MET") {
			problems.push(`${id} is not a NOT_MET observation; only a problem takes a decision`);
		} else if (named.has(id)) {
			problems.push(`${id} already has a decision; each NOT_MET observation takes one`);
		}
		if (observations.get(id)?.outcome === "NOT_MET") {
			named.add(id);
		}
		if (disposition === undefined) {
			problems.push(`disposition must be one of ${DISPOSITIONS.join(", ")}`);
		} else if (disposition === "RAISE") {
			if (witnessIds === null || witnessIds.length > 0) {
				problems.push("a raised observation names no witness");
			}
		} else {
			problems.push(...witnessProblems(disposition, witnessIds, witnesses));
		}
		errors.push(...problems.map((problem) => `${label}: ${problem}`));
		if (problems.length === 0 && disposition !== undefined) {
			decided.set(id, disposition);
		}
	}
	return { decided, named, errors };
}

/** Keep an explicit no-summary distinct from a primitive the SDK could coerce to null. */
export function prepareReviewArguments(value: unknown) {
	if (!isObject(value)) {
		throw new Error("the review is one object with decisions, summary and inline");
	}
	if (value.summary !== undefined && value.summary !== null && !isObject(value.summary)) {
		throw new Error(
			"summary is one object with body and basedOn, or null when there is no comment",
		);
	}
	return value;
}

/**
 * Reads one review as the composer sent it, or every reason it cannot be stored. A review is stored whole or not
 * at all: its decisions and texts were written together, so one wrong part sends the whole review back to be
 * corrected. The decisions are checked here and not stored as sent: each withheld one becomes the stored withholding
 * of its observation and reason, and a decision's witnessIds are not kept.
 *
 * @param lineNotes whether this work has lines a note can be placed on
 */
export function readReview(
	value: unknown,
	observations: ReadonlyMap<string, ReviewedObservation>,
	lineNotes: boolean,
	context: ReviewContext,
): { review: ComposedReview } | { errors: string[] } {
	if (!isObject(value)) {
		return { errors: ["the review is one object with decisions, summary and inline"] };
	}
	const errors: string[] = [];
	const unknownFields = Object.keys(value).filter(
		(key) => key !== "decisions" && key !== "summary" && key !== "inline",
	);
	if (unknownFields.length > 0) {
		errors.push(
			`unknown review field(s): ${unknownFields.join(", ")} — a review takes decisions, summary and inline`,
		);
	}
	const decisions = readDecisions(value.decisions, observations, context.witnesses);
	errors.push(...decisions.errors);

	const read = readSummary(value.summary, observations, context.standardsInView);
	errors.push(...read.errors);
	const { summary } = read;

	const sentNotes = listOf(value.inline);
	if (sentNotes === null) {
		errors.push("inline must be an array of line notes");
	}
	const notes = sentNotes ?? [];
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
			standardProblem(basedOn, observations, context.standardsInView),
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

	errors.push(
		...coverageProblems(decisions.decided, decisions.named, spokenOf(value), observations),
	);
	const withheld: ReviewWithheld[] = [...decisions.decided].flatMap(([id, disposition]) =>
		disposition === "RAISE" ? [] : [{ basedOn: [id], reason: disposition }],
	);
	return errors.length > 0 ? { errors } : { review: { summary, inline, withheld } };
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
				citations: withoutDigests(citations),
			};
		});
}

/** Citations as a public reference shows them: quoted lines and coordinates, without verification digests. */
function withoutDigests(citations: unknown): unknown[] {
	return (Array.isArray(citations) ? citations : []).map((citation: unknown) => {
		if (!isObject(citation)) {
			return citation;
		}
		const { verification: _verification, ...shown } = citation;
		return shown;
	});
}

/** The native outcome-specific grounds of an abstention; generic prose is not a decided assessment. */
export function qualificationEvidence(observation: {
	outcome: unknown;
	evidence?: unknown;
}): Record<string, unknown> {
	const branch = observation.outcome === "UNDETERMINED" ? "undecidability" : "inapplicability";
	const evidence = isObject(observation.evidence) ? observation.evidence : {};
	return { [branch]: evidence[branch] };
}

/**
 * The abstentions admission marked publicEligible, each as the limit it recorded with the lines it cited: a bound on
 * what the review may claim, distinct from the practices it never reached. They carry no id: no text rests on them,
 * and the review decides nothing about them.
 */
export function publicQualifications(
	observations: readonly Record<string, unknown>[],
): Record<string, unknown>[] {
	return observations
		.filter(
			(observation) =>
				observation.publicEligible === true &&
				(observation.outcome === "NOT_APPLICABLE" || observation.outcome === "UNDETERMINED"),
		)
		.map((observation) => ({
			practiceSlug: observation.practiceSlug,
			outcome: observation.outcome,
			evidence: qualificationEvidence({
				outcome: observation.outcome,
				evidence: observation.evidence,
			}),
			citations: withoutDigests(observation.citations),
		}));
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
	eligibleForAlreadySaid: boolean;
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
	readAt?: string,
): string {
	const asRead = readAt === undefined ? "" : `, as read at ${readAt}`;
	const shown =
		feedback.length === 0
			? `No same-work delivered feedback is shown here${asRead}.\n`
			: `What Hephaestus delivered on this work${asRead}:\n\`\`\`json\n${JSON.stringify({ alreadySaid: feedback }, null, 1)}\n\`\`\`\n`;
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
	readAt: string | null = capturedAt,
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
		// Stale words still record what was said here, so they can show novelty; only current ones stand as advice.
		const recorded =
			witnessId !== null &&
			typeof body === "string" &&
			body.trim() !== "" &&
			(recordedClaimCurrentness === "CURRENT" || recordedClaimCurrentness === "STALE") &&
			withdrawn !== true;
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
					recorded &&
					recordedClaimCurrentness === "CURRENT" &&
					deliveredBy(deliveredAt, capturedAt),
				eligibleForAlreadySaid: recorded && deliveredBy(deliveredAt, readAt),
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
				eligibleForAlreadySaid: statement.eligibleForAlreadySaid,
			});
		}
	}
	for (const statement of captured) {
		// The captured discussion has one cutoff: what it holds was said by the time the work was captured.
		witnesses.set(statement.witnessId, {
			eligibleForPriorAdvice: statement.eligibleForPriorAdvice,
			eligibleForAlreadySaid: statement.eligibleForPriorAdvice,
		});
	}
	return witnesses;
}

export const READ_PRACTICE_TOOL_DESCRIPTION =
	"Show a practice's complete permitted MET observations, its whole staged standard, known limitations and " +
	"candidate references to earlier feedback before acknowledging it. An opening MET entry is only an index; even " +
	"when its standard accompanies a NOT_MET observation, its full MET grounds must be read. The reference is in " +
	"view from your next turn, never another call in the same response. Nothing is published or stored by this call.";

/** What report_review tells the model it does. The rules are applied by readReview, with every reason at once. */
export const REVIEW_TOOL_DESCRIPTION =
	"Store the final review on this piece of work. First decide each NOT_MET observation: RAISE it, or withhold it " +
	"with your reason and, for advice this work already received, the statement that gave it. Then write the summary " +
	"comment and any notes placed on lines of the change: they speak about every raised observation and no withheld " +
	"one. Each body is published whole as written, with provider safety formatting and a fixed disclosure; nothing is " +
	"assembled from fragments. An accepted call is final and ends the composition. A missing or contradicted decision, " +
	"invalid support, eligibility or placement, a witness that cannot stand as the advice, or an acknowledgement " +
	"written without its practice's complete MET reference in view refuses the whole review, with every reason, so it can be " +
	"corrected and sent again.";

/**
 * How the review is written. It ends the opening turn, after the criteria, so reference material is followed by the
 * writing task; the composer prompt points here.
 */
export const WRITE_CONTRACT =
	"## Writing the review\n" +
	"Write from the observations each text rests on, within their qualifications. A declared affordance supports its bounded " +
	"benefit, not an unobserved runtime or test outcome. Keep each remedy on its recorded gap and leave unrelated " +
	"behavior as it is; make another change a prerequisite only when the evidence establishes that dependency. " +
	"You are not told whether the work is ready, so do not approve it, call it ready or blocked, or set conditions " +
	"for merging it. Timing the evidence itself warrants is fine: a committed secret is removed and rotated before " +
	"anyone relies on the history.\n\n" +
	"Make each point fully once in this review:\n" +
	"- A line note is one self-contained point about the code at its anchor: that local concern with its action and " +
	"evidence, or that local acknowledgement. It is read alone, so it says its point completely. Several practices " +
	"may support it when they describe that one event; it does not collect the review's other points.\n" +
	"- The summary orients the reader: the guidance its NOT_MET observations warrant, most important first, and, " +
	"selectively, a useful choice or repair the evidence shows. It carries every ask that is not " +
	"about one place in the code, such as the description, a reply to a reviewer or a work-wide change, unless it " +
	"forms one point with the code at a line. When a point has no useful line note, the summary carries that point " +
	"completely. It may name or locate a line note's topic, and then names that note's " +
	"observations in basedOn, but it does not restate the note's diagnosis, action or acknowledgement.\n" +
	"- An acknowledgement appears once, in the summary or on its line, and says briefly what the choice provides. It " +
	"is never required, counted or used to cushion a concern; when nothing calls for action, the summary may describe " +
	"that bounded result, or the review may say nothing.\n\n" +
	"One observation may support notes at several places, and also the summary's overview of them.";

/** The practices of the public NOT_MET observations, each once: every one is decided against its whole standard. */
function notMetPractices(reviewable: readonly Record<string, unknown>[]): string[] {
	return [
		...new Set(
			reviewable
				.filter(
					(observation) => observation.publicEligible === true && observation.outcome === "NOT_MET",
				)
				.map((observation) => String(observation.practiceSlug)),
		),
	];
}

/**
 * What a staged standard is for in the review, wherever one is shown: the opening, a read, or a restored reference. The
 * criteria were written to assess the work; here they only qualify how its recorded observations are communicated.
 */
export const STANDARD_REFERENCE =
	"Reference, whole as staged: each standard explains what its practice's recorded observations were assessed " +
	"against and the responses it accepts, and qualifies whether and how those observations may be communicated. It " +
	"is not a request to assess the work again and raises no concern of its own.";

/** Each concern's complete grounds, limits, standard and candidate communication references, grouped by practice. */
export function notMetReference(
	reviewable: readonly Record<string, unknown>[],
	stagedCriteria: (practiceSlug: string) => string | null,
	practices: readonly ReviewPractice[] = [],
	history: readonly OwnPriorFeedback[] = [],
	reviewedRevision: string | null = null,
): string {
	const concerns = notMetPractices(reviewable).map((slug) => {
		const observations = reviewable.filter(
			(entry) =>
				entry.publicEligible === true && entry.outcome === "NOT_MET" && entry.practiceSlug === slug,
		);
		return `${practiceStandard(slug, stagedCriteria).text}${practiceReference(slug, observations, practices, history, reviewedRevision)}`;
	});
	return concerns.length === 0
		? "No public NOT_MET observation was admitted.\n"
		: `${STANDARD_REFERENCE}\n${concerns.join("\n")}`;
}

/** Association by captured practice metadata only: these references decide neither a match nor novelty. */
function practiceReference(
	slug: string,
	observations: readonly Record<string, unknown>[],
	practices: readonly ReviewPractice[],
	history: readonly OwnPriorFeedback[],
	reviewedRevision: string | null,
): string {
	const practice = practices.find((entry) => entry.slug === slug);
	const candidatePriorWitnesses = history.flatMap((entry) => {
		if (
			entry.witnessId === null ||
			(!entry.eligibleForAlreadySaid && !entry.eligibleForPriorAdvice) ||
			!Array.isArray(entry.basedOn) ||
			!entry.basedOn.some((support: unknown) => isObject(support) && support.practiceSlug === slug)
		) {
			return [];
		}
		return [
			{
				witnessId: entry.witnessId,
				reviewedRevision:
					typeof entry.reviewedRevision === "string" ? entry.reviewedRevision : null,
				basedOn: entry.basedOn.filter(
					(support: unknown) => isObject(support) && support.practiceSlug === slug,
				),
				eligibleForAlreadySaid: entry.eligibleForAlreadySaid,
				eligibleForPriorAdvice: entry.eligibleForPriorAdvice,
			},
		];
	});
	return `\`\`\`json\n${JSON.stringify(
		{
			reviewedRevision,
			...(practice === undefined
				? {}
				: {
						practice: {
							slug: practice.slug,
							name: practice.name,
							...(practice.revisionId === undefined ? {} : { revisionId: practice.revisionId }),
							knownLimitations: practice.knownLimitations,
						},
					}),
			observations,
			candidatePriorWitnesses,
		},
		null,
		1,
	)}\n\`\`\`\n`;
}

/** Full MET grounds and their reference, also retained whole after compaction. */
export function consultedStandard(
	standardText: string,
	slug: string,
	reviewable: readonly Record<string, unknown>[],
	practices: readonly ReviewPractice[] = [],
	history: readonly OwnPriorFeedback[] = [],
	reviewedRevision: string | null = null,
): string {
	const grounds = reviewable.filter(
		(observation) =>
			observation.publicEligible === true &&
			observation.outcome === "MET" &&
			observation.practiceSlug === slug,
	);
	return `${standardText}The recorded MET observations of \`${slug}\`:\n${practiceReference(slug, grounds, practices, history, reviewedRevision)}`;
}

/** The practices read_practice may show: those with a public MET observation. */
export function readablePractices(reviewable: readonly Record<string, unknown>[]): string[] {
	return [
		...new Set(
			reviewable
				.filter(
					(observation) => observation.publicEligible === true && observation.outcome === "MET",
				)
				.map((observation) => String(observation.practiceSlug)),
		),
	];
}

/**
 * One practice's staged criteria read once, and whether they are shown whole. A read that is missing, fails or holds
 * no text never counts as the standard; a blank one is still shown as staged.
 */
export function practiceStandard(
	slug: string,
	stagedCriteria: (practiceSlug: string) => string | null,
): { text: string; whole: boolean } {
	let criteria: string | null;
	try {
		criteria = stagedCriteria(slug);
	} catch {
		return {
			text: `### Criteria of \`${slug}\` — could not be read; nothing is known about them here\n`,
			whole: false,
		};
	}
	return {
		text: criteriaBlock(slug, criteria),
		whole: criteria !== null && criteria.trim() !== "",
	};
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
 * The parameters of report_review for one run, built from the same observations readReview checks against: one
 * decision for each NOT_MET observation comes first, each text names only decided observations of this run, and a
 * note sits only on one with a line of this change. A list with nothing it could name takes no items. The required
 * fields of each part are required here, so a missing one is answered by the provider's own validation; the texts
 * use null for no summary and an empty array for no notes. readReview stays the final check and answers each part by name.
 *
 * @param lineNotes whether this work has lines a note can be placed on
 * @param eligibleWitnesses every witnessId a decision may name; readReview checks which reason each one supports
 */
export function reviewToolParameters(
	observations: ReadonlyMap<string, ReviewedObservation>,
	lineNotes: boolean,
	eligibleWitnesses: readonly string[],
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
		required: ["decisions", "summary"],
		properties: {
			decisions: {
				type: "array",
				minItems: notMet.length,
				maxItems: notMet.length,
				description:
					"Decided first: one decision for each NOT_MET observation, and none when there is none. RAISE means " +
					"the texts below speak about it; any other disposition withholds it, and no text speaks about it.",
				items: {
					type: "object",
					required: ["observationId", "disposition"],
					properties: {
						observationId:
							notMet.length > 0 ? { type: "string", enum: notMet } : { type: "string" },
						disposition: { type: "string", enum: [...DISPOSITIONS] },
						witnessIds: {
							type: "array",
							...(eligibleWitnesses.length > 0
								? { items: { type: "string", enum: [...eligibleWitnesses] } }
								: { maxItems: 0, items: { type: "string" } }),
							description:
								"For ALREADY_SAID or NO_MATERIAL_CHANGE: the witnessId of each statement under what was " +
								"already said on this work that gave this advice. ALREADY_SAID takes a statement marked " +
								"eligibleForAlreadySaid; NO_MATERIAL_CHANGE one marked eligibleForPriorAdvice. Checked, " +
								"never stored.",
						},
					},
				},
			},
			summary: {
				type: ["object", "null"],
				required: ["basedOn", "body"],
				description:
					"The one overview comment on the work: the guidance its NOT_MET observations warrant, a useful " +
					"choice or repair the evidence shows, and points not carried by line notes. Send null when nothing " +
					"on this work earns a comment of its own.",
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
					"Notes intended for cited lines of the change, each one self-contained point about the code at " +
					"its line. Each placement can fail independently.",
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
		},
	};
}

/** read_practice names one practice it may show; an empty enum is invalid, so with none it offers a bare string. */
export function readPracticeParameters(readable: readonly string[]) {
	return {
		type: "object",
		required: ["practiceSlug"],
		properties: {
			practiceSlug: {
				type: "string",
				...(readable.length > 0 ? { enum: [...readable] } : {}),
				description:
					"The practice whose whole standard to show: one with a MET observation of this work.",
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
	/** Which revision of the practice was staged: it identifies the standard, not that the standard or the work changed. */
	revisionId?: number;
}

/** Everything the review on the work is composed from; nothing else reaches its session. */
export interface ReviewTurnInput {
	/** The captured record of this same work, from buildSameWorkContext: it orients; observations own assessments. */
	sameWork: string;
	/** The decided observations of this work the review may rest on, whole, from publicObservations. */
	observations: readonly Record<string, unknown>[];
	/** The limits recorded by the abstentions of this work, from publicQualifications: context, never support. */
	qualifications: readonly Record<string, unknown>[];
	/** What Hephaestus already said on this same work, from priorPublicFeedback. */
	alreadySaid: readonly OwnPriorFeedback[];
	ownHistoryOmissions?: OwnHistoryOmissions;
	/** When the delivered feedback above was read, just before this composition; absent when it was not read again. */
	ownHistoryReadAt?: string;
	/** The bounded captured public discussion of this same work, from buildPublicReviewHistory. */
	captured: PublicReviewHistory;
	/** Context on the practices of the decided observations. */
	practices: readonly ReviewPractice[];
	/** Practices this review did not reach at all. */
	notReached: readonly string[];
	/** Whether this work has lines a note can be placed on. */
	lineNotes: boolean;
	/** One practice's staged criteria, whole; null when none were staged. */
	stagedCriteria: (practiceSlug: string) => string | null;
}

/**
 * The opening reference: whole work and communication, full concerns, an index of optional recognition, and the limits
 * the abstentions recorded.
 */
export function buildReviewTurn(input: ReviewTurnInput): string {
	const own = ownHistoryText(input.alreadySaid, input.ownHistoryOmissions, input.ownHistoryReadAt);
	const others =
		input.captured.sources.length === 0 && input.captured.omitted === undefined
			? "What people and tools said on this work was not part of this capture, so it is unknown.\n"
			: `Captured public discussion on this work; omitted sources remain unknown:\n\`\`\`json\n${JSON.stringify(input.captured, null, 1)}\n\`\`\`\n`;
	const said = `${own}${others}An \`ALREADY_SAID\` decision names the \`witnessId\` of a statement marked \`eligibleForAlreadySaid\`; a \`NO_MATERIAL_CHANGE\` decision one marked \`eligibleForPriorAdvice\`; any other statement is context only. \`eligibleForAlreadySaid\` alone establishes communication by the read time, not that the captured work received or responded to it.\n`;

	const qualifications =
		input.qualifications.length === 0
			? ""
			: `### Recorded limits of this review
Practices this review looked at and did not decide, or found not applicable, each with the limit it recorded and the lines it cited. They bound what the review may claim. They are not concerns or recognition: no text rests on them, and nothing is decided about them here.
\`\`\`json
${JSON.stringify({ observations: input.qualifications }, null, 1)}
\`\`\`
`;
	const placement = input.lineNotes
		? "- Line notes: a note's `anchor` names one citation marked `anchorable` by its `observationId` and " +
			"`citationIndex`. On GitHub it is a review comment on that line; on GitLab it is its own comment " +
			"headed by a link to the line. Each body stands on its own, and each placement can fail independently.\n"
		: "- This work has no lines a note can sit on; everything goes in the summary.\n";
	const cited: CitedObservation[] = input.observations
		.filter(
			(observation) => observation.publicEligible === true && observation.outcome === "NOT_MET",
		)
		.map((observation) => ({
			id: String(observation.id),
			practiceSlug: String(observation.practiceSlug),
			outcome: observation.outcome,
			citations: Array.isArray(observation.citations)
				? observation.citations.filter((citation: unknown): citation is Record<string, unknown> =>
						isObject(citation),
					)
				: [],
		}));
	const concerns = notMetReference(
		input.observations,
		input.stagedCriteria,
		input.practices,
		input.alreadySaid,
		input.captured.reviewedRevision ?? null,
	);
	const recognition = input.observations
		.filter((entry) => entry.publicEligible === true && entry.outcome === "MET")
		.map((entry) => ({ id: entry.id, practiceSlug: entry.practiceSlug, summary: entry.summary }));
	return `## The review to write
The measurement of this work is finished. The captured work establishes its purpose and evidence. Earlier public communication records what was said, not current verification. Each concern below carries its recorded grounds and standard; optional recognition starts as an index and is read only when useful.

### The reviewed work, as captured
${input.sameWork}
### Already said on this work
${said}
### Concerns to decide
Candidate prior witness references associate captured practice metadata only. Read their complete statements above to judge whether the advice matches, remains warranted, or is worth repeating; a missing association does not establish novelty.
${concerns}
### Optional recognition index
\`\`\`json
${JSON.stringify({ observations: recognition }, null, 1)}
\`\`\`
${qualifications}
### Where the words go
- The summary: one comment on the work.
${placement}
${sameLinesNote(cited)}${notReachedNote(input.notReached)}${recognition.length > 0 ? "Before the review acknowledges a MET observation, use read_practice for its complete grounds and reference, including when its standard is already shown with a concern. A read is in view from your next turn. Then store" : "Store"} the whole review with one report_review call. It decides first: each NOT_MET observation is RAISE, and a text speaks about it, or withheld with its reason, and no text speaks about it. Writing nothing for the work is a decision too: it is still one final report_review call.

${WRITE_CONTRACT}`;
}
