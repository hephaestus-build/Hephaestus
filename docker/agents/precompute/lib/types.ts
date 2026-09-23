/** Precomputation types — hints and directions, never verdicts */

export interface Hint {
	file: string;
	line: number;
	pattern: string;
	context: string;
	inDiff: boolean;
	flags: Record<string, HintFlag>;
}

/** A hint flag renders into summary.md verbatim, so it stays a JSON scalar. */
export type HintFlag = boolean | number | string;

/** Metadata varies by reviewed artifact; scripts validate the fields they use. */
export type ArtifactMetadata = Record<string, unknown>;

/**
 * What a precompute script returns. `practice` and `status` are NOT part of it: the runner stamps
 * those on, so the filename slug stays the single source of truth for a script's identity.
 */
export interface PracticeFindings {
	hints: Hint[];
	metrics: Record<string, number>;
	directions: string[];
}

/** A validated `PracticeFindings` attributed to a practice — the shape of `{output}/{slug}.json`. */
export interface PracticeResult extends PracticeFindings {
	practice: string;
	status: "ok" | "error" | "timeout";
}

/**
 * Injected scripts are untrusted; the runner validates their results with parseFindings.
 *
 * @param contextDir the context the server projected (`inputs/context`), read with `lib/context.ts`
 * @param changeDir the change view this container derived (`work/change`), read with `lib/change.ts`
 */
export type PracticeScript = (
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	metadata: ArtifactMetadata,
	contextDir?: string,
	changeDir?: string,
) => PracticeFindings | Promise<PracticeFindings>;

export interface DiffFile {
	path: string;
	addedLines: Map<number, string>;
	removedLines: Map<number, string>;
	hunks: DiffHunk[];
}

export interface DiffHunk {
	oldStart: number;
	oldCount: number;
	newStart: number;
	newCount: number;
	lines: string[];
}

/**
 * Pull request metadata — the `metadata.json` written by
 * PullRequestContentSource.buildPullRequestMetadata() on the server.
 * Scripts should import this instead of declaring ad-hoc types.
 */
export interface PullRequestMetadata {
	pr_number: number;
	pr_url: string;
	repository_full_name: string;
	source_branch: string;
	target_branch: string;
	commit_sha: string;
	title?: string;
	body?: string;
	state?: string;
	is_draft?: boolean;
	/** Stated apart from `state`: a pull request closed by its merge is stored CLOSED with `merged_at` set. */
	is_merged?: boolean;
	labels?: string[];
	assignees?: string[];
	milestone?: string;
	/** Filled by the GraphQL sync only. */
	merge_state_status?: string;
	review_decision?: string;
	/** What the checks or pipeline said about the reviewed head: SUCCESS, FAILURE, PENDING, CANCELLED or NONE. */
	head_checks?: string;
	/** The provider classified the author as automation. */
	author_bot?: boolean;
	additions?: number;
	deletions?: number;
	changed_files?: number;
	author?: string;
	merged_by?: string;
	created_at?: string;
	closed_at?: string;
	merged_at?: string;
}
