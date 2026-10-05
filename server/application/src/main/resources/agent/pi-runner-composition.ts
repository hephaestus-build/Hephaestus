export const CHANNELS = ["IN_CONTEXT", "IN_APP", "IN_CHAT"] as const;
export type Channel = (typeof CHANNELS)[number];

/** The developer's practice pages and the mentor conversation; the reviewed work takes a review instead. */
export const PRIVATE_CHANNELS = ["IN_APP", "IN_CHAT"] as const;
export type PrivateChannel = (typeof PRIVATE_CHANNELS)[number];

export const ACTIONS = ["NEW", "SUPERSEDE", "WITHHOLD"] as const;
export type FeedbackAction = (typeof ACTIONS)[number];

export const WITHHOLD_REASONS = ["NO_MATERIAL_CHANGE", "ALREADY_SAID", "BELOW_BAR"] as const;
export type WithholdReason = (typeof WITHHOLD_REASONS)[number];

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
		const word =
			typeof raw.reason === "string" ? raw.reason.trim().toUpperCase().replaceAll("-", "_") : "";
		const reason = WITHHOLD_REASONS.find((candidate) => candidate === word);
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

/**
 * What was already said on this very piece of work, from the feedback history: only the review on the work, never
 * what reached the developer's own pages or conversations, and never anything about other work.
 */
export function priorPublicFeedback(history: unknown, thisWork: string | undefined): unknown[] {
	if (thisWork === undefined || !isObject(history) || !Array.isArray(history.feedback)) {
		return [];
	}
	return history.feedback.flatMap((entry: unknown) => {
		if (!isObject(entry) || entry.channel !== "IN_CONTEXT") {
			return [];
		}
		const artifact = isObject(entry.artifact) ? entry.artifact : {};
		if (workIdentity(artifact.kind, artifact.url) !== thisWork) {
			return [];
		}
		const { deliveredAt, body, recordedClaimCurrentness, withdrawn } = entry;
		return [{ deliveredAt, body, recordedClaimCurrentness, withdrawn }];
	});
}

/** What report_review tells the model it does. The rules are applied by readReview, with every reason at once. */
export const REVIEW_TOOL_DESCRIPTION =
	"Store the review on this piece of work: the complete summary comment, any complete notes placed on lines " +
	"of the change, and the NOT_MET observations you decided not to raise here. Each body supplies the complete " +
	"guidance; the server never assembles prose from fragments. Provider safety formatting and a fixed disclosure " +
	"still apply. One call stores the whole review; " +
	"a later call replaces it. Speak only about the issues the named observations decided; incidental citation " +
	"details do not authorize new assessments or requirements. Acknowledgements must name their own support. " +
	"Invalid support, eligibility or placement refuses the whole review, with every reason, so it can be " +
	"corrected and sent again.";

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
				required: ["body", "basedOn"],
				description:
					"The one comment on the work, complete as the developer will read it. Omit it when nothing on " +
					"this work earns a comment of its own.",
				properties: {
					body: {
						type: "string",
						minLength: 1,
						maxLength: REVIEW_LIMITS.summaryChars,
						description: "The whole comment in Markdown.",
					},
					basedOn: idList(
						decided,
						"The id of every observation this comment speaks about, and no other. If any of them may " +
							"not go out, the whole comment stays unsaid.",
					),
				},
			},
			inline: {
				type: "array",
				maxItems: anchorable.length > 0 ? REVIEW_LIMITS.inlineNotes : 0,
				description:
					"Notes placed on lines of the change. Each is complete on its own: where a line cannot carry " +
					"it, it is posted as a separate comment headed by its file and line.",
				items: {
					type: "object",
					required: ["body", "basedOn", "anchor"],
					properties: {
						body: {
							type: "string",
							minLength: 1,
							maxLength: REVIEW_LIMITS.inlineChars,
							description: "The whole note in Markdown.",
						},
						basedOn: idList(
							decided,
							"The id of every observation this note speaks about; it includes the anchor's.",
						),
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

/** What the review is told about a practice: its staged revision's public facts, never its criteria. */
export interface ReviewPractice {
	slug: string;
	name: string;
	whyItMatters?: string;
	knownLimitations: readonly string[];
}

/** Everything the review on the work is composed from; nothing else reaches its session. */
export interface ReviewTurnInput {
	/** The decided observations of this work the review may rest on, whole, from publicObservations. */
	observations: readonly Record<string, unknown>[];
	/** The practices looked at and not decided, by slug and outcome, from uncertainOutcomes. */
	undecided: readonly { practiceSlug: string; outcome: string }[];
	/** What was already said on this same work, from priorPublicFeedback. */
	alreadySaid: readonly unknown[];
	/** The practices of the decided observations. */
	practices: readonly ReviewPractice[];
	/** Practices this review did not reach at all. */
	notReached: readonly string[];
	/** Whether this work has lines a note can be placed on. */
	lineNotes: boolean;
}

/** The one prompt of the review composition: every input inline, because its session can read nothing else. */
export function buildReviewTurn(input: ReviewTurnInput): string {
	const said =
		input.alreadySaid.length === 0
			? "Nothing has been said on this work yet.\n"
			: `\`\`\`json\n${JSON.stringify({ alreadySaid: input.alreadySaid }, null, 1)}\n\`\`\`\n`;
	const practices = `\`\`\`json\n${JSON.stringify({ practices: input.practices }, null, 1)}\n\`\`\`\n`;
	const undecided =
		input.undecided.length === 0
			? ""
			: `\nLooked at and not decided, so they support no claim either way: ${input.undecided
					.map((entry) => `${entry.practiceSlug} (${entry.outcome})`)
					.join(", ")}.\n`;
	const placement = input.lineNotes
		? "- Line notes: a note sits on one citation marked `anchorable`, named by `observationId` and " +
			"`citationIndex`. If the provider cannot place it on that line, it is posted as its own comment " +
			"headed by the file and line, so write every note to stand on its own. Several notes may rest on " +
			"one practice when they are about different lines.\n"
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
The measurement of this work is finished. Below is everything this review may rest on: the decided observations of this work, what was already said on this same work, and the practices they were measured against.

### Decided observations of this work
\`\`\`json
${JSON.stringify({ observations: input.observations }, null, 1)}
\`\`\`
${undecided}
### Already said on this work
${said}
### The practices
${practices}
### Where the words go
- The summary: one comment on the work.
${placement}
${sameLinesNote(cited)}${notReachedNote(input.notReached)}Store the review with one report_review call. Decide every NOT_MET observation: speak about it in the summary or a line note, or name it under withheld with your reason. Writing nothing for the work is a correct outcome when nothing earns it; then send only withheld.`;
}
