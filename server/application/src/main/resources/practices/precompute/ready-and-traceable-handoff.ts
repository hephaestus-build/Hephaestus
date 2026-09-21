// Precompute traceability hints; the model judges readiness from the captured handoff.
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

/** Bare `#N` mention (`number` group), rejecting `#1a2b` colours / `#1.2` versions / `#42px` units. */
const BARE_REF = /#(?<number>\d+)(?![\w.])/gu;
/** Issue id at the start of a branch-slug segment, e.g. `1313-foo` or `feat/1313-foo`. */
const BRANCH_REF = /(?:^|\/)(?<number>\d{1,7})-/gu;

export default function readyAndTraceableHandoff(
	_repoPath: string,
	_d: Map<string, DiffFile>,
	m: PullRequestMetadata,
) {
	const directions: string[] = [];

	// --- Traceability: does the handoff reference a motivating issue at all? ---
	const body = `${m.body ?? ""}\n${(m.commits ?? []).map((c) => c.message ?? "").join("\n")}`;
	const branch = m.source_branch;
	const bodyRefs = new Set<string>();
	for (const mt of body.matchAll(BARE_REF)) {
		bodyRefs.add(`#${mt.groups?.number}`);
	}
	const branchRefs = new Set<string>();
	for (const mt of branch.matchAll(BRANCH_REF)) {
		branchRefs.add(`#${mt.groups?.number}`);
	}
	const allRefs = new Set<string>([...bodyRefs, ...branchRefs]);
	if (allRefs.size > 0) {
		directions.push(
			`Issue-mention syntax candidates: ${[...allRefs].join(", ")}${branchRefs.size > 0 ? ` (branch '${branch}' encodes ${[...branchRefs].join(", ")})` : ""}. Inspect each mention in context before treating it as the author's motivating issue: templates, examples and branch numbers may be unrelated. A genuine reference need not contain a closing keyword.`,
		);
	} else {
		directions.push(
			`Traceability fact: no issue reference (#N, 'Refs #N', closing keyword, or issue-number branch prefix) was found in the body, commits, or branch '${branch}' — confirm in the body before concluding the handoff is untraceable.`,
		);
	}

	return {
		hints: [],
		metrics: {
			issueMentionSyntaxCandidateCount: allRefs.size,
		},
		directions,
	};
}
