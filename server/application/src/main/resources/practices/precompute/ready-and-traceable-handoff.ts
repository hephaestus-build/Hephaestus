// Precompute traceability hints; the model judges readiness from the captured handoff.
import { readCommits } from "../lib/change.ts";
import { branchIssueReferences, issueNumberReferences } from "../lib/references.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

export default async function readyAndTraceableHandoff(
	_repoPath: string,
	_d: Map<string, DiffFile>,
	m: PullRequestMetadata,
	_contextDir?: string,
	changeDir?: string,
) {
	const directions: string[] = [];

	// --- Traceability: does the handoff reference a motivating issue at all? ---
	const commits = await readCommits(changeDir);
	const body = `${m.body ?? ""}\n${commits.map((c) => c.message).join("\n")}`;
	const branch = m.source_branch;
	const bodyRefs = new Set(issueNumberReferences(body).map((n) => `#${n}`));
	const branchRefs = new Set(branchIssueReferences(branch).map((n) => `#${n}`));
	const allRefs = new Set<string>([...bodyRefs, ...branchRefs]);
	if (allRefs.size > 0) {
		directions.push(
			`Issue-mention syntax candidates: ${[...allRefs].join(", ")}${branchRefs.size ? ` (branch '${branch}' encodes ${[...branchRefs].join(", ")})` : ""}. Inspect each mention in context before treating it as the author's motivating issue: templates, examples and branch numbers may be unrelated. A genuine reference need not contain a closing keyword.`,
		);
	} else {
		directions.push(
			`Traceability fact: no issue reference (#N, 'Refs #N', closing keyword, or issue-number branch prefix) was found in the body, the ${commits.length} commit message(s), or branch '${branch}' — confirm in the body before concluding the handoff is untraceable.`,
		);
	}

	return {
		hints: [],
		metrics: {
			issueMentionSyntaxCandidateCount: allRefs.size,
			commitCount: commits.length,
		},
		directions,
	};
}
