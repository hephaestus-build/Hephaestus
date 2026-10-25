import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import nodePath from "node:path";
import {
	DefaultResourceLoader,
	defineTool,
	type AgentToolResult,
	createCodemodeExtension,
	type AgentSession,
	type createAgentSession,
	type SettingsManager,
} from "@earendil-works/pi-coding-agent";
import { toolArgumentValidator, unwrapToolArguments } from "./pi-tool-validation.ts";

import {
	type Admission,
	type LeanResult,
	type AdmittedResult,
	resultsById,
} from "./pi-admitted-results.ts";
import {
	PUBLIC_REVIEW_RESOURCE_LOADER_OPTIONS,
	PUBLIC_REVIEW_TOOLS,
	SANDBOX_RESOURCE_LOADER_OPTIONS,
} from "./pi-agent-sandbox.ts";
import { checkedOutCommit, readPinnedBlob, pinnedDiff } from "./pi-change.ts";
import { errorText } from "./pi-error-text.ts";
import {
	prepareFeedback,
	type FeedbackComposition,
	type FeedbackPreparation,
	type FeedbackPreparationReceipt,
	type PublicationRefusal,
	type TextRefusal,
} from "./pi-feedback-preparation.ts";
import { trimmedText } from "./pi-result-normalize.ts";
import {
	buildPublicReviewHistory,
	buildPrimarySourceReference,
	buildSameWorkContext,
	captureTime,
} from "./pi-review-brief.ts";
import type { Work } from "./pi-review-turns.ts";
import {
	REVIEW_TOOL_DESCRIPTION,
	readReview,
	prepareReviewArguments,
	reviewToolParameters,
	type ReviewedResult,
	ACTIONS,
	PRIVATE_DESTINATIONS,
	publicResults,
	DESTINATIONS,
	PRIOR_ADVICE_REASONS,
	WITHHOLD_REASONS,
	evidenceRefs,
	namedEvidence,
	notReachedNote,
	type ReviewPractice,
	buildReviewTurn,
	decidedByReview,
	deliveredBy,
	priorAdviceWitnesses,
	priorPublicFeedback,
	type PriorAdviceWitness,
	publicQualifications,
	qualificationEvidence,
	undeliverableFeedback,
	validateFeedbackEvidence,
	type ComposedFeedbackEnvelope,
	type ComposedFeedbackPiece,
	type ComposedReview,
	type PreparedFeedbackTarget,
	type EvidenceRef,
	type Destination,
	type PrivateDestination,
	type FeedbackAction,
} from "./pi-runner-composition.ts";
import { scheduleDeadline, type SessionPhase } from "./pi-session-lifecycle.ts";
import type { taskPaths } from "./pi-task-paths.ts";
import { hasText, isBlank } from "./pi-text.ts";
import { prepareTurnText } from "./pi-turn-context.ts";
import {
	compositionRequestSchema,
	type Outcome,
} from "./work/practice-scripts/lib/core/contract.ts";
import {
	jsonArray,
	optionalString,
	isJsonObject as isRecord,
} from "./work/practice-scripts/lib/core/json.ts";
import { z } from "./work/practice-scripts/lib/core/practice-script.ts";
import type { practiceIndexSchema } from "./work/practice-scripts/lib/core/runtime-inputs.ts";

export type CompositionRequest = z.infer<typeof compositionRequestSchema>;

/** Missing requests disable composition; malformed requests never grant a destination. */
export function loadCompositionRequest(path: string): CompositionRequest | null {
	if (!existsSync(path)) {
		return null;
	}
	const request = compositionRequestSchema.parse(JSON.parse(readFileSync(path, "utf8")));
	return Object.keys(request.destinations).length === 0 ? null : request;
}

interface FeedbackToolRuntime {
	feedback: ComposedFeedback;
	admitted: () => boolean;
	routes: (slug: string) => readonly PrivateDestination[] | undefined;
	destinationRefusal: (destination: PrivateDestination, slug: string) => string;
	negativeItems: (slug: string) => number;
	documentedShape: (schema: unknown) => unknown;
	listSchema: (items: unknown, what: string) => unknown;
	submittedList: (value: unknown) => { items: unknown[] } | { error: string };
	refusal: (id: string, text: string) => Promise<AgentToolResult<ReportFeedbackDetails>>;
	persist: () => void;
	paid: () => boolean;
	onStored: (count: number) => void;
}
interface ConversationNotes {
	situation?: string;
	capability?: string;
	evidenceSummary?: string;
	inConversationLearning?: string;
	alreadySaid?: string;
}

/**
 * A private-destination piece; per-piece validation in readFeedbackPiece/validateFeedback. As read, basedOn holds the ids the
 * composer named; as stored, the references they resolve to.
 */
interface FeedbackPieceOf<Basis> extends ComposedFeedbackPiece {
	destination: PrivateDestination;
	practiceSlug: string;
	basedOn: Basis;
	action: FeedbackAction;
	supersedesThreadKey?: string;
	withholdReason?: string;
	witnessIds?: string[];
	title?: string;
	body?: string;
	nextStep?: string;
	notes?: ConversationNotes;
}

type ReadFeedbackPiece = FeedbackPieceOf<string[]>;

type FeedbackPiece = FeedbackPieceOf<EvidenceRef[]>;

function feedbackBody(piece: Pick<FeedbackPiece, "title" | "body" | "nextStep" | "notes">): string {
	const sections = [piece.title, piece.body, piece.nextStep];
	for (const [field, label] of [
		["situation", "Situation"],
		["capability", "Capability"],
		["evidenceSummary", "Evidence"],
		["inConversationLearning", "In conversation"],
		["alreadySaid", "Earlier feedback"],
	] as const) {
		const text = piece.notes?.[field];
		if (hasText(text)) {
			sections.push(`${label}: ${text}`);
		}
	}
	return sections.filter(hasText).join("\n\n");
}

export type CompositionPhase = "PUBLIC_REVIEW" | "PRIVATE_FEEDBACK";

export type CompositionFailureReason =
	| "MODEL_ERROR"
	| "RUNTIME_ERROR"
	| "BUDGET"
	| "STALL"
	| "SAFETY"
	| "LOOP"
	| "NO_DECISION"
	| "SERVER_REFUSED"
	| PublicationRefusal;

interface CompositionFailure {
	phase: CompositionPhase;
	reason: CompositionFailureReason;
}

export interface ComposedFeedback extends ComposedFeedbackEnvelope {
	compositionFailures: CompositionFailure[];
	contractVersion: number;
	admissionDigest: string | null;
	results: LeanResult[];
	preparedTargets: PreparedFeedbackTarget[];
	feedback: FeedbackPiece[];
	review: ComposedReview | null;
	/** The context of the history read the stored review was composed from; set and cleared with `review`. */
	publicationContext?: string | null;
	preparation: FeedbackPreparationReceipt | null;
}

interface ReportFeedbackDetails {
	stored: number;
	destination?: Destination;
	total?: number;
}

function skipped(text: string): { stored: boolean; text: string } {
	return { stored: false, text };
}

function validateConversationPiece(piece: ReadFeedbackPiece): string | null {
	if (hasText(piece.body) || hasText(piece.nextStep)) {
		return "CONVERSATION takes notes{situation,capability,evidenceSummary,inConversationLearning}, not body/nextStep - nothing for this destination is read out; skipped.";
	}
	const { notes } = piece;
	for (const field of [
		"situation",
		"capability",
		"evidenceSummary",
		"inConversationLearning",
	] as const) {
		if (isBlank(notes?.[field])) {
			return `CONVERSATION needs notes.${field}; skipped.`;
		}
	}
	return null;
}

function normalizeQuotedText(value: string): string {
	return value
		.normalize("NFKC")
		.replaceAll(/[“”„‟]/gu, '"')
		.replaceAll(/[‘’‚‛]/gu, "'")
		.replaceAll(/\s+/gu, " ")
		.trim()
		.toLowerCase();
}

export function notMetPractices(
	results: readonly { practiceSlug: string; outcome: Outcome }[],
): string[] {
	return [
		...new Set(
			results.filter((result) => result.outcome === "NOT_MET").map((result) => result.practiceSlug),
		),
	].toSorted();
}

const FEEDBACK_TEXT_GUIDANCE = {
	body: {
		target: 600,
		example:
			"Both changes name the new behavior but leave its purpose unstated. Reviewers cannot judge the scope from that alone.",
	},
	nextStep: {
		target: 300,
		example:
			"Before your next review request, check that the description explains why the change is needed.",
	},
	situation: {
		target: 300,
		example:
			"The developer names changes without explaining their purpose across two review requests.",
	},
	capability: {
		target: 300,
		example:
			"The developer can distinguish a description of changed behavior from a reason for the change.",
	},
	evidenceSummary: {
		target: 600,
		example:
			"The descriptions of ‘Update sign-in’ and ‘Add search’ name the changes but give no reason for them.",
	},
	inConversationLearning: {
		target: 300,
		example:
			"The developer explains which decision a reviewer cannot assess without knowing the change’s purpose.",
	},
	alreadySaid: {
		target: 300,
		example: "Earlier practice-page feedback asked for the reason behind each change.",
	},
} as const;

const FEEDBACK_TEXT_BOUNDS = {
	title: 255,
	body: FEEDBACK_TEXT_GUIDANCE.body.target * 2,
	nextStep: FEEDBACK_TEXT_GUIDANCE.nextStep.target * 2,
	supersedesThreadKey: 64,
	situation: FEEDBACK_TEXT_GUIDANCE.situation.target * 2,
	capability: FEEDBACK_TEXT_GUIDANCE.capability.target * 2,
	evidenceSummary: FEEDBACK_TEXT_GUIDANCE.evidenceSummary.target * 2,
	inConversationLearning: FEEDBACK_TEXT_GUIDANCE.inConversationLearning.target * 2,
	alreadySaid: FEEDBACK_TEXT_GUIDANCE.alreadySaid.target * 2,
} as const;

const optionalFeedbackText = (field: keyof typeof FEEDBACK_TEXT_BOUNDS) => {
	const guidance =
		field === "title" || field === "supersedesThreadKey"
			? undefined
			: FEEDBACK_TEXT_GUIDANCE[field];
	const lengthError =
		guidance === undefined
			? undefined
			: `Aim for at most ${guidance.target} characters; more than ${FEEDBACK_TEXT_BOUNDS[field]} is refused. ` +
				`Keep the evidence and its qualifications; remove repetition. ` +
				`Corrected text example (synthetic; use this developer's supported facts): ${JSON.stringify(guidance.example)}. Send this piece again`;
	return z
		.string()
		.trim()
		.max(FEEDBACK_TEXT_BOUNDS[field], lengthError)
		.nullish()
		.transform((value) => (value === "" ? undefined : (value ?? undefined)));
};
const feedbackPieceSchema = z.strictObject({
	destination: z.enum(PRIVATE_DESTINATIONS),
	practiceSlug: z.string().trim().min(1),
	action: z.enum(ACTIONS),
	basedOn: z.array(z.string().trim().min(1)).default([]),
	supersedesThreadKey: optionalFeedbackText("supersedesThreadKey"),
	witnessIds: z.array(z.string().trim().min(1)).optional(),
	withholdReason: z
		.enum(WITHHOLD_REASONS)
		.nullish()
		.transform((value) => value ?? undefined),
	title: optionalFeedbackText("title"),
	body: optionalFeedbackText("body"),
	nextStep: optionalFeedbackText("nextStep"),
	notes: z
		.strictObject({
			situation: optionalFeedbackText("situation"),
			capability: optionalFeedbackText("capability"),
			evidenceSummary: optionalFeedbackText("evidenceSummary"),
			inConversationLearning: optionalFeedbackText("inConversationLearning"),
			alreadySaid: optionalFeedbackText("alreadySaid"),
		})
		.nullish()
		.transform((value) => value ?? undefined),
});

function readFeedbackPiece(
	value: unknown,
	practiceSlugs: readonly string[],
	results: readonly AdmittedResult[],
): ReadFeedbackPiece | string {
	const parsed = feedbackPieceSchema.safeParse(value);
	if (!parsed.success) {
		return parsed.error.issues
			.map((issue) => `${issue.path.join(".") || "piece"}: ${issue.message}`)
			.join("; ");
	}
	const piece = parsed.data;
	if (!practiceSlugs.includes(piece.practiceSlug)) {
		return `practiceSlug '${piece.practiceSlug}' is not a practice with an admitted result in this run (those are: ${practiceSlugs.join(", ")}); skipped.`;
	}
	if (piece.basedOn.length === 0) {
		const ids = results
			.filter((result) => result.practiceSlug === piece.practiceSlug)
			.map((result) => result.resultId);
		return `basedOn is required: the admitted result or problem id(s) this piece rests on (${piece.practiceSlug}'s results: ${ids.join(", ")}); skipped.`;
	}
	return piece;
}

// Practice-page feedback describes a pattern across work, not a quotation of this item.
function validatePracticePagePiece(
	piece: ReadFeedbackPiece,
	named: ReadonlyMap<string, { result: AdmittedResult }>,
): string | null {
	const { body } = piece;
	if (!hasText(body?.trim())) {
		return "PRACTICE_PAGE needs a body; skipped.";
	}
	const normalizedBody = normalizeQuotedText(body);
	const repeatsCurrentEvidence = piece.basedOn.some((id) => {
		const result = named.get(id)?.result;
		return [
			...(result?.citations ?? []),
			...(result?.problems ?? []).flatMap((problem) => problem.citations),
		].some((citation) => {
			const quote = normalizeQuotedText(optionalString(citation.quote) ?? "");
			return quote.length >= 12 && normalizedBody.includes(quote);
		});
	});
	if (repeatsCurrentEvidence) {
		return "PRACTICE_PAGE describes a pattern across items; do not copy a current item quote into it. Skipped.";
	}
	return null;
}

function validateFeedback(
	piece: ReadFeedbackPiece,
	results: ReadonlyMap<string, AdmittedResult>,
	preparedTargets: readonly PreparedFeedbackTarget[],
	deliveredWitnesses: ReadonlyMap<string, PriorAdviceWitness>,
): string | null {
	const evidenceError = validateFeedbackEvidence(piece.practiceSlug, piece.basedOn, results);
	if (evidenceError !== null) {
		return evidenceError;
	}
	const named = namedEvidence(results);
	if (
		piece.destination === "PRACTICE_PAGE" &&
		piece.basedOn.some((id) => named.get(id)?.result.practiceSlug !== piece.practiceSlug)
	) {
		return "A PRACTICE_PAGE card addresses one practice; basedOn must name only that practice's NOT_MET results or problems. Skipped.";
	}
	if (piece.action === "WITHHOLD") {
		const reason = piece.withholdReason;
		if (!hasText(reason)) {
			return "WITHHOLD needs a withholdReason; skipped.";
		}
		// As on the work: ALREADY_SAID needs words recorded as said by the history read; any other reason also needs a
		// claim still current, delivered before this work was captured.
		const flag = reason === "ALREADY_SAID" ? "eligibleForAlreadySaid" : "eligibleForPriorAdvice";
		const witnesses = (slug: string, id: string) =>
			deliveredWitnesses.get(`${piece.destination}:${slug}:${id}`)?.[flag] === true;
		const witnessedSlugs = notMetPractices(
			piece.basedOn.flatMap((id) => named.get(id)?.result ?? []),
		);
		// The withholding settles each NOT_MET practice in basedOn (undecidedPrivatePractices), so each needs a record here.
		const unrecorded = witnessedSlugs.filter(
			(slug) =>
				![...deliveredWitnesses].some(
					([key, witness]) => key.startsWith(`${piece.destination}:${slug}:`) && witness[flag],
				),
		);
		if ([...PRIOR_ADVICE_REASONS].some((prior) => prior === reason) && unrecorded.length > 0) {
			const needed =
				reason === "ALREADY_SAID"
					? "not withdrawn, with its words, delivered by the history read; its claim may be current or stale"
					: "not withdrawn, with its words, its claim still current, delivered before this work was captured";
			return (
				`${reason} rests on feedback recorded as delivered on ${piece.destination} for ${unrecorded.join(", ")} in the ` +
				`delivered-feedback history: ${needed}, for each NOT_MET practice in basedOn. The history does not establish that prerequisite; prepared ` +
				`feedback and other destinations do not count. Decide this destination on its own evidence: the piece it supports, ` +
				`or WITHHOLD with BELOW_BAR. Skipped.`
			);
		}
		if (reason !== "BELOW_BAR" && (piece.witnessIds?.length ?? 0) === 0) {
			return `${reason} needs witnessIds from the delivered-feedback file; skipped.`;
		}
		const witnessIds = piece.witnessIds ?? [];
		if (
			witnessIds.some((id) => !witnessedSlugs.some((slug) => witnesses(slug, id))) ||
			witnessedSlugs.some(
				(slug) => reason !== "BELOW_BAR" && !witnessIds.some((id) => witnesses(slug, id)),
			)
		) {
			return `Witnesses must name delivered feedback that can stand for ${reason} for every supported practice on this destination; skipped.`;
		}
		return null;
	}
	if (isBlank(piece.title)) {
		return "A piece that is not a WITHHOLD needs a title; skipped.";
	}
	if (piece.destination === "PRACTICE_PAGE" && piece.action === "SUPERSEDE") {
		return "A practice-page card replaces the open card automatically; use NEW and name no target. Skipped.";
	}
	if (piece.action === "SUPERSEDE") {
		if (!hasText(piece.supersedesThreadKey)) {
			return "SUPERSEDE needs a supersedesThreadKey; skipped.";
		}
		if (
			!preparedTargets.some(
				(target) =>
					target.threadKey === piece.supersedesThreadKey &&
					target.destination === piece.destination &&
					target.practiceSlug === piece.practiceSlug,
			)
		) {
			return `No queued message has threadKey '${piece.supersedesThreadKey}' on this destination and practice; it must come from the prepared-feedback file. Skipped.`;
		}
	}
	if (piece.destination === "CONVERSATION") {
		return validateConversationPiece(piece);
	}
	if (piece.notes) {
		return "Only CONVERSATION feedback may carry a notes block; skipped.";
	}
	if (isBlank(piece.nextStep)) {
		return `${piece.destination} needs a nextStep; skipped.`;
	}
	return validatePracticePagePiece(piece, named);
}

export function persistComposedFeedback(composedFeedback: ComposedFeedback, output: string): void {
	for (const piece of undeliverableFeedback(composedFeedback)) {
		const { destination, practiceSlug, supersedesThreadKey } = piece;
		console.error(
			destination === undefined || practiceSlug === undefined || supersedesThreadKey === undefined
				? `[pi-runner] composed feedback the server cannot deliver: a SUPERSEDE piece lacks the destination, ` +
						`practice or thread it replaces: ${JSON.stringify({ destination, practiceSlug, supersedesThreadKey })}`
				: `[pi-runner] composed feedback the server cannot deliver: ${destination}/${practiceSlug} ` +
						`supersedes '${supersedesThreadKey}', which this envelope does not list as staged`,
		);
	}
	const feedback = composedFeedback.feedback.toSorted(
		(a, b) => Number(a.action === "WITHHOLD") - Number(b.action === "WITHHOLD"),
	);
	writeFileSync(output, JSON.stringify({ ...composedFeedback, feedback }, null, 2));
}

/** Null unused copy fields and absent witness lists carry no evidence; basedOn is never inferred. */
export function prepareFeedbackArguments(raw: unknown): unknown {
	const input = unwrapToolArguments(raw);
	if (!isRecord(input)) {
		return input;
	}
	let listed = input.feedback;
	if (typeof listed === "string") {
		try {
			const decoded: unknown = JSON.parse(listed);
			listed = decoded;
		} catch {
			return input;
		}
	}
	const pieces = isRecord(listed) ? [listed] : listed;
	if (!Array.isArray(pieces)) {
		return input;
	}
	return {
		...input,
		feedback: pieces.map((piece: unknown) => {
			if (!isRecord(piece)) {
				return piece;
			}
			const absent = new Set([
				"supersedesThreadKey",
				"title",
				"body",
				"nextStep",
				"notes",
				"withholdReason",
			]);
			const prepared = Object.fromEntries(
				Object.entries(piece).filter(([key, value]) => value !== null || !absent.has(key)),
			);
			if (prepared.witnessIds === null) {
				prepared.witnessIds = [];
			}
			if (isRecord(prepared.notes) && prepared.notes.alreadySaid === null) {
				const { alreadySaid: _absent, ...notes } = prepared.notes;
				prepared.notes = notes;
			}
			return prepared;
		}),
	};
}

export function buildFeedbackTool(
	practiceSlugs: string[],
	request: CompositionRequest,
	results: readonly AdmittedResult[],
	preparedTargets: PreparedFeedbackTarget[],
	deliveredWitnesses: ReadonlyMap<string, PriorAdviceWitness>,
	runtime: FeedbackToolRuntime,
) {
	runtime.feedback.preparedTargets = preparedTargets;
	const enabledDestinations = PRIVATE_DESTINATIONS.filter(
		(destination) => request.destinations[destination] !== undefined,
	);
	const usedPerDestination: Record<PrivateDestination, number> = {
		PRACTICE_PAGE: 0,
		CONVERSATION: 0,
	};
	const seen = new Set<string>();

	const store = (value: unknown): { stored: boolean; text: string } => {
		const read = readFeedbackPiece(value, practiceSlugs, results);
		if (typeof read === "string") {
			return { stored: false, text: read };
		}
		const piece = read;
		// Read current admitted results per call; the tool exists before admission fills them.
		const admitted = resultsById(results);
		const bounds = request.destinations[piece.destination];
		if (bounds === undefined) {
			return skipped(
				`${piece.destination} is not a destination this run may write for (open: ${enabledDestinations.join(", ")}); skipped.`,
			);
		}
		const key = `${piece.destination}:${piece.practiceSlug}`;
		if (seen.has(key)) {
			return skipped(
				`already have a ${piece.destination} piece for ${piece.practiceSlug}, and the first one stands; nothing to resend for it. Skipped.`,
			);
		}
		const delivers = piece.action !== "WITHHOLD";
		if (delivers && usedPerDestination[piece.destination] >= bounds.maxPieces) {
			return skipped(
				`${piece.destination} holds at most ${bounds.maxPieces} piece(s) and is full: this one does not fit — WITHHOLD it with BELOW_BAR or leave it out. Skipped.`,
			);
		}
		const rejection = validateFeedback(piece, admitted, preparedTargets, deliveredWitnesses);
		if (rejection !== null) {
			return skipped(rejection);
		}
		const supportingSlugs = [
			...new Set(
				piece.basedOn.flatMap((id) => namedEvidence(admitted).get(id)?.result.practiceSlug ?? []),
			),
		];
		const ineligible = supportingSlugs.find(
			(slug) => !(runtime.routes(slug) ?? []).includes(piece.destination),
		);
		if (ineligible !== undefined) {
			return skipped(
				`${piece.destination} cannot carry feedback about ${piece.practiceSlug} this run ` +
					`(${runtime.destinationRefusal(piece.destination, ineligible)}); write no piece for it there, not even a WITHHOLD. Skipped.`,
			);
		}
		if (piece.destination === "PRACTICE_PAGE" && delivers) {
			const pieces = runtime.negativeItems(piece.practiceSlug);
			if (pieces < request.minDistinctItems) {
				return skipped(
					`PRACTICE_PAGE needs a pattern across at least ${request.minDistinctItems} pieces of work, and ` +
						`${piece.practiceSlug} has support on ${pieces} (the PRACTICE_PAGE support read); WITHHOLD it ` +
						`with BELOW_BAR. Skipped.`,
				);
			}
		}
		seen.add(key);
		if (delivers) {
			usedPerDestination[piece.destination] += 1;
		}
		runtime.feedback.feedback.push({
			...piece,
			basedOn: evidenceRefs(piece.basedOn, namedEvidence(admitted)),
		});
		return {
			stored: true,
			text: `stored a ${piece.destination} piece for ${piece.practiceSlug} (${piece.action}); ${usedPerDestination[piece.destination]}/${bounds.maxPieces} used on that destination.`,
		};
	};

	const properties = {
		destination: {
			type: "string",
			enum: enabledDestinations,
			description: "Which surface this piece is for. Each has its own level and its own rules.",
		},
		practiceSlug: {
			type: "string",
			enum: practiceSlugs,
			description: "The primary practice. A practice-page card uses only this practice.",
		},
		basedOn: {
			type: "array",
			minItems: 1,
			items: { type: "string", minLength: 1 },
			description:
				"What this rests on: admitted ids from this run, a result's resultId or, for one located problem, a problem's problemId. Related NOT_MET practices may support CONVERSATION only when each is eligible there.",
		},
		action: {
			type: "string",
			enum: ACTIONS,
			description:
				"NEW to say something; SUPERSEDE to replace prepared feedback that has not been delivered; " +
				"WITHHOLD to record, with a reason, that you decided to stay quiet.",
		},
		supersedesThreadKey: {
			type: "string",
			maxLength: FEEDBACK_TEXT_BOUNDS.supersedesThreadKey,
			description:
				"Required for SUPERSEDE: the threadKey of an entry in the task-declared prepared-feedback file. " +
				"You may not name a key that is not in that file.",
		},
		witnessIds: {
			type: "array",
			items: { type: "string" },
			description: "Delivered feedback IDs that witness prior advice; empty for BELOW_BAR.",
		},
		withholdReason: { type: "string", enum: WITHHOLD_REASONS },
		title: {
			type: "string",
			maxLength: FEEDBACK_TEXT_BOUNDS.title,
			description: "Names the issue in a few words. Never names the person.",
		},
		body: {
			type: "string",
			maxLength: FEEDBACK_TEXT_BOUNDS.body,
			description:
				`PRACTICE_PAGE only: aim for at most ${FEEDBACK_TEXT_GUIDANCE.body.target} characters. ` +
				"The evidenced pattern across the work and why it matters; the future way of working belongs to nextStep. Grounded in current results and their problems; never quote a line or claim change over time from the pre-run history. Markdown, read verbatim, without headings or internal labels. The UI renders the title and next step separately.",
		},
		nextStep: {
			type: "string",
			maxLength: FEEDBACK_TEXT_BOUNDS.nextStep,
			description:
				`PRACTICE_PAGE only: aim for at most ${FEEDBACK_TEXT_GUIDANCE.nextStep.target} characters. ` +
				"The one repeatable way of working for the next piece of work; the body does not state it. No heading or label. Name the missing decision, not a heading/template unless the practice requires one; never provide paste-ready prose.",
		},
		notes: {
			type: "object",
			additionalProperties: false,
			required: ["situation", "capability", "evidenceSummary", "inConversationLearning"],
			description:
				"CONVERSATION only. Notes TO the mentor, which composes the whole turn itself, later, " +
				"with the live conversation in front of it. Write what it needs to know, never a " +
				"sentence for it to say: anything phrased as a line of dialogue will be spoken, and " +
				"will sound like a script. " +
				`Aim for at most ${FEEDBACK_TEXT_GUIDANCE.situation.target} characters per field, ` +
				`or ${FEEDBACK_TEXT_GUIDANCE.evidenceSummary.target} for evidenceSummary. No headings or internal labels in field contents; each field adds its own fact.`,
			properties: {
				situation: {
					type: "string",
					maxLength: FEEDBACK_TEXT_BOUNDS.situation,
					description:
						"The concern the problems have in common, as it can be observed, stated once rather than " +
						"listing each item again. Your words about it, not words for it - third person, never " +
						"addressed to the developer as 'you', and never a judgement of the person.",
				},
				capability: {
					type: "string",
					maxLength: FEEDBACK_TEXT_BOUNDS.capability,
					description:
						"The self-check of the assessed behaviour this conversation should support: how the developer can tell whether their own work shows it, not only whether something is wired up. State the capability, not a solution such as a required heading/template, and not a question, script, diagnosis, or fixed tactic.",
				},
				evidenceSummary: {
					type: "string",
					maxLength: FEEDBACK_TEXT_BOUNDS.evidenceSummary,
					description:
						"Where the concern occurred, compactly: each supporting work and problem located once. " +
						"Summarise rather than inventing a quote; the original result evidence is " +
						"staged separately for the mentor to inspect.",
				},
				inConversationLearning: {
					type: "string",
					maxLength: FEEDBACK_TEXT_BOUNDS.inConversationLearning,
					description:
						"Understanding the developer articulates in a way that can be observed before the conversation ends: a distinction, decision, question, or self-check they say. Not a promise, future output, message text, or compliance target.",
				},
				alreadySaid: {
					type: "string",
					maxLength: FEEDBACK_TEXT_BOUNDS.alreadySaid,
					description:
						"Optional. Relevant prior communication the shown history records, and movement the shown evidence supports. Omit when none is shown. Partial or missing history leaves coverage unknown: it never proves that nothing was said or that a concern was resolved.",
				},
			},
		},
	};
	const common = ["destination", "practiceSlug", "basedOn", "action"];
	const branch = (destination: PrivateDestination, action: FeedbackAction) => {
		let fields: string[];
		if (action === "WITHHOLD") {
			fields = ["withholdReason", "witnessIds"];
		} else if (destination === "PRACTICE_PAGE") {
			fields = ["title", "body", "nextStep"];
		} else {
			fields = ["title", "notes", ...(action === "SUPERSEDE" ? ["supersedesThreadKey"] : [])];
		}
		const keys = [...common, ...fields];
		return {
			type: "object",
			additionalProperties: false,
			required: keys,
			properties: {
				...Object.fromEntries(Object.entries(properties).filter(([key]) => keys.includes(key))),
				destination: { type: "string", enum: [destination] },
				action: { type: "string", enum: [action] },
			},
		};
	};
	const branches = enabledDestinations.flatMap((destination) => [
		branch(destination, "WITHHOLD"),
		branch(destination, "NEW"),
		...(destination === "CONVERSATION" ? [branch(destination, "SUPERSEDE")] : []),
	]);

	const parameters = {
		type: "object",
		additionalProperties: false,
		required: ["feedback"],
		properties: { feedback: { type: "array", minItems: 1, items: { anyOf: branches } } },
	};
	// Each piece is stored or refused on its own; envelope preparation must preserve valid siblings.
	const validateArguments = toolArgumentValidator(
		{
			...parameters,
			properties: { feedback: { type: "array", minItems: 1, items: {} } },
		},
		parameters,
	);
	return defineTool({
		name: "report_feedback",
		prepareArguments: (raw) => validateArguments(prepareFeedbackArguments(raw)),
		constrainedSampling: { type: "json_schema", strict: "prefer" },
		exposure: "model-only",
		label: "Report Feedback",
		description:
			"Persist feedback for the developer's practice pages and the mentor conversation, one per " +
			"destination and practice. Send every piece you have ready in one call; each is stored or skipped on " +
			"its own, with the reason, and a skipped piece can be sent again corrected without re-sending the " +
			"stored ones. This is an intervention, not a measurement: it takes no outcome, severity or " +
			"confidence, and no citation you typed yourself. " +
			`Aim for at most ${FEEDBACK_TEXT_GUIDANCE.body.target} characters in a card body, ${FEEDBACK_TEXT_GUIDANCE.nextStep.target} in its nextStep; keep title/body/nextStep separate. ` +
			`Mentor notes aim for ${FEEDBACK_TEXT_GUIDANCE.situation.target} per field, ${FEEDBACK_TEXT_GUIDANCE.evidenceSummary.target} for evidenceSummary. ` +
			"More than twice a field's target is refused with a correction example, never truncated. " +
			"Keep the evidence and its qualifications. No headings or internal labels in field contents. Use natural evidence references, never internal coordinates. " +
			'Input example for a practice page: {"feedback":[{"destination":"PRACTICE_PAGE","practiceSlug":"<admitted practice slug>","basedOn":["<its NOT_MET resultId or problemId>"],"action":"NEW","title":"Name the concern","body":"Explain the supported concern.","nextStep":"One action that addresses it."}]}. ' +
			'For WITHHOLD, send destination, practiceSlug, basedOn, action:"WITHHOLD", withholdReason and witnessIds:[]; omit title, body, nextStep and notes.',
		parameters,
		execute: async (toolCallId, params): Promise<AgentToolResult<ReportFeedbackDetails>> => {
			if (!runtime.admitted()) {
				return {
					content: [
						{
							type: "text",
							text: "Feedback composition opens only after Java admits the completed results.",
						},
					],
					details: { stored: 0 },
				};
			}
			const submitted = runtime.submittedList(isRecord(params) ? params.feedback : null);
			if ("error" in submitted) {
				return runtime.refusal(toolCallId, `feedback refused — ${submitted.error}`);
			}
			if (submitted.items.length === 0) {
				return runtime.refusal(
					toolCallId,
					"feedback refused — the list is empty; send every piece you have ready in it",
				);
			}
			const outcomes = submitted.items.map(store);
			const stored = outcomes.filter((outcome) => outcome.stored).length;
			runtime.onStored(stored);
			if (stored > 0) {
				runtime.persist();
			}
			const text = outcomes.map((outcome, index) => `#${index + 1}: ${outcome.text}`).join("\n");
			if (stored === 0) {
				return runtime.refusal(toolCallId, text);
			}
			return {
				content: [{ type: "text", text }],
				details: { stored, total: runtime.feedback.feedback.length },
				terminate: runtime.paid(),
			};
		},
	});
}

export interface PreparationOptions {
	feedback: ComposedFeedback;
	phase?: CompositionPhase;
	admitted: boolean;
	expired: boolean;
	url: string;
	token: string;
	task: { reviewId: string; jobId: string; attempt: number };
	timeoutMs: number;
	persist: () => void;
	recordFailure: (phase: CompositionPhase, reason: CompositionFailureReason) => void;
}

/**
 * Prepares only the stored public review, while its composer can still answer a typed refusal. A typed refusal leaves
 * the review for the caller to withdraw; any other refusal ends it as before; a lost answer leaves it stored, so the
 * later preparation replays the same words.
 */
export async function preparePublicReview(
	options: PreparationOptions,
): Promise<FeedbackPreparation | null> {
	if (!options.admitted || options.expired) {
		return null;
	}
	const entry = preparationPackages(options.feedback, "PUBLIC_REVIEW").find(
		(candidate) => candidate.publicComposition,
	);
	if (entry === undefined) {
		return null;
	}
	const result = await prepareFeedback({
		url: options.url,
		token: options.token,
		payload: {
			...options.task,
			basisResultIds: [...new Set(entry.composition.flatMap((piece) => piece.resultIds))],
			composition: entry.composition,
			publicComposition: true,
			publicationContext: options.feedback.publicationContext ?? null,
		},
		timeoutMs: options.timeoutMs,
	});
	if (result.state === "PREPARED") {
		options.feedback.preparation = result.receipt;
		options.persist();
	} else if (result.state === "REFUSED" && result.publication === undefined) {
		options.feedback.review = null;
		options.feedback.publicationContext = null;
		options.recordFailure("PUBLIC_REVIEW", "SERVER_REFUSED");
		options.persist();
	}
	console.error(`[pi-runner] public review preparation: ${result.state}`);
	return result;
}

export async function prepareComposedFeedback(options: PreparationOptions): Promise<void> {
	const { feedback: composedFeedback } = options;
	// A receipt already holds the public review accepted while its session ran; only a lost answer needs this replay.
	if (
		!options.admitted ||
		options.expired ||
		(options.phase === "PUBLIC_REVIEW" && composedFeedback.preparation !== null)
	) {
		return;
	}
	const packages = preparationPackages(composedFeedback, options.phase);
	const refused = new Set<number>();
	for (const entry of packages) {
		const result = await prepareFeedback({
			url: options.url,
			token: options.token,
			payload: {
				...options.task,
				basisResultIds: [...new Set(entry.composition.flatMap((piece) => piece.resultIds))],
				composition: entry.composition,
				publicComposition: entry.publicComposition,
				publicationContext: entry.publicComposition
					? (composedFeedback.publicationContext ?? null)
					: null,
			},
			timeoutMs: options.timeoutMs,
		});
		if (result.state === "REFUSED") {
			if (entry.publicComposition) {
				composedFeedback.review = null;
				composedFeedback.publicationContext = null;
				options.recordFailure("PUBLIC_REVIEW", result.publication ?? "SERVER_REFUSED");
			} else if (entry.privateIndex !== undefined) {
				refused.add(entry.privateIndex);
				options.recordFailure("PRIVATE_FEEDBACK", result.publication ?? "SERVER_REFUSED");
			}
		}
		if (result.state === "PREPARED") {
			composedFeedback.preparation = result.receipt;
		}
		console.error(`[pi-runner] feedback preparation: ${result.state}`);
	}
	composedFeedback.feedback = composedFeedback.feedback.filter((_, index) => !refused.has(index));
	options.persist();
}

function preparationPackages(
	composedFeedback: ComposedFeedback,
	phase: CompositionPhase | undefined,
): { composition: FeedbackComposition[]; publicComposition: boolean; privateIndex?: number }[] {
	const packages: {
		composition: FeedbackComposition[];
		publicComposition: boolean;
		privateIndex?: number;
	}[] = [];
	const { review } = composedFeedback;
	if (review !== null && phase !== "PRIVATE_FEEDBACK") {
		const refs = [
			...(review.summary?.basedOn ?? []),
			...review.inline.flatMap((note) => note.basedOn),
			...review.withheld.flatMap((piece) => piece.basedOn),
		];
		const resultIds = [...new Set(refs.map((ref) => ref.resultId))];
		const quiet = review.summary === null && review.inline.length === 0;
		packages.push({
			publicComposition: true,
			composition:
				resultIds.length === 0
					? []
					: [
							{
								destination: "ON_WORK",
								resultIds,
								summary: review.summary?.body ?? null,
								notes: review.inline.map((note) => ({ ...note.anchor, body: note.body })),
								withheldReason: quiet ? "COMPOSER_WITHHELD" : null,
								page: null,
								trace: {
									copies: [
										...(review.summary === null
											? []
											: [
													{
														kind: "SUMMARY" as const,
														position: 0,
														basedOn: review.summary.basedOn,
													},
												]),
										...review.inline.map((note, index) => ({
											kind: "LINE_NOTE" as const,
											position: index + (review.summary === null ? 0 : 1),
											basedOn: note.basedOn,
										})),
									],
									withheld: review.withheld,
									replacesId: null,
									primaryResultId: null,
								},
							},
						],
		});
	}
	for (const [privateIndex, piece] of composedFeedback.feedback.entries()) {
		if (phase === "PUBLIC_REVIEW") {
			continue;
		}
		const quiet = piece.action === "WITHHOLD";
		const primary = composedFeedback.results.find(
			(result) => result.practiceSlug === piece.practiceSlug,
		);
		const card = piece.destination === "PRACTICE_PAGE" && !quiet;
		packages.push({
			publicComposition: false,
			privateIndex,
			composition: [
				{
					destination: piece.destination,
					resultIds: [...new Set(piece.basedOn.map((ref) => ref.resultId))],
					summary: quiet || card ? null : feedbackBody(piece),
					page:
						card &&
						piece.title !== undefined &&
						piece.body !== undefined &&
						piece.nextStep !== undefined
							? { title: piece.title, body: piece.body, nextStep: piece.nextStep }
							: null,
					notes: [],
					withheldReason: quiet ? "COMPOSER_WITHHELD" : null,
					trace: {
						copies: quiet
							? []
							: [
									{
										kind: piece.destination === "CONVERSATION" ? "CHAT_TURN" : "SUMMARY",
										position: 0,
										basedOn: piece.basedOn,
									},
								],
						withheld: quiet
							? [
									{
										basedOn: piece.basedOn,
										reason: piece.withholdReason ?? "BELOW_BAR",
										witnessIds: piece.witnessIds ?? [],
									},
								]
							: [],
						replacesId:
							piece.action === "SUPERSEDE"
								? (composedFeedback.preparedTargets.find(
										(target) =>
											target.threadKey === piece.supersedesThreadKey &&
											target.destination === piece.destination &&
											target.practiceSlug === piece.practiceSlug,
									)?.feedbackId ?? null)
								: null,
						primaryResultId: primary?.resultId ?? null,
					},
				},
			],
		});
	}
	return packages;
}

interface ReportReviewDetails {
	stored: number;
}

// A completed response can contain more calls; final composition must reject all subsequent writes.
export interface PublicReviewState {
	final: boolean;
	reviewContext: "LOST" | "RESTORING" | "HELD";
	/** Set when preparation refused this session's review with a publication code; nothing was stored. */
	refused?: PublicationRefusal;
}

/** A public review session ends, or ends because preparation refused its review with a publication code. */
export type PublicReviewOutcome = "DONE" | { refused: PublicationRefusal };

/**
 * Fresh public sessions after the initial one, only when the history under the review changed; same attempt, same
 * admitted results, same safety deadline. Each session builds its review tool and witness validator from one history
 * read. The SDK could replace a session's tools, but a bounded restart keeps that one-read contract for this rare case.
 * An unknown delivery outcome never starts a session: only a provider readback can resolve it.
 */
export const MAX_PUBLIC_RECOMPOSITIONS = 2;

const REVIEW_FINAL = "The review on this work is final; this composition accepts nothing more.";

const TEXT_RULES: Record<TextRefusal["rule"], string> = {
	LINK: "has a link that no cited quote supplies. Write no links: Hephaestus adds its own",
	MENTION:
		"has an @-mention or Slack mention. Mention no one, and put code such as `@Override` in backticks",
	HTML: "has raw HTML. Put code such as `List<String>` in backticks",
	IMAGE: "has an image. Write no images",
	HIDDEN_CHARACTER: "has a hidden or direction-changing character. Remove it",
};

/** What the composer must rewrite: the server names the rule and the field, never the text. */
export function textRefusal({ rule, field }: TextRefusal): string {
	return `${fieldName(field)} ${TEXT_RULES[rule]}.`;
}

function fieldName(field: string): string {
	if (field === "summary") {
		return "The summary";
	}
	const note = /^notes\[(?<index>\d+)\]$/u.exec(field)?.groups?.index;
	if (note !== undefined) {
		return `Inline note ${Number(note) + 1}`;
	}
	return `The card's ${field === "page.nextStep" ? "next step" : field.slice("page.".length)}`;
}

type ReviewToolRuntime = Pick<
	FeedbackToolRuntime,
	"admitted" | "feedback" | "persist" | "onStored"
> & {
	refusal: (id: string, text: string) => Promise<AgentToolResult<ReportReviewDetails>>;
	/** The context of the history read whose statements this session shows; stored with the review. */
	publicationContext?: string | null;
	/** Prepares the stored review while this session can still answer a typed refusal. */
	preparePublic?: () => Promise<FeedbackPreparation | null>;
};

export function buildReviewTool(
	lineNotes: boolean,
	restable: ReadonlyMap<string, ReviewedResult>,
	witnesses: ReturnType<typeof priorAdviceWitnesses>,
	state: PublicReviewState,
	restore: () => string,
	runtime: ReviewToolRuntime,
) {
	// Every witness a decision can name; readReview checks which reason each one may support.
	const eligible = [...witnesses].filter(([, witness]) => witness.eligibleForAlreadySaid);
	const parameters = reviewToolParameters(
		restable,
		lineNotes,
		eligible.map(([id]) => id),
	);
	const validateArguments = toolArgumentValidator(parameters);
	return defineTool({
		name: "report_review",
		prepareArguments: (raw) => {
			try {
				return prepareReviewArguments(raw);
			} catch (error) {
				validateArguments(unwrapToolArguments(raw));
				throw error;
			}
		},
		exposure: "model-only",
		label: "Report Review",
		description: REVIEW_TOOL_DESCRIPTION,
		parameters,
		execute: createReviewRecorder(lineNotes, restable, witnesses, state, restore, runtime),
	});
}

export function createReviewRecorder(
	lineNotes: boolean,
	restable: ReadonlyMap<string, ReviewedResult>,
	witnesses: ReturnType<typeof priorAdviceWitnesses>,
	state: PublicReviewState,
	restore: () => string,
	runtime: ReviewToolRuntime,
) {
	return async (
		toolCallId: string,
		params: unknown,
	): Promise<AgentToolResult<ReportReviewDetails>> => {
		if (!runtime.admitted()) {
			return runtime.refusal(
				toolCallId,
				"Feedback composition opens only after Java admits the completed results.",
			);
		}
		if (state.final) {
			return runtime.refusal(toolCallId, REVIEW_FINAL);
		}
		if (state.reviewContext !== "HELD") {
			const restored = state.reviewContext === "LOST" ? `\n${restore()}\n` : "";
			state.reviewContext = "RESTORING";
			return runtime.refusal(
				toolCallId,
				`review refused, nothing was stored: the session's context was compacted. Use the restored review reference in the next model turn before storing the review.${restored}\nSend the whole review again.`,
			);
		}
		const read = readReview(params, restable, lineNotes, { witnesses });
		if ("errors" in read) {
			return runtime.refusal(
				toolCallId,
				`review refused, nothing was stored:\n${read.errors.map((error) => `- ${error}`).join("\n")}`,
			);
		}
		// Final only once persisted: a failed write leaves the review unaccepted, so it is never delivered.
		const previous = runtime.feedback.review;
		const previousContext = runtime.feedback.publicationContext ?? null;
		runtime.feedback.review = read.review;
		runtime.feedback.publicationContext = runtime.publicationContext ?? null;
		try {
			runtime.persist();
		} catch (error) {
			runtime.feedback.review = previous;
			runtime.feedback.publicationContext = previousContext;
			throw error;
		}
		const prepared = await runtime.preparePublic?.();
		if (prepared?.state === "REFUSED" && prepared.publication === "FEEDBACK_TEXT_REFUSED") {
			// The server prepared nothing and named the text to rewrite; this session may send the review again.
			runtime.feedback.review = previous;
			runtime.feedback.publicationContext = previousContext;
			runtime.persist();
			return runtime.refusal(
				toolCallId,
				`review refused, nothing was stored: ${textRefusal(prepared.text)}\nSend the whole review again.`,
			);
		}
		if (prepared?.state === "REFUSED" && prepared.publication !== undefined) {
			// The server prepared nothing. Changed history earns a fresh session; unknown delivery requires readback.
			runtime.feedback.review = previous;
			runtime.feedback.publicationContext = previousContext;
			runtime.persist();
			state.final = true;
			state.refused = prepared.publication;
			return {
				content: [
					{
						type: "text",
						text:
							prepared.publication === "PUBLIC_HISTORY_CHANGED"
								? "The review was not stored: feedback on this work changed while it was composed. Composition starts again from the current history."
								: "The review was not stored: another delivery of feedback on this work has an unknown outcome. It is final.",
					},
				],
				details: { stored: 0 },
				terminate: true,
			};
		}
		state.final = true;
		if (prepared?.state === "REFUSED") {
			return {
				content: [
					{ type: "text", text: "The server refused the review; nothing was stored. It is final." },
				],
				details: { stored: 0 },
				terminate: true,
			};
		}
		runtime.onStored(1);
		const { summary, inline, withheld } = read.review;
		const said = summary
			? `a summary resting on ${summary.basedOn.length} result(s) and problem(s)`
			: "no summary";
		return {
			content: [
				{
					type: "text",
					text: `Stored the review: ${said}, ${inline.length} line note(s), ${withheld.length} withholding decision(s). It is final.`,
				},
			],
			details: { stored: 1 },
			terminate: true,
		};
	};
}

export interface CompositionFiles {
	requestPath: string;
	feedbackPath: string;
	/** Written by the runner from the admission's private history, for the private session only. */
	deliveredPath: string;
	preparedPath: string;
	problemsPath: string;
	supportPath: string;
	criteriaPathOf: (slug: string) => string;
}

// Only the runner writes these, at the start of the private phase: a session with bash could otherwise leave files
// that the guards would read as history. Without a server read there is none, which the turn reports as not staged.
// capturedAt is when the reviewed work was captured, from its folder index; null fails every prior-advice witness.
export function stagePrivateHistory(
	history: Admission["privateHistory"],
	files: CompositionFiles,
	capturedAt: string | null,
): {
	preparedTargets: PreparedFeedbackTarget[];
	deliveredWitnesses: Map<string, PriorAdviceWitness>;
} {
	rmSync(files.deliveredPath, { force: true });
	rmSync(files.preparedPath, { force: true });
	if (history !== null) {
		writePrivateHistory(history, files);
	}
	return {
		preparedTargets: stagedPreparedTargets(files.preparedPath),
		deliveredWitnesses: stagedDeliveredWitnesses(files.deliveredPath, capturedAt),
	};
}

function writePrivateHistory(
	history: NonNullable<Admission["privateHistory"]>,
	files: CompositionFiles,
): void {
	const { readAt, coverage } = history;
	mkdirSync(nodePath.dirname(files.deliveredPath), { recursive: true });
	mkdirSync(nodePath.dirname(files.preparedPath), { recursive: true });
	writeFileSync(
		files.deliveredPath,
		JSON.stringify(
			{ readAt, coverage, recordRole: "RECORDED_DELIVERY", feedback: history.delivered },
			null,
			2,
		),
	);
	writeFileSync(
		files.preparedPath,
		JSON.stringify(
			{ readAt, coverage, recordRole: "PREPARED_NOT_DELIVERED", prepared: history.queued },
			null,
			2,
		),
	);
}

function coordinatesOf(citation: unknown): unknown {
	if (!isRecord(citation)) {
		return citation;
	}
	const { quote: _quote, verification: _verification, ...coordinates } = citation;
	return coordinates;
}

const COMPOSITION_BLOCK_LIMIT = 32_000;

function stagedBlock(label: string, file: string, limit = COMPOSITION_BLOCK_LIMIT): string {
	if (!existsSync(file)) {
		return "";
	}
	const text = readFileSync(file, "utf8").trim();
	return text.length > limit
		? `### ${label} — too large to show here; read \`${file}\`\n`
		: `### ${label}\n\`\`\`json\n${text}\n\`\`\`\n`;
}

export function routedTo(
	admission: Admission,
	destination: PrivateDestination,
	practiceSlug: string,
): boolean {
	if (destination === "PRACTICE_PAGE") {
		return (
			admission.practicePageSupport?.state === "COMPLETE" &&
			admission.practicePageSupport.practices.some(
				(practice) => practice.practiceSlug === practiceSlug && practice.decision === "ADMIT",
			)
		);
	}
	if (admission.conversationEligibility?.state !== "COMPLETE") {
		return false;
	}
	const admitted = new Set(
		admission.conversationEligibility.results
			.filter((route) => route.decision === "ADMIT")
			.map((route) => route.resultId),
	);
	return admission.results.some(
		(result) =>
			result.practiceSlug === practiceSlug &&
			result.outcome === "NOT_MET" &&
			admitted.has(result.resultId),
	);
}

export function destinationRefusal(
	admission: Admission,
	destination: PrivateDestination,
	practiceSlug: string,
): string {
	if (admission.privateProgress.decidedPractices.includes(practiceSlug)) {
		return "already decided by private feedback an earlier attempt of this review prepared";
	}
	if (destination === "PRACTICE_PAGE") {
		const decision =
			admission.practicePageSupport?.state === "COMPLETE"
				? admission.practicePageSupport.practices.find(
						(practice) => practice.practiceSlug === practiceSlug,
					)?.decision
				: undefined;
		return decision ?? "not routed";
	}
	const ids = new Set(
		admission.results
			.filter((result) => result.practiceSlug === practiceSlug)
			.map((result) => result.resultId),
	);
	const decisions =
		admission.conversationEligibility?.state === "COMPLETE"
			? admission.conversationEligibility.results
					.filter((route) => ids.has(route.resultId))
					.map((route) => route.decision)
			: [];
	return decisions.length > 0 ? [...new Set(decisions)].join(", ") : "not routed";
}

// Enforce snapshot-dependent constraints here for fast model correction; Java rechecks them.
export function negativePiecesOfWork(admission: Admission, practiceSlug: string): number {
	if (admission.practicePageSupport?.state !== "COMPLETE") {
		return 0;
	}
	const support = admission.practicePageSupport.practices.find(
		(practice) => practice.practiceSlug === practiceSlug,
	);
	return new Set(
		(support?.occurrences ?? []).map((occurrence) => `${occurrence.itemKind}:${occurrence.itemId}`),
	).size;
}

export function composerView(result: AdmittedResult): Record<string, unknown> {
	const citations = result.citations.map(coordinatesOf);
	if (result.outcome !== "NOT_MET" && result.outcome !== "MET") {
		const { resultId, practiceSlug, outcome } = result;
		return {
			resultId,
			practiceSlug,
			outcome,
			evidence: qualificationEvidence(result),
			citations,
		};
	}
	// MET counterevidence keeps the same qualifications as NOT_MET.
	const { citations: _admitted, example, problems, ...rest } = result;
	return {
		...rest,
		evidence: isRecord(result.evidence) ? { ...result.evidence, citations } : result.evidence,
		...(isRecord(example)
			? { example: { ...example, citation: coordinatesOf(example.citation) } }
			: {}),
		citations,
		problems: problems.map((problem) => ({
			...problem,
			citations: problem.citations.map(coordinatesOf),
		})),
	};
}

// Composition receives criteria, but not measuring leads or source directions that could initiate another assessment.
function notMetCriteria(
	results: readonly AdmittedResult[],
	criteriaPathOf: (slug: string) => string,
	limit = COMPOSITION_BLOCK_LIMIT,
): string {
	return notMetPractices(results)
		.map((slug) => {
			const file = criteriaPathOf(slug);
			if (!existsSync(file)) {
				return `### Criteria of \`${slug}\` — not staged for this review; nothing is known about them here\n`;
			}
			let text: string;
			try {
				text = readFileSync(file, "utf8").trim();
			} catch {
				return `### Criteria of \`${slug}\` — \`${file}\` could not be read; nothing is known about them here\n`;
			}
			if (text.length === 0) {
				return `### Criteria of \`${slug}\` — \`${file}\` is empty; no standard is available here\n`;
			}
			if (text.length > limit) {
				return `### Criteria of \`${slug}\` — too large to show here; read \`${file}\`\n`;
			}
			const longestRun = Math.max(
				0,
				...[...text.matchAll(/`+/gu)].map((backticks) => backticks[0].length),
			);
			const fence = "`".repeat(Math.max(3, longestRun + 1));
			return `### Criteria of \`${slug}\`\n${fence}markdown\n${text}\n${fence}\n`;
		})
		.join("\n");
}

// A history location can be named without being staged; missing and empty carry different evidence.
function unstagedHistory(label: string, file: string): string {
	return `### ${label} — \`${file}\` is not staged for this review; what it would record is unknown here, not empty\n`;
}

// The producer owns body eligibility; consume only bodies it staged.
function priorFeedbackFacts(label: string, file: string, key: "feedback" | "prepared"): string {
	if (!existsSync(file)) {
		return unstagedHistory(label, file);
	}
	const pointer = `### ${label} — read \`${file}\` for the recorded history; its contents are not shown here\n`;
	let history: unknown;
	try {
		history = JSON.parse(readFileSync(file, "utf8"));
	} catch {
		return pointer;
	}
	if (!isRecord(history) || !Array.isArray(history[key])) {
		return pointer;
	}
	const entries: unknown[] = history[key];
	if (
		entries.some(
			(entry) => !isRecord(entry) || (entry.body != null && typeof entry.body !== "string"),
		)
	) {
		return pointer;
	}
	const projected = entries.map((entry, index) => {
		if (!isRecord(entry)) {
			return entry;
		}
		const { body, ...facts } = entry;
		return {
			...facts,
			...(typeof body === "string"
				? { bodyAt: `${file} → ${key}[${index}].body (${body.length} characters)` }
				: {}),
		};
	});
	const text = JSON.stringify({ ...history, [key]: projected }, null, 1);
	const heading =
		entries.length === 0
			? "staged with no entries"
			: "bodies remain at their `bodyAt` in the unchanged file; read them when earlier wording matters";
	return text.length > COMPOSITION_BLOCK_LIMIT
		? pointer
		: `### ${label} — ${heading}\n\`\`\`json\n${text}\n\`\`\`\n`;
}

// A planned public decision proves neither delivery nor prior private advice.
function plannedReviewFacts(composedFeedback: ComposedFeedback, FEEDBACK_PATH: string): string {
	const { review } = composedFeedback;
	if (!review) {
		return "";
	}
	const stored = existsSync(FEEDBACK_PATH);
	const bodyAt = (field: string) =>
		stored ? { bodyAt: `${FEEDBACK_PATH} → review.${field}` } : {};
	const facts = {
		summary: review.summary ? { basedOn: review.summary.basedOn, ...bodyAt("summary.body") } : null,
		inline: review.inline.map(({ body: _body, ...note }, index) => ({
			...note,
			...bodyAt(`inline[${index}].body`),
		})),
		withheld: review.withheld,
	};
	return `### The review composed for this work\nThis decision is not a receipt of provider delivery. It may have entered public handling, whose final gates can still withhold it. Only delivered-history records establish prior communication. Private destinations remain independently useful and may make the same supported point. ${stored ? "Read the located bodies if their wording matters." : "Its wording is not available as a staged file here."}\n\`\`\`json\n${JSON.stringify(facts, null, 1)}\n\`\`\`\n`;
}

export function buildCompositionTurn(
	request: CompositionRequest,
	admission: Admission,
	composedFeedback: ComposedFeedback,
	routedDestinations: ReadonlyMap<string, readonly PrivateDestination[]> | null,
	files: CompositionFiles,
	notReached: readonly string[] = [],
): string {
	const {
		requestPath: COMPOSITION_REQUEST_PATH,
		feedbackPath: FEEDBACK_PATH,
		preparedPath: PREPARED_FEEDBACK_PATH,
		supportPath: PRACTICE_PAGE_SUPPORT_PATH,
		criteriaPathOf,
	} = files;
	const { results } = admission;
	const openDestinations = PRIVATE_DESTINATIONS.filter(
		(destination) => request.destinations[destination] !== undefined,
	)
		.map(
			(destination) =>
				`${destination} (at most ${request.destinations[destination]?.maxPieces ?? 0})`,
		)
		.join(", ");
	const closed = PRIVATE_DESTINATIONS.filter(
		(destination) => request.destinations[destination] === undefined,
	);
	const closedNote =
		closed.length > 0 ? ` Closed this turn, so write nothing for them: ${closed.join(", ")}.` : "";
	const coverageNote = notReachedNote(notReached);
	const { decidedPractices } = admission.privateProgress;
	const decidedNote =
		decidedPractices.length > 0
			? ` Private feedback that an earlier attempt of this review prepared already decides ${decidedPractices.join(", ")}; it stands, and the limits above are the room it left.`
			: "";
	const routing = PRIVATE_DESTINATIONS.filter(
		(destination) => request.destinations[destination] !== undefined,
	)
		.map(
			(destination) =>
				`${destination}: ${privatelyComposable(admission, routedDestinations)
					.filter((slug) => routedDestinations?.get(slug)?.includes(destination) === true)
					.join(", ")}`,
		)
		.join("; ");
	const admitted = JSON.stringify({ results: results.map(composerView) }, null, 1);
	const onTheWork = plannedReviewFacts(composedFeedback, FEEDBACK_PATH);
	const problemHistory = "What earlier reviews recorded about this person";
	const problemHistoryPath = files.problemsPath;
	const context = [
		stagedBlock(
			"The composition request (destinations, limits, copy forms)",
			COMPOSITION_REQUEST_PATH,
		),
		existsSync(problemHistoryPath)
			? stagedBlock(problemHistory, problemHistoryPath)
			: unstagedHistory(problemHistory, problemHistoryPath),
		request.destinations.PRACTICE_PAGE === undefined
			? ""
			: stagedBlock(
					"PRACTICE_PAGE support: occurrences a new card may cite at this read",
					PRACTICE_PAGE_SUPPORT_PATH,
				),
		priorFeedbackFacts("Recorded delivered feedback", files.deliveredPath, "feedback"),
		priorFeedbackFacts(
			"Prepared feedback (not delivered; supersession targets)",
			PREPARED_FEEDBACK_PATH,
			"prepared",
		),
	]
		.filter((block) => block !== "")
		.join("\n");
	return `## This turn
The review just finished. The criteria its NOT_MET practices were assessed against come first, as staged; its ${results.length} admitted result(s) follow them, one per practice. The MET and NOT_MET ones carry their rationale and their citations by coordinates; each NOT_MET one also carries its problems, the located problems it found, each with its own citations. The abstentions carry the limit they recorded, an open question or why the practice did not apply, and their citations by coordinates. The full record, quoted lines included, is \`work/composition/results.json\`. The history follows them.

${notMetCriteria(results, criteriaPathOf)}
\`\`\`json
${admitted}
\`\`\`

${context}
${onTheWork}
Destinations open this turn: ${openDestinations}.${closedNote}${decidedNote}
The practices each open destination may carry: ${routing}. Write no piece, not even a WITHHOLD, for a practice on a destination that does not list it.
A pattern claim needs at least ${request.minDistinctItems} distinct pieces of work.

${coverageNote}Persist the feedback with report_feedback — every piece you have in one call. Writing nothing on a destination is a correct and common outcome; say in one line why, and stop.`;
}

// Private composition requires a complete server routing read; missing routing closes the destination. What earlier
// attempts of this review prepared is this attempt's starting point: their practices are decided, so no destination
// carries them again, and their delivering pieces keep the room they used. Pieces this attempt adds do not move it.
export function closeUnroutedPrivateDestinations(
	request: CompositionRequest,
	admission: Admission,
	feedback: ComposedFeedback,
): ReadonlyMap<string, readonly PrivateDestination[]> {
	const notMet = notMetPractices(admission.results);
	const decided = new Set(admission.privateProgress.decidedPractices);
	const undecided = notMet.filter((slug) => !decided.has(slug));
	for (const destination of PRIVATE_DESTINATIONS) {
		const bounds = request.destinations[destination];
		if (bounds === undefined || undecided.length === 0) {
			continue;
		}
		bounds.maxPieces = Math.max(
			0,
			bounds.maxPieces - admission.privateProgress.deliveries[destination],
		);
		const read =
			destination === "PRACTICE_PAGE"
				? admission.practicePageSupport
				: admission.conversationEligibility;
		if (read?.state !== "COMPLETE") {
			if (destination === "PRACTICE_PAGE") {
				delete request.destinations.PRACTICE_PAGE;
			} else {
				delete request.destinations.CONVERSATION;
			}
			console.error(
				`[pi-runner] ${destination} closed for this run: ${destination === "PRACTICE_PAGE" ? "support context" : "routing"} ${read === null ? "missing or malformed" : read.state}`,
			);
			if (read?.state !== "REFUSED") {
				recordCompositionFailure(feedback, "PRIVATE_FEEDBACK", "RUNTIME_ERROR");
			}
		} else if (!undecided.some((slug) => routedTo(admission, destination, slug))) {
			if (destination === "PRACTICE_PAGE") {
				delete request.destinations.PRACTICE_PAGE;
			} else {
				delete request.destinations.CONVERSATION;
			}
			console.error(
				`[pi-runner] ${destination} closed for this run: no undecided practice is routed to it`,
			);
		}
	}
	return new Map(
		notMet.map((slug) => [
			slug,
			decided.has(slug)
				? []
				: PRIVATE_DESTINATIONS.filter(
						(destination) =>
							request.destinations[destination] !== undefined &&
							routedTo(admission, destination, slug),
					),
		]),
	);
}

export function privatelyComposable(
	admission: Admission,
	routedDestinations: ReadonlyMap<string, readonly PrivateDestination[]> | null,
): string[] {
	const notMet = notMetPractices(admission.results);
	const routed = routedDestinations;
	return routed === null ? notMet : notMet.filter((slug) => (routed.get(slug) ?? []).length > 0);
}

export function undecidedPrivatePractices(
	admission: Admission,
	composedFeedback: ComposedFeedback,
	routes: ReadonlyMap<string, readonly PrivateDestination[]> | null,
): string[] {
	const practiceOf = new Map(
		admission.results.map((result) => [result.resultId, result.practiceSlug]),
	);
	const decided = new Set(
		composedFeedback.feedback.flatMap((piece) => [
			piece.practiceSlug,
			...piece.basedOn.flatMap((ref) => practiceOf.get(ref.resultId) ?? []),
		]),
	);
	return privatelyComposable(admission, routes).filter((slug) => !decided.has(slug));
}

export function recordCompositionFailure(
	composedFeedback: ComposedFeedback,
	phase: CompositionPhase,
	reason: CompositionFailureReason,
): void {
	if (!composedFeedback.compositionFailures.some((failure) => failure.phase === phase)) {
		composedFeedback.compositionFailures.push({ phase, reason });
	}
}

const OWED_REASONS: ReadonlySet<CompositionFailureReason> = new Set(["SAFETY", "STALL"]);

/**
 * Whether the runner requests recovery for composition that the run, not the work, stopped. Only the admitting
 * attempt requests another public pass. A private stop requests recovery after a review was composed or accepted
 * earlier; the server decides from its recorded preparation whether another attempt is owed.
 */
export function compositionOwed(
	composedFeedback: ComposedFeedback,
	attempt: { admitting: boolean; reviewOnWork: boolean },
): boolean {
	const reasonOf = (phase: CompositionPhase) =>
		composedFeedback.compositionFailures.find((failure) => failure.phase === phase)?.reason;
	const publicReason = reasonOf("PUBLIC_REVIEW");
	if (publicReason !== undefined) {
		return attempt.admitting && OWED_REASONS.has(publicReason);
	}
	const privateReason = reasonOf("PRIVATE_FEEDBACK");
	return attempt.reviewOnWork && privateReason !== undefined && OWED_REASONS.has(privateReason);
}

export function finishCompositionText(
	notMet: readonly string[],
	request: CompositionRequest,
	composedFeedback: ComposedFeedback,
): string {
	const room = PRIVATE_DESTINATIONS.filter(
		(destination) => request.destinations[destination] !== undefined,
	)
		.map((destination) => {
			const maxPieces = request.destinations[destination]?.maxPieces ?? 0;
			const used = composedFeedback.feedback.filter(
				(piece) => piece.destination === destination && piece.action !== "WITHHOLD",
			).length;
			return `${destination} ${Math.max(0, maxPieces - used)} of ${maxPieces}`;
		})
		.join(", ");
	return (
		`## Undecided\nThe turn ended with no piece and no WITHHOLD for these practices, each with a ` +
		`NOT_MET result: ${notMet.join(", ")}. For each, persist the piece you decided on, or a ` +
		`WITHHOLD with its reason (NO_MATERIAL_CHANGE, ALREADY_SAID, BELOW_BAR), in one report_feedback ` +
		`call. Room left for new items: ${room}; a destination with none left takes only a WITHHOLD. The ` +
		`admitted results and their problems are in \`work/composition/results.json\`. Use tools only from this ` +
		`point onward; no prose.`
	);
}

export interface CompositionTrace {
	modelError: boolean;
	runtimeError: boolean;
	stoppedBy: "budget" | "safety" | "stall" | "loop" | null;
}
interface CompositionDeadline {
	expired: () => boolean;
	elapsed: Promise<undefined>;
}
type SessionOptions = NonNullable<Parameters<typeof createAgentSession>[0]>;
interface CompositionSession {
	session: AgentSession;
	phase: SessionPhase;
	unsubscribe: () => void;
}
interface CompositionRuntime<TTrace extends CompositionTrace> extends Pick<
	FeedbackToolRuntime,
	"paid" | "documentedShape" | "listSchema" | "submittedList" | "refusal" | "onStored" | "persist"
> {
	openSession: (
		phase: SessionPhase,
		tools: string[],
		customTools: SessionOptions["customTools"],
		resourceLoader?: SessionOptions["resourceLoader"],
	) => Promise<CompositionSession>;
	closeSession: (opened: CompositionSession) => Promise<void>;
	openTurnTrace: (
		phase: SessionPhase,
		label: string,
		budget: Work,
		demand: { owed: () => number; nudge: string; endsWhenPaid?: true },
	) => TTrace;
	closeTurnTrace: (trace: TTrace) => void;
	settleSession: (session: AgentSession, label: string) => Promise<boolean>;
	opening: () => string;
	hasOpening: () => boolean;
	openedContext: () => void;
	readPublicFeedbackHistory: () => Promise<{
		readAt: string;
		publicationContext: string | null;
		history: { feedback: unknown[] };
	}>;
	preparePublic: (expired: boolean) => Promise<FeedbackPreparation | null>;
	recordHistory: (history: {
		readAt: string;
		workCapturedAt: string | null;
		entries: {
			id: unknown;
			deliveredAt: unknown;
			eligibleForAlreadySaid: boolean;
			eligibleForPriorAdvice: boolean;
		}[];
		omissions: { oversizedEntries: number; budgetEntries: number };
	}) => void;
	compositionNudge: string;
	reviewNudge: string;
}
interface CompositionOptions {
	admission: Admission;
	feedback: ComposedFeedback;
	routes: ReadonlyMap<string, readonly PrivateDestination[]> | null;
	files: CompositionFiles;
	cwd: string;
	agentDir: string;
	paths: ReturnType<typeof taskPaths>;
	folderIndex: unknown;
	task: { repositoryFullName: unknown; pullRequestNumber: unknown };
	practiceIndex: z.infer<typeof practiceIndexSchema>;
	settingsManager: SettingsManager;
	stylePath: string;
	reviewPromptPath: string;
	privatePromptPath: string;
	practiceTools: string[];
	thisWork: string | undefined;
	budget: (practices: number) => Work;
}

// Supersession is limited to unread thread keys present in this snapshot.
function stagedPreparedTargets(PREPARED_FEEDBACK_PATH: string): PreparedFeedbackTarget[] {
	try {
		if (!existsSync(PREPARED_FEEDBACK_PATH)) {
			return [];
		}
		const prepared: unknown = JSON.parse(readFileSync(PREPARED_FEEDBACK_PATH, "utf8"));
		const entries = isRecord(prepared) ? jsonArray(prepared.prepared) : [];
		return entries.flatMap((entry) => {
			if (!isRecord(entry)) {
				return [];
			}
			const { threadKey, feedbackId, destination, practiceSlug } = entry;
			return typeof threadKey === "string" &&
				threadKey.trim() &&
				z.uuid().safeParse(feedbackId).success &&
				typeof feedbackId === "string" &&
				isDestination(destination) &&
				typeof practiceSlug === "string" &&
				practiceSlug.trim()
				? [{ threadKey, feedbackId, destination, practiceSlug }]
				: [];
		});
	} catch (error) {
		console.error(`[pi-runner] prepared feedback unreadable for composition: ${errorText(error)}`);
		return [];
	}
}

/**
 * Each delivered piece, by `destination:practice:feedbackId`, with what it may stand for, as on the work: words said
 * by the history read are communication whether their claim is current or stale; only a current claim delivered
 * before this work was captured is advice it could have answered. Prepared or unreadable feedback is neither.
 */
function stagedDeliveredWitnesses(
	file: string,
	capturedAt: string | null,
): Map<string, PriorAdviceWitness> {
	const witnesses = new Map<string, PriorAdviceWitness>();
	try {
		if (!existsSync(file)) {
			return witnesses;
		}
		const history: unknown = JSON.parse(readFileSync(file, "utf8"));
		if (
			!isRecord(history) ||
			history.recordRole !== "RECORDED_DELIVERY" ||
			!Array.isArray(history.feedback)
		) {
			return witnesses;
		}
		const readAt = typeof history.readAt === "string" ? history.readAt : null;
		for (const entry of history.feedback) {
			if (!isRecord(entry)) {
				continue;
			}
			const { destination, recordedClaimCurrentness, withdrawn, body, deliveredAt } = entry;
			const id = trimmedText(entry.id);
			const recorded =
				(destination === "PRACTICE_PAGE" || destination === "CONVERSATION") &&
				id !== "" &&
				(recordedClaimCurrentness === "CURRENT" || recordedClaimCurrentness === "STALE") &&
				withdrawn === false &&
				typeof body === "string" &&
				body.trim() !== "";
			if (!recorded) {
				continue;
			}
			const witness = {
				eligibleForPriorAdvice:
					recordedClaimCurrentness === "CURRENT" && deliveredBy(deliveredAt, capturedAt),
				eligibleForAlreadySaid: deliveredBy(deliveredAt, readAt),
			};
			for (const support of jsonArray(entry.basedOn)) {
				if (isRecord(support) && support.outcome === "NOT_MET") {
					witnesses.set(`${destination}:${trimmedText(support.practiceSlug)}:${id}`, witness);
				}
			}
		}
		return witnesses;
	} catch (error) {
		console.error(`[pi-runner] delivered feedback unreadable for composition: ${errorText(error)}`);
		return new Map();
	}
}

function reviewableById(
	reviewable: readonly Record<string, unknown>[],
): Map<string, ReviewedResult> {
	return new Map(
		reviewable.map((result) => [
			String(result.resultId),
			{
				practiceSlug: String(result.practiceSlug),
				summaryOnly: result.summaryOnly === true,
				outcome: result.outcome,
				problems: jsonArray(result.problems)
					.filter(isRecord)
					.map((problem) => ({
						problemId: String(problem.problemId),
						citations: jsonArray(problem.citations).filter(isRecord),
					})),
			},
		]),
	);
}

function undecidedByReview(
	composedFeedback: ComposedFeedback,
	reviewable: readonly Record<string, unknown>[],
): string[] {
	const decided = decidedByReview(composedFeedback.review);
	return reviewable
		.filter((result) => result.outcome === "NOT_MET")
		.map((result) => String(result.resultId))
		.filter((id) => !decided.has(id));
}

function practiceContext(
	practiceIndex: CompositionOptions["practiceIndex"],
	results: readonly Record<string, unknown>[],
): ReviewPractice[] {
	const slugs = new Set(results.map((result) => String(result.practiceSlug)));
	return practiceIndex
		.filter((practice) => slugs.has(practice.slug))
		.map((practice) => ({
			slug: practice.slug,
			name: practice.name,
			...(practice.whyItMatters === undefined ? {} : { whyItMatters: practice.whyItMatters }),
			knownLimitations: practice.knownLimitations,
			...(practice.versionId === undefined ? {} : { versionId: practice.versionId }),
		}));
}

// The composer references admitted results and problems; it cannot author verdicts, citations, or locations.
function finishReviewText(undecided: readonly string[]): string {
	const owed =
		undecided.length > 0
			? `## Undecided\nThe review leaves these NOT_MET results undecided: ${undecided.join(", ")}.`
			: "## Unfinished\nThe review on this work is not final yet.";
	return `${owed}\nStore the final review with one report_review call. A review that says nothing is still one final report_review call. No prose outside the calls.`;
}

export function incompleteCompositionReason(
	trace: CompositionTrace,
	safetyExpired: boolean,
): CompositionFailureReason {
	const stops = { budget: "BUDGET", stall: "STALL", safety: "SAFETY", loop: "LOOP" } as const;
	if (trace.stoppedBy !== null) {
		return stops[trace.stoppedBy];
	}
	if (safetyExpired) {
		return "SAFETY";
	}
	if (trace.modelError) {
		return "MODEL_ERROR";
	}
	if (trace.runtimeError) {
		return "RUNTIME_ERROR";
	}
	return "NO_DECISION";
}

export function createCompositionStage<TTrace extends CompositionTrace>(
	options: CompositionOptions & { runtime: CompositionRuntime<TTrace> },
) {
	const {
		admission,
		feedback: composedFeedback,
		routes: routedDestinations,
		files,
		cwd: CWD,
		agentDir: AGENT_DIR,
		paths,
		folderIndex,
		task,
		practiceIndex,
		settingsManager,
		stylePath: FEEDBACK_STYLE_PATH,
		reviewPromptPath: REVIEW_COMPOSER_PROMPT_PATH,
		privatePromptPath: COMPOSER_PROMPT_PATH,
		practiceTools: PRACTICE_TOOLS,
		thisWork: THIS_WORK,
		budget: compositionBudget,
		runtime,
	} = options;
	const {
		openSession,
		closeSession,
		openTurnTrace,
		closeTurnTrace,
		settleSession,
		opening,
		hasOpening,
		openedContext,
		readPublicFeedbackHistory,
		preparePublic,
		recordHistory,
		onStored,
		paid,
		documentedShape,
		listSchema,
		submittedList,
		refusal,
		persist: persistFeedback,
		compositionNudge: COMPOSITION_NUDGE,
		reviewNudge: REVIEW_NUDGE,
	} = runtime;

	async function askComposerOnceMore(
		session: AgentSession,
		phase: SessionPhase,
		notMet: readonly string[],
		undecided: () => readonly string[],
		request: CompositionRequest,
		buildText: () => string,
		safety: CompositionDeadline,
	): Promise<TTrace> {
		console.error(
			`[pi-runner] composition left ${notMet.length} NOT_MET practice(s) undecided — asking once more`,
		);
		const trace = openTurnTrace(phase, "composition once more", compositionBudget(notMet.length), {
			owed: () => undecided().length,
			nudge: COMPOSITION_NUDGE,
			endsWhenPaid: true,
		});
		try {
			if (!(await settleSession(session, "composition"))) {
				throw new Error("the session was still busy when the composition was asked once more");
			}
			if (!safety.expired()) {
				await Promise.race([
					(async () => {
						const prepared = await prepareTurnText(
							session,
							() => `${buildText()}

${finishCompositionText(notMet, request, composedFeedback)}`,
						);
						if (safety.expired()) {
							return;
						}
						if (prepared === null) {
							throw new Error("essential composition input exceeds the context window");
						}
						await session.prompt(prepared, {
							preflightResult: (disposition) => {
								if (disposition === "started") {
									openedContext();
								}
							},
						});
					})(),
					safety.elapsed,
				]);
			}
		} catch (error) {
			trace.runtimeError = trace.stoppedBy === null && !safety.expired();
			console.error(`[pi-runner] composition failed: ${errorText(error)}`);
		} finally {
			if (trace.stoppedBy !== null) {
				await settleSession(session, "composition");
			}
			closeTurnTrace(trace);
		}
		return trace;
	}

	// Public composition receives admitted public results in a fresh session without private history.
	async function composeReview(
		request: CompositionRequest,
		reviewable: readonly Record<string, unknown>[],
		notReachedSlugs: readonly string[],
		safety: CompositionDeadline,
	): Promise<PublicReviewOutcome> {
		const lineNotes = request.onWorkCopyForms.includes("LINE_NOTE");
		const reviewLoader = new DefaultResourceLoader({
			cwd: CWD,
			agentDir: AGENT_DIR,
			settingsManager,
			...PUBLIC_REVIEW_RESOURCE_LOADER_OPTIONS,
			systemPrompt: `${readFileSync(FEEDBACK_STYLE_PATH, "utf8")}\n\n${readFileSync(REVIEW_COMPOSER_PROMPT_PATH, "utf8")}`,
			agentsFilesOverride: () => ({ agentsFiles: [] }),
			extensionFactories: [],
		});
		await reviewLoader.reload();
		// Read now, not at capture: feedback delivered while this review ran was said here too. A failed read writes no
		// review rather than one that could repeat what was just delivered.
		let read: Awaited<ReturnType<typeof readPublicFeedbackHistory>>;
		try {
			read = await readPublicFeedbackHistory();
		} catch (error) {
			console.error(`[pi-runner] The review on the work was not composed: ${errorText(error)}`);
			recordCompositionFailure(
				composedFeedback,
				"PUBLIC_REVIEW",
				safety.expired() ? "SAFETY" : "RUNTIME_ERROR",
			);
			return "DONE";
		}
		const framing = task;
		const captured = buildPublicReviewHistory(CWD, paths.contextRoot, folderIndex, framing);
		const alreadySaid = priorPublicFeedback(
			read.history,
			THIS_WORK,
			captured.capturedAt,
			undefined,
			read.readAt,
		);
		recordHistory({
			readAt: read.readAt,
			workCapturedAt: captured.capturedAt,
			entries: alreadySaid.feedback.map(
				({ id, deliveredAt, eligibleForAlreadySaid, eligibleForPriorAdvice }) => ({
					id,
					deliveredAt,
					eligibleForAlreadySaid,
					eligibleForPriorAdvice,
				}),
			),
			omissions: alreadySaid.omissions,
		});
		const restable = reviewableById(reviewable);
		const state: PublicReviewState = { final: false, reviewContext: "LOST" };
		const practices = practiceContext(practiceIndex, reviewable);
		const restore = () => {
			state.reviewContext = "RESTORING";
			return text;
		};
		const phase: SessionPhase = "public-review";
		const opened = await openSession(
			phase,
			[...PUBLIC_REVIEW_TOOLS],
			[
				buildReviewTool(
					lineNotes,
					restable,
					priorAdviceWitnesses(alreadySaid.feedback, captured.statements),
					state,
					restore,
					{
						admitted: () => true,
						feedback: composedFeedback,
						persist: persistFeedback,
						refusal,
						onStored,
						publicationContext: read.publicationContext,
						preparePublic: async () => preparePublic(safety.expired()),
					},
				),
			],
			reviewLoader,
		);
		const reviewSession = opened.session;
		// Pi requires every tool to terminate a mixed batch, then AgentSession drains queued nudges.
		// Preserve its boundary; a persisted final review also ends this isolated session's internal queue.
		const { finishTurn } = reviewSession.agent;
		reviewSession.agent.finishTurn = async (turn, signal) => {
			const decision = await finishTurn?.(turn, signal);
			if (state.final) {
				reviewSession.clearQueue();
				return { action: "end" };
			}
			return decision ?? undefined;
		};
		const unsubscribeContext = reviewSession.subscribe((event) => {
			if (event.type === "compaction_end" && !event.aborted && !hasText(event.errorMessage)) {
				state.reviewContext = "LOST";
			} else if (event.type === "turn_start" && state.reviewContext === "RESTORING") {
				state.reviewContext = "HELD";
			}
		});
		// Admission's own rows: the projection the review rests on drops the digests a source is checked against.
		const repository = nodePath.resolve(CWD, paths.repositoryRoot);
		const primarySource = buildPrimarySourceReference(
			CWD,
			paths.contextRoot,
			paths.repositoryRoot,
			folderIndex,
			admission.results,
			{
				blob: (commit, file, limit) => readPinnedBlob(repository, commit, file, limit),
				diff: (base, head, limit) => pinnedDiff(repository, base, head, limit),
				checkedOut: () => checkedOutCommit(repository),
			},
		);
		const text = buildReviewTurn({
			sameWork: [buildSameWorkContext(CWD, paths.contextRoot, folderIndex, framing), primarySource]
				.filter((part) => part !== "")
				.join("\n\n"),
			results: reviewable,
			qualifications: publicQualifications(admission.results),
			alreadySaid: alreadySaid.feedback,
			ownHistoryOmissions: alreadySaid.omissions,
			ownHistoryReadAt: read.readAt,
			captured,
			practices,
			notReached: notReachedSlugs,
			lineNotes,
			stagedCriteria: (slug) => {
				const file = files.criteriaPathOf(slug);
				return existsSync(file) ? readFileSync(file, "utf8") : null;
			},
		});
		const owed = () =>
			state.final ? 0 : Math.max(1, undecidedByReview(composedFeedback, reviewable).length);
		const budget = compositionBudget(owed());
		let started = false;
		const wasStarted = () => started;
		const trace = openTurnTrace(phase, "review composition", budget, {
			owed,
			nudge: REVIEW_NUDGE,
		});
		let finalTrace = trace;
		try {
			try {
				if (safety.expired()) {
					trace.stoppedBy = "safety";
					throw new Error("the run reached its safety ceiling before the review was due");
				}
				await Promise.race([
					(async () => {
						const prepared = await prepareTurnText(reviewSession, () => text);
						if (safety.expired()) {
							return;
						}
						if (prepared === null) {
							throw new Error("essential review input exceeds the context window");
						}
						await reviewSession.prompt(prepared, {
							preflightResult: (disposition) => {
								if (disposition === "started") {
									started = true;
									state.reviewContext = "HELD";
								}
							},
						});
					})(),
					safety.elapsed,
				]);
			} catch (error) {
				trace.runtimeError = trace.stoppedBy === null && !safety.expired();
				console.error(`[pi-runner] review composition failed: ${errorText(error)}`);
			} finally {
				if (trace.stoppedBy !== null) {
					await settleSession(reviewSession, "review composition");
				}
				closeTurnTrace(trace);
			}
			const left = undecidedByReview(composedFeedback, reviewable);
			if (
				wasStarted() &&
				!trace.modelError &&
				!trace.runtimeError &&
				(trace.stoppedBy === null || trace.stoppedBy === "loop") &&
				!state.final &&
				(await settleSession(reviewSession, "review composition")) &&
				!safety.expired()
			) {
				console.error(
					left.length > 0
						? `[pi-runner] the review left ${left.length} NOT_MET result(s) undecided — asking once more`
						: "[pi-runner] the review is not final — asking once more",
				);
				const retry = openTurnTrace(phase, "review composition once more", budget, {
					owed,
					nudge: REVIEW_NUDGE,
					endsWhenPaid: true,
				});
				finalTrace = retry;
				try {
					await Promise.race([
						(async () => {
							const prepared = await prepareTurnText(
								reviewSession,
								() =>
									`${state.reviewContext === "HELD" ? "" : `${text}\n\n`}${finishReviewText(left)}`,
							);
							if (safety.expired()) {
								return;
							}
							if (prepared === null) {
								throw new Error("essential review input exceeds the context window");
							}
							await reviewSession.prompt(prepared, {
								preflightResult: (disposition) => {
									if (disposition === "started") {
										state.reviewContext = "HELD";
									}
								},
							});
						})(),
						safety.elapsed,
					]);
				} catch (error) {
					retry.runtimeError = retry.stoppedBy === null && !safety.expired();
					console.error(`[pi-runner] review composition failed: ${errorText(error)}`);
				} finally {
					if (retry.stoppedBy !== null) {
						await settleSession(reviewSession, "review composition");
					}
					closeTurnTrace(retry);
				}
			}
		} finally {
			try {
				await closeSession(opened);
				if (!state.final) {
					recordCompositionFailure(
						composedFeedback,
						"PUBLIC_REVIEW",
						incompleteCompositionReason(finalTrace, safety.expired()),
					);
				}
				persistFeedback();
			} finally {
				unsubscribeContext();
			}
		}
		return state.refused === undefined ? "DONE" : { refused: state.refused };
	}

	// Private composition starts after measurement in a fresh session with server-routed support.
	async function composePrivately(
		request: CompositionRequest,
		safety: CompositionDeadline,
		notReachedSlugs: readonly string[],
	): Promise<void> {
		if (
			!PRIVATE_DESTINATIONS.some((destination) => request.destinations[destination] !== undefined)
		) {
			return;
		}
		const history = stagePrivateHistory(admission.privateHistory, files, captureTime(folderIndex));
		const feedbackTool = buildFeedbackTool(
			practiceIndex.map((practice) => practice.slug).toSorted(),
			request,
			admission.results,
			history.preparedTargets,
			history.deliveredWitnesses,
			{
				feedback: composedFeedback,
				admitted: () => true,
				routes: (slug) => routedDestinations?.get(slug),
				destinationRefusal: (destination, slug) => destinationRefusal(admission, destination, slug),
				negativeItems: (slug) => negativePiecesOfWork(admission, slug),
				documentedShape,
				listSchema,
				submittedList,
				refusal,
				persist: persistFeedback,
				paid,
				onStored,
			},
		);
		const privateLoader = new DefaultResourceLoader({
			cwd: CWD,
			agentDir: AGENT_DIR,
			settingsManager,
			...SANDBOX_RESOURCE_LOADER_OPTIONS,
			systemPrompt: `${readFileSync(FEEDBACK_STYLE_PATH, "utf8")}\n\n${readFileSync(COMPOSER_PROMPT_PATH, "utf8")}`,
			agentsFilesOverride: () => ({ agentsFiles: [] }),
			extensionFactories: [createCodemodeExtension({ mode: "on", models: false })],
		});
		await privateLoader.reload();
		{
			const opened = await openSession(
				"private-feedback",
				[...PRACTICE_TOOLS, "report_feedback"],
				[feedbackTool],
				privateLoader,
			);
			const { session } = opened;
			let finalTrace: TTrace;
			try {
				finalTrace = await composeInto(session, opened.phase);
			} finally {
				await closeSession(opened);
			}
			if (undecidedPrivatePractices(admission, composedFeedback, routedDestinations).length > 0) {
				recordCompositionFailure(
					composedFeedback,
					"PRIVATE_FEEDBACK",
					incompleteCompositionReason(finalTrace, safety.expired()),
				);
			}
		}

		async function composeInto(session: AgentSession, phase: SessionPhase): Promise<TTrace> {
			const undecided = () =>
				undecidedPrivatePractices(admission, composedFeedback, routedDestinations);
			let started = false;
			const wasStarted = () => started;
			const trace = openTurnTrace(
				phase,
				"composition",
				compositionBudget(privatelyComposable(admission, routedDestinations).length),
				{
					owed: () => undecided().length,
					nudge: COMPOSITION_NUDGE,
				},
			);
			let finalTrace = trace;
			try {
				if (safety.expired()) {
					trace.stoppedBy = "safety";
					throw new Error("the run reached its safety ceiling before composition was due");
				}
				const compositionTurn = buildCompositionTurn(
					request,
					admission,
					composedFeedback,
					routedDestinations,
					files,
					notReachedSlugs,
				);
				await Promise.race([
					(async () => {
						const compositionText = await prepareTurnText(
							session,
							() => `${opening()}${compositionTurn}`,
						);
						if (safety.expired()) {
							return;
						}
						if (compositionText === null) {
							throw new Error("essential composition input exceeds the context window");
						}
						await session.prompt(compositionText, {
							preflightResult: (disposition) => {
								if (disposition === "started") {
									openedContext();
									started = true;
								}
							},
						});
					})(),
					safety.elapsed,
				]);
			} catch (error) {
				trace.runtimeError = trace.stoppedBy === null && !safety.expired();
				console.error(`[pi-runner] composition failed: ${errorText(error)}`);
			} finally {
				if (trace.stoppedBy !== null) {
					await settleSession(session, "composition");
				}
				closeTurnTrace(trace);
			}
			// A composer that ended on its own, or was cut off by a loop guard, is asked once more for the
			// NOT_MET practices it left undecided; otherwise they have no composed next step.
			const left = undecided();
			if (
				wasStarted() &&
				!trace.modelError &&
				!trace.runtimeError &&
				(trace.stoppedBy === null || trace.stoppedBy === "loop") &&
				left.length > 0 &&
				!safety.expired()
			) {
				finalTrace = await askComposerOnceMore(
					session,
					phase,
					left,
					undecided,
					request,
					() =>
						hasOpening()
							? ""
							: `${opening()}${buildCompositionTurn(request, admission, composedFeedback, routedDestinations, files, notReachedSlugs)}`,
					safety,
				);
			}
			return finalTrace;
		}
	}
	return { composeReview, composePrivately };
}

function isDestination(value: unknown): value is Destination {
	return DESTINATIONS.some((destination) => destination === value);
}

/**
 * Composes the review on the work. A typed refusal stored nothing: only changed history opens a fresh session, which
 * reads it again, within MAX_PUBLIC_RECOMPOSITIONS. Any other refusal is recorded as the review's failure.
 */
async function composePublicReview(
	stage: Pick<ReturnType<typeof createCompositionStage>, "composeReview">,
	request: CompositionRequest,
	reviewable: readonly Record<string, unknown>[],
	notReached: readonly string[],
	safety: CompositionDeadline,
	feedback: ComposedFeedback,
): Promise<void> {
	for (let recomposed = 0; ; recomposed += 1) {
		const outcome = await stage.composeReview(request, reviewable, notReached, safety);
		if (outcome === "DONE") {
			return;
		}
		if (
			outcome.refused !== "PUBLIC_HISTORY_CHANGED" ||
			recomposed >= MAX_PUBLIC_RECOMPOSITIONS ||
			safety.expired()
		) {
			recordCompositionFailure(feedback, "PUBLIC_REVIEW", outcome.refused);
			return;
		}
	}
}

export async function composeAdmittedFeedback(options: {
	request: CompositionRequest;
	admission: Admission;
	feedback: ComposedFeedback;
	routes: ReadonlyMap<string, readonly PrivateDestination[]> | null;
	stage: ReturnType<typeof createCompositionStage>;
	notReached: readonly string[];
	remainingMs: number;
	onDeadline: () => void;
	prepare: (safety: ReturnType<typeof scheduleDeadline>, phase: CompositionPhase) => Promise<void>;
	persist: () => void;
	after: () => void;
}): Promise<void> {
	const {
		request,
		admission,
		feedback,
		routes,
		stage,
		notReached,
		remainingMs,
		onDeadline,
		prepare,
		persist,
		after,
	} = options;
	if (admission.results.length === 0) {
		return;
	}
	const markRemaining = (reason: CompositionFailureReason) => {
		if (
			!admission.publicReviewed &&
			request.destinations.ON_WORK !== undefined &&
			publicResults(admission.results).length > 0 &&
			feedback.review === null
		) {
			recordCompositionFailure(feedback, "PUBLIC_REVIEW", reason);
		}
		if (
			PRIVATE_DESTINATIONS.some((destination) => request.destinations[destination] !== undefined) &&
			undecidedPrivatePractices(admission, feedback, routes).length > 0
		) {
			recordCompositionFailure(feedback, "PRIVATE_FEEDBACK", reason);
		}
	};
	if (remainingMs <= 0) {
		markRemaining("SAFETY");
		console.error(
			"[pi-runner] The run reached its safety ceiling before composition — preserving admitted results",
		);
		return;
	}
	const safety = scheduleDeadline(remainingMs, onDeadline);
	try {
		const reviewable = publicResults(admission.results);
		if (
			!admission.publicReviewed &&
			request.destinations.ON_WORK !== undefined &&
			reviewable.length > 0
		) {
			await composePublicReview(stage, request, reviewable, notReached, safety, feedback);
		}
		await prepare(safety, "PUBLIC_REVIEW");
		try {
			if (privatelyComposable(admission, routes).length > 0 && !safety.expired()) {
				await stage.composePrivately(request, safety, notReached);
			}
			await prepare(safety, "PRIVATE_FEEDBACK");
		} catch (error) {
			recordCompositionFailure(
				feedback,
				"PRIVATE_FEEDBACK",
				safety.expired() ? "SAFETY" : "RUNTIME_ERROR",
			);
			console.error(`[pi-runner] private feedback failed: ${errorText(error)}`);
		}
	} catch (error) {
		markRemaining(safety.expired() ? "SAFETY" : "RUNTIME_ERROR");
		throw error;
	} finally {
		if (safety.expired()) {
			markRemaining("SAFETY");
		}
		clearTimeout(safety.timer);
		persist();
		after();
	}
}
