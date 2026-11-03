import type { z } from "zod";

import type { Citation } from "../../lib/core/contract.ts";
import { domainAccess } from "../../lib/core/domain-access.ts";
import { captureFailure, type ScriptContext } from "../../lib/core/practice-script.ts";
import { readCapturedCommits } from "./change.ts";
import { evidenceTools } from "./evidence-tools.ts";
import type { GrepMatch, GrepOptions } from "./grep.ts";
import {
	repositoryRecordSchema,
	fileRecordSchema,
	pullRequestRecordSchema,
	issueRecordSchema,
} from "./records.ts";
import {
	readGeneralComments,
	readLinkedWorkItemCapture,
	readPullRequestChecks,
	readReviewComments,
	readReviewThreads,
} from "./review.ts";
import {
	findSites,
	selectedSources,
	type FoundSites,
	type SiteRules,
	type SitesOptions,
	type SourceFile,
} from "./sites.ts";
import type { DiffFile } from "./types.ts";

export {
	COMMIT,
	isCommit,
	repositoryRecordSchema,
	fileRecordSchema,
	pullRequestRecordSchema,
	issueRecordSchema,
	type ScmFileRecord,
} from "./records.ts";
export type { DiffFile } from "./types.ts";
export interface FileTree {
	readonly commit: string;
	read: (
		path: string,
		options?: { offsetLine?: number; maxLines?: number },
	) => Promise<
		| { status: "FOUND"; text: string; truncated: boolean }
		| { status: "BINARY" }
		| { status: "ABSENT" }
	>;
	list: (glob?: string) => Promise<{ paths: readonly string[]; truncated: boolean }>;
	grep: (
		pattern: string,
		options?: GrepOptions,
	) => Promise<{ matches: readonly GrepMatch[]; truncated: boolean }>;
	cite: (path: string, startLine: number, endLine?: number) => Promise<Citation>;
}
export interface ChangeView {
	readonly base: string;
	readonly head: string;
	readonly files: ReadonlyMap<string, DiffFile>;
	cite: (
		path: string,
		side: "OLD" | "NEW",
		startLine: number,
		endLine?: number,
	) => Promise<Citation>;
}
export interface Files {
	head: FileTree;
	change: ChangeView | null;
}

type ScmKind = (typeof SCM_KINDS)[number];
const SCM_KINDS = [
	"scm.pull_request",
	"scm.pull_request_review",
	"scm.issue",
	"scm.file",
	"scm.repository",
] as const;

interface ScmRecords {
	"scm.pull_request": z.infer<typeof pullRequestRecordSchema>;
	"scm.pull_request_review": z.infer<typeof pullRequestRecordSchema>;
	"scm.issue": z.infer<typeof issueRecordSchema>;
	"scm.file": z.infer<typeof fileRecordSchema>;
	"scm.repository": z.infer<typeof repositoryRecordSchema>;
}

const RECORD_SCHEMAS: { [K in ScmKind]: z.ZodType<ScmRecords[K]> } = {
	"scm.pull_request": pullRequestRecordSchema,
	"scm.pull_request_review": pullRequestRecordSchema,
	"scm.issue": issueRecordSchema,
	"scm.file": fileRecordSchema,
	"scm.repository": repositoryRecordSchema,
};

/** The work a script reviews, with its record and what the capture holds of it. */
export type ScmWork<K extends ScmKind> = K extends ScmKind
	? {
			kind: K;
			item: ScmRecords[K];
			/** The file tree at the reviewed version; null when it was not captured. */
			head: FileTree | null;
			/** The captured change; null when it was not captured. */
			change: ChangeView | null;
			/** The changed files. It raises a capture failure when the change was not captured. */
			changedFiles: () => ReadonlyMap<string, DiffFile>;
			/** The changed files with added lines, or a scan's file, with their text. */
			sources: (
				select?: (file: { path: string; language: string | null }) => boolean,
			) => Promise<readonly SourceFile[]>;
			sites: (rules: SiteRules, options?: SitesOptions) => Promise<FoundSites>;
			commits: () => ReturnType<typeof readCapturedCommits>;
			linkedIssues: () => ReturnType<typeof readLinkedWorkItemCapture>;
			inlineComments: () => ReturnType<typeof readReviewComments>;
			conversationComments: () => ReturnType<typeof readGeneralComments>;
			reviewRecord: () => ReturnType<typeof readReviewThreads>;
			checks: () => ReturnType<typeof readPullRequestChecks>;
		}
	: never;

function recordOf<K extends ScmKind>(kind: K, item: unknown): ScmRecords[K] {
	const parsed = RECORD_SCHEMAS[kind].safeParse(item);
	if (!parsed.success) {
		const at = parsed.error.issues[0]?.path.join(".") ?? "";
		throw captureFailure("unavailable", `the record of this ${kind} is not valid at ${at}`);
	}
	return parsed.data;
}

/**
 * The SCM view of the review, for the kinds of work that the script reads. Another kind stops the
 * script with an error: a script states what it can review.
 */
export function scm<K extends ScmKind>(
	ctx: Pick<ScriptContext, "kind" | "item" | "scope" | "evidence">,
	...kinds: readonly [K, ...K[]]
): ScmWork<K> {
	const kind = kinds.find((candidate) => candidate === ctx.kind);
	if (kind === undefined) {
		throw new Error(`the script reads ${kinds.join(" or ")}, but the item is ${ctx.kind}`);
	}
	const { files, port } = domainAccess(ctx);
	const treeOf = (commit: string): FileTree => ({
		commit,
		read: async (path, options = {}) => port.read({ op: "read", path, ...options }),
		list: async (glob) => port.read({ op: "list", ...(glob === undefined ? {} : { glob }) }),
		grep: async (pattern, options = {}) => port.read({ op: "grep", pattern, ...options }),
		cite: async (path, startLine, endLine) =>
			port.read({
				op: "cite",
				source: "tree",
				path,
				startLine,
				...(endLine === undefined ? {} : { endLine }),
			}),
	});
	const tree: Files | null =
		files === null
			? null
			: {
					head: treeOf(files.head.commit),
					change:
						files.change === null
							? null
							: {
									...files.change,
									cite: async (path, side, startLine, endLine) =>
										port.read({
											op: "cite",
											source: "change",
											path,
											side,
											startLine,
											...(endLine === undefined ? {} : { endLine }),
										}),
								},
				};
	Object.assign(ctx.evidence, { tools: evidenceTools(tree?.head ?? null) });
	const { evidence } = ctx;
	// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- TypeScript does not narrow a conditional type over a generic kind; the kind and its record are built together here.
	return {
		kind,
		item: recordOf(kind, ctx.item),
		head: tree?.head ?? null,
		change: tree?.change ?? null,
		changedFiles: () => {
			if (tree?.change == null) {
				throw captureFailure("unavailable", "the change was not captured");
			}
			return tree.change.files;
		},
		sources: async (select = () => true) => selectedSources(ctx, tree, select),
		sites: async (rules, options) => findSites(ctx, tree, rules, options),
		commits: async () => readCapturedCommits(evidence),
		linkedIssues: async () => readLinkedWorkItemCapture(evidence),
		inlineComments: async () => readReviewComments(evidence),
		conversationComments: async () => readGeneralComments(evidence),
		reviewRecord: async () => readReviewThreads(evidence),
		checks: async () => readPullRequestChecks(evidence),
	} as ScmWork<K>;
}
