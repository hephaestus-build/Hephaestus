import { defineTool, type AgentToolResult } from "@earendil-works/pi-coding-agent";

import type { ProblemDrafts } from "./pi-problem-drafts.ts";
import { resultToolSchemas } from "./pi-result-schema.ts";
import {
	prepareProblemArguments,
	prepareResultArguments,
	validateResultEvidence,
	RESULT_EVIDENCE_EXAMPLES,
} from "./pi-tool-arguments.ts";
import { toolArgumentValidator, unwrapToolArguments } from "./pi-tool-validation.ts";
import type { Problem } from "./work/practice-scripts/lib/core/contract.ts";
import { isJsonObject as isRecord } from "./work/practice-scripts/lib/core/json.ts";

export const RESULT_TOOL_NAMES = [
	"upsert_problem",
	"remove_problem",
	"read_problem",
	"submit_result",
];

interface ResultToolOptions<T> {
	sourceKinds: readonly string[];
	contextRoot: string;
	drafts: () => ProblemDrafts;
	validateProblem: (raw: unknown) => Promise<Problem & { key: string }>;
	submit: (id: string, params: Record<string, unknown>) => Promise<AgentToolResult<T>>;
}

function draftReply(drafts: ProblemDrafts, action: string, key: string) {
	const reply = { state: "local-draft", action, key, problemCount: drafts.size };
	return {
		content: [{ type: "text" as const, text: JSON.stringify(reply) }],
		details: reply,
	};
}

/** Tools mutate only the current session's drafts; the host owns evidence and final admission. */
export function buildResultTools<T>(options: ResultToolOptions<T>) {
	const schemas = resultToolSchemas(options.sourceKinds);
	const validateUpsert = toolArgumentValidator(schemas.upsertProblem);
	const validateRemove = toolArgumentValidator(schemas.removeProblem);
	const validateResult = toolArgumentValidator(schemas.result);

	return [
		defineTool({
			name: "upsert_problem",
			exposure: "model-only",
			executionMode: "sequential",
			label: "Draft Problem",
			description:
				"Add or replace one local problem draft by stable key. Send its complete body; an invalid replacement leaves the previous draft unchanged. Same key and body changes nothing. This does not submit a result or end review. Use submit_result with ordered problemKeys to publish the complete candidate for server admission. Prefer a single inline submit_result for simple results.",
			parameters: schemas.upsertProblem,
			prepareArguments: (raw) => {
				options.drafts().requireOpen();
				return validateUpsert(prepareProblemArguments(unwrapToolArguments(raw)));
			},
			execute: async (_id, params) => {
				const drafts = options.drafts();
				const problem = await options.validateProblem(params);
				return draftReply(drafts, drafts.upsert(problem), problem.key);
			},
		}),
		defineTool({
			name: "remove_problem",
			exposure: "model-only",
			executionMode: "sequential",
			label: "Remove Draft Problem",
			description:
				"Remove a local problem draft by key before submitting the result. An unknown key changes nothing. This does not delete an admitted Problem or establish repair.",
			parameters: schemas.removeProblem,
			prepareArguments: (raw) => {
				options.drafts().requireOpen();
				return validateRemove(unwrapToolArguments(raw));
			},
			execute: async (_id, params) => {
				const drafts = options.drafts();
				const args = validateRemove(params);
				if (typeof args.key !== "string") {
					throw new Error("A problem key is required.");
				}
				const { key } = args;
				return draftReply(drafts, drafts.remove(key), key);
			},
		}),
		defineTool({
			name: "read_problem",
			exposure: "model-only",
			executionMode: "sequential",
			label: "Read Draft Problem",
			description:
				"Read one complete local problem draft by its key, for example after compaction before correcting it. The key belongs only to this practice session. This is not an admitted product Problem. A submitted result's drafts are closed.",
			parameters: schemas.removeProblem,
			prepareArguments: (raw) => {
				options.drafts().requireOpen();
				return validateRemove(unwrapToolArguments(raw));
			},
			execute: async (_id, params) => {
				const args = validateRemove(params);
				if (typeof args.key !== "string") {
					throw new Error("A problem key is required.");
				}
				const problem = options.drafts().read(args.key);
				const reply = { state: "local-draft", problem };
				return {
					content: [{ type: "text" as const, text: JSON.stringify(reply) }],
					details: reply,
				};
			},
		}),

		defineTool({
			name: "submit_result",
			exposure: "model-only",
			executionMode: "sequential",
			label: "Submit Result",
			description:
				"Choose one outcome and its evidence shape. MET: evidence:{} or evidence.search; " +
				"NOT_MET: problems plus optional evidence.search and evidence.openQuestions; " +
				"DOES_NOT_APPLY: evidence.inapplicability only; COULD_NOT_DETERMINE: evidence.openQuestions, with optional evidence.search. " +
				"Omit unused fields. Each open question requires openQuestion and wouldSettleIt. " +
				`Submit the one complete evidenced result of this session's practice: its outcome, the explanation, the ` +
				`citations it rests on, and for NOT_MET each problem with its severity, citations and basis. The host adds ` +
				`the coverage it measured from your reads and keeps the result for server admission after measuring. For a large NOT_MET, optionally draft individual problems with upsert_problem and select their ordered problemKeys instead of inline problems. Never supply both. A draft is not an admitted Problem. Cite the source you read; the runner resolves source kind, ` +
				`manifest identity and the captured revision. Citation examples: ` +
				`{"path":"src/Auth.java","startLine":10,"quote":"insecure();"}; ` +
				`{"sourcePath":"work/change/diff.patch","path":"src/Auth.java","side":"NEW","startLine":10,"quote":"+ insecure();"}; ` +
				`{"path":"${options.contextRoot}/metadata.json","startLine":1,"quote":"Add login"}. ` +
				`Use only text you read. Examples show the shape, not evidence for this review. ${
					RESULT_EVIDENCE_EXAMPLES
				}`,
			parameters: schemas.result,
			prepareArguments: (raw) => {
				const prepared = prepareResultArguments(raw);
				validateResultEvidence(prepared, options.sourceKinds);
				validateResult(prepared);
				return options.drafts().resolve(prepared);
			},
			execute: async (id, params) => {
				const drafts = options.drafts();
				if (!isRecord(params)) {
					throw new Error("Submit one complete result object.");
				}
				const result = await options.submit(id, params);
				if (result.terminate === true) {
					drafts.seal();
				}
				return result;
			},
		}),
	];
}
