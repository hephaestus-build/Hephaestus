import type { ToolDefinition } from "@earendil-works/pi-coding-agent";

import {
	MAX_SUMMARY_CHARS,
	OUTCOME_DESCRIPTIONS,
	SEVERITY_DESCRIPTIONS,
	describeVocabulary,
} from "./pi-result-normalize.ts";
import {
	CANDIDATE_LIMITS,
	OUTCOMES,
	PRACTICE_SLUG,
	SEVERITIES,
} from "./work/practice-scripts/lib/core/contract.ts";

/** Model-facing shapes; the shared Candidate parser owns semantic validity. */
export function resultToolSchemas(availableSourceKindValues: readonly string[]) {
	const searchSchema = {
		type: "object",
		additionalProperties: false,
		required: ["consulted", "lookedFor", "boundary"],
		properties: {
			consulted: {
				type: "array",
				minItems: 1,
				items: { type: "string", enum: availableSourceKindValues },
				description:
					"Evidence source kinds you actually searched, e.g. scm.review-threads. Name scm.repository.tree only after a grep of the whole checkout that was not cut.",
			},
			lookedFor: {
				type: "string",
				minLength: 1,
				description:
					"The specific behavior whose absence you report, matching the behavior assessed in the summary and explanation.",
			},
			boundary: {
				type: "string",
				minLength: 1,
				description:
					"What this search did NOT cover, so a reader can judge how far the absence reaches.",
			},
		},
	} as const;
	const inapplicabilitySchema = {
		type: "object",
		additionalProperties: false,
		required: ["consulted", "prerequisite", "ruledOutBy"],
		properties: {
			consulted: {
				type: "array",
				minItems: 1,
				items: { type: "string", enum: availableSourceKindValues },
				description:
					"Evidence source kinds you read to reach this conclusion, e.g. scm.pull-request.diff.",
			},
			prerequisite: {
				type: "string",
				minLength: 1,
				description: "The subject this practice's criteria judge.",
			},
			ruledOutBy: {
				type: "string",
				minLength: 1,
				description: "The fact about THIS work that rules out that subject.",
			},
		},
	} as const;
	const citationSchema = {
		type: "object",
		additionalProperties: false,
		required: ["path", "startLine", "quote"],
		properties: {
			sourceKind: {
				type: ["string", "null"],
				enum: [...availableSourceKindValues, null],
				description: "Optional. The runner resolves the source kind from the path you read.",
			},
			// Validate source membership at submission; large enums can exceed the SDK schema compiler's capacity.
			sourcePath: {
				type: ["string", "null"],
				minLength: 1,
				maxLength: CANDIDATE_LIMITS.pathChars,
				description:
					"Optional. The source you read, such as work/change/diff.patch or a captured record. The runner resolves its manifest identity.",
			},
			path: {
				type: "string",
				minLength: 1,
				maxLength: CANDIDATE_LIMITS.pathChars,
				description:
					"The file you read: a repository-relative path, a sandbox path, work/change/diff.patch, or a captured record path.",
			},
			side: {
				type: ["string", "null"],
				enum: ["OLD", "NEW", null],
				description:
					"For a diff quote, OLD selects removed lines and NEW selects added lines. Omission lets the runner resolve the side from the exact quote.",
			},
			commit: {
				type: ["string", "null"],
				minLength: 1,
				maxLength: 1024,
				description:
					"Optional full commit SHA for historical repository text. Omission selects the captured HEAD automatically. Do not use a branch name.",
			},
			startLine: {
				type: "integer",
				minimum: 1,
				maximum: 2_147_483_647,
				description:
					"The 1-based line you read. For a diff, use its [L<n>] file coordinate or the physical line number of diff.patch.",
			},
			endLine: {
				type: ["integer", "null"],
				minimum: 1,
				maximum: 2_147_483_647,
				description: "The last line of the quote, at least startLine; omitted means one line.",
			},
			quote: {
				type: "string",
				minLength: 1,
				maxLength: CANDIDATE_LIMITS.quoteChars,
				description:
					"Required verbatim text at those lines. For repository text and records, a fragment is enough. For a diff, copy each whole line. Diff markers and [L<n>] prefixes are accepted.",
			},
		},
	} as const;
	const openQuestionSchema = {
		type: "object",
		additionalProperties: false,
		required: ["openQuestion", "wouldSettleIt"],
		properties: {
			openQuestion: {
				type: "string",
				minLength: 1,
				description: "The question the evidence you actually read left open, in one sentence.",
			},
			wouldSettleIt: {
				type: "string",
				minLength: 1,
				description:
					"The EVIDENCE that would have decided it — something that already exists and you could not " +
					"read, named concretely: 'the body of issue #7', 'the test file the description says covers " +
					"this'. NOT what the author should have written: advice belongs to a later step, and " +
					"answering with it leaves nobody any wiser about which source this practice is missing.",
			},
			citation: {
				anyOf: [citationSchema, { type: "null" }],
				description: "Where the question arises, when one place of the work does.",
			},
		},
	} as const;
	const problemSchema = {
		type: "object",
		additionalProperties: false,
		required: ["severity", "summary", "explanation", "citations", "basis"],
		properties: {
			key: {
				type: ["string", "null"],
				description:
					"Optional: a short lowercase-hyphenated name for this problem, unique within the result.",
			},
			severity: {
				type: "string",
				enum: SEVERITIES,
				description: `From the practice's Severity section. ${describeVocabulary(SEVERITIES, SEVERITY_DESCRIPTIONS)}`,
			},
			summary: {
				type: "string",
				minLength: 1,
				maxLength: MAX_SUMMARY_CHARS,
				description: `One line of at most ${MAX_SUMMARY_CHARS} characters naming this one problem, such as 'Debug print left in the request handler'.`,
			},
			explanation: {
				type: "string",
				minLength: 1,
				maxLength: CANDIDATE_LIMITS.problemExplanationChars,
				description:
					"What the cited evidence establishes about this one criterion gap, including any consequence that decides its applicability, outcome or severity. " +
					"Apply Grounding to the exact behavior and its language or API contract; leave an unresolved premise in evidence.openQuestions, and do not let it decide the problem. " +
					"Evidence, not advice, intent, confidence, or hidden chain-of-thought.",
			},
			citations: {
				type: "array",
				minItems: 1,
				maxItems: CANDIDATE_LIMITS.citationsPerProblem,
				items: citationSchema,
				description: "The lines that show this problem.",
			},
			basis: {
				description:
					"All indexes are zero-based positions in THIS problem's citations array: with one citation, use 0. Why the problem belongs to this work. introduced: the change adds it (head: the index of a citation of the NEW side or the reviewed head). worsened: the change makes it worse (base: the index of the OLD side's citation, head: the NEW side's). duty: the criteria require an event that this work lacks (criterionQuote: the criteria's exact words, event: the index of the citation that shows the event). state: the problem exists in the pinned file; its citations witness that state at the reviewed version.",
				anyOf: [
					{
						type: "object",
						additionalProperties: false,
						required: ["type", "head", "explanation"],
						properties: {
							type: { type: "string", enum: ["introduced"] },
							head: { type: "integer", minimum: 0 },
							explanation: { type: "string", minLength: 1 },
						},
					},
					{
						type: "object",
						additionalProperties: false,
						required: ["type", "base", "head", "explanation"],
						properties: {
							type: { type: "string", enum: ["worsened"] },
							base: { type: "integer", minimum: 0 },
							head: { type: "integer", minimum: 0 },
							explanation: { type: "string", minLength: 1 },
						},
					},
					{
						type: "object",
						additionalProperties: false,
						required: ["type", "event", "criterionQuote", "explanation"],
						properties: {
							type: { type: "string", enum: ["duty"] },
							event: { type: "integer", minimum: 0 },
							criterionQuote: { type: "string", minLength: 1 },
							explanation: { type: "string", minLength: 1 },
						},
					},
					{
						type: "object",
						additionalProperties: false,
						required: ["type", "explanation"],
						properties: {
							type: { type: "string", enum: ["state"] },
							explanation: { type: "string", minLength: 1 },
						},
					},
				],
			},
			nextStep: {
				type: ["string", "null"],
				description:
					"Optional, when the criteria name a change: the one change that resolves this supported criterion gap, relying on the same established mechanism as the explanation.",
			},
		},
	} as const;
	const resultSchema = {
		type: "object",
		additionalProperties: false,
		required: ["outcome", "summary", "explanation", "citations", "evidence"],
		properties: {
			outcome: {
				type: "string",
				enum: OUTCOMES,
				description: `${describeVocabulary(OUTCOMES, OUTCOME_DESCRIPTIONS)} Use these exact values. Case variants, NOT_APPLICABLE for DOES_NOT_APPLY, and UNDETERMINED for COULD_NOT_DETERMINE are accepted.`,
			},
			summary: {
				type: "string",
				minLength: 1,
				maxLength: MAX_SUMMARY_CHARS,
				description:
					`A short phrase of at most ${MAX_SUMMARY_CHARS} characters identifying the specific behavior whose conformance to the practice standard you assess, such as 'Debug print left in the request handler'. ` +
					"Never a single word and never the practice's own name; the reasons, titles and quotes go in explanation. " +
					"A longer summary is refused, not shortened.",
			},
			explanation: {
				type: "string",
				minLength: 1,
				maxLength: CANDIDATE_LIMITS.explanationChars,
				description:
					"A concise explanation a developer can read: the source facts that decide whether this work holds " +
					"this criterion's subject and applicability, then, when it does, how the cited evidence meets or falls " +
					"short of what this criterion judges, leaving to other practices what it assigns to them. Describe " +
					"evidence, not advice, intent, confidence, or hidden chain-of-thought.",
			},
			citations: {
				type: "array",
				maxItems: CANDIDATE_LIMITS.resultCitations,
				items: citationSchema,
				description:
					"The lines that decide the outcome. Required for MET, DOES_NOT_APPLY and COULD_NOT_DETERMINE; a NOT_MET result may leave it empty, since each problem cites its own lines.",
			},
			problems: {
				anyOf: [
					{ type: "array", minItems: 1, maxItems: CANDIDATE_LIMITS.problems, items: problemSchema },
					{ type: "null" },
				],
				description:
					"NOT_MET requires a nonempty array: each problem has severity, summary, explanation, citations and basis. For every other outcome, omit problems or use null or [].",
			},
			example: {
				anyOf: [
					{
						type: "object",
						additionalProperties: false,
						required: ["citation", "note"],
						properties: {
							citation: citationSchema,
							note: { type: "string", minLength: 1, maxLength: CANDIDATE_LIMITS.exampleNoteChars },
						},
					},
					{ type: "null" },
				],
				description:
					"Optional, MET or NOT_MET only: one place of this work that shows the practice as the criteria ask, when the evidence holds one; never on the lines of a problem. null otherwise.",
			},
			evidence: {
				type: "object",
				additionalProperties: false,
				description:
					"An object with only these outcome-specific fields. MET: optional search warrant; DOES_NOT_APPLY: required inapplicability; " +
					"COULD_NOT_DETERMINE: required openQuestions and optional search; NOT_MET: optional search and openQuestions. " +
					"Put consulted, lookedFor and boundary inside search, never directly inside evidence. Omit unused fields; empty values in unused fields are treated as omitted.",
				properties: {
					search: { anyOf: [searchSchema, { type: "null" }] },
					inapplicability: { anyOf: [inapplicabilitySchema, { type: "null" }] },
					openQuestions: {
						anyOf: [
							{
								type: "array",
								minItems: 1,
								maxItems: CANDIDATE_LIMITS.openQuestions,
								items: openQuestionSchema,
							},
							{ type: "null" },
						],
					},
				},
			},
		},
	} as const;

	const key = {
		type: "string",
		pattern: PRACTICE_SLUG.source,
		description: "Stable short lowercase-hyphenated name, unique within this practice result.",
	} as const;
	const result: ToolDefinition["parameters"] = {
		...resultSchema,
		properties: {
			...resultSchema.properties,
			problemKeys: {
				type: "array",
				minItems: 1,
				maxItems: CANDIDATE_LIMITS.problems,
				uniqueItems: true,
				items: key,
				description:
					"NOT_MET only: ordered keys of local problem drafts to include instead of inline problems. Never supply both. Unselected drafts are not submitted.",
			},
		},
	};
	const upsertProblem: ToolDefinition["parameters"] = {
		...problemSchema,
		required: [...problemSchema.required, "key"],
		properties: { ...problemSchema.properties, key },
	};
	const removeProblem: ToolDefinition["parameters"] = {
		type: "object",
		additionalProperties: false,
		required: ["key"],
		properties: { key },
	};
	return { result, upsertProblem, removeProblem };
}
