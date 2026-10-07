// Precompute traceability hints and checklist facts; the model judges readiness from the captured handoff.
import { readCommits } from "../lib/change.ts";
import { branchIssueReferences, issueNumberReferences } from "../lib/references.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

export default async function readyAndTraceableHandoff(
	_repoPath: string,
	_d: Map<string, DiffFile>,
	m: PullRequestMetadata,
	contextDir?: string,
) {
	const directions: string[] = [];

	// Traceability: does the handoff reference a motivating issue at all?
	const commits = await readCommits(contextDir);
	const body = `${m.body ?? ""}\n${commits.map((c) => c.message).join("\n")}`;
	const branch = m.source_branch;
	const bodyRefs = new Set(issueNumberReferences(body).map((n) => `#${n}`));
	const branchRefs = new Set(branchIssueReferences(branch).map((n) => `#${n}`));
	const allRefs = new Set<string>([...bodyRefs, ...branchRefs]);
	if (allRefs.size > 0) {
		directions.push(
			`Issue-mention syntax candidates: ${[...allRefs].join(", ")}${branchRefs.size > 0 ? ` (branch '${branch}' encodes ${[...branchRefs].join(", ")})` : ""}. Inspect each mention in context before treating it as the author's motivating issue: templates, examples and branch numbers may be unrelated. A genuine reference need not contain a closing keyword.`,
		);
	} else {
		directions.push(
			`Traceability fact: no issue reference (#N, 'Refs #N', closing keyword, or issue-number branch prefix) was found in the body, the ${commits.length} commit message(s), or branch '${branch}' — confirm in the body before concluding the handoff is untraceable.`,
		);
	}

	// Readiness: the checklist as written, counted, so a tick is never guessed at. The counts cannot say whose
	// lines they are; the model reads that from the description.
	const description = m.body ?? "";
	const ticked = (description.match(/^\s*[-*+]\s*\[[xX]\]/gmu) ?? []).length;
	const unticked = (description.match(/^\s*[-*+]\s*\[ \]/gmu) ?? []).length;
	if (ticked + unticked > 0) {
		directions.push(
			`Checklist fact: the description carries ${ticked} ticked and ${unticked} unticked checkbox line(s). These counts say neither whose lines they are nor what was done. Where work/change/description.authored.md exists, it separates the form's lines from the author's; otherwise compare description.md with the form. Judge whether the author adopted the checklist or wrote equivalent prose; a form's line left unticked as supplied is boilerplate, not unfinished work, and a tick is a claim, not a result.`,
		);
	}

	return {
		hints: [],
		metrics: {
			issueMentionSyntaxCandidateCount: allRefs.size,
			commitCount: commits.length,
			checklistTicked: ticked,
			checklistUnticked: unticked,
		},
		directions,
	};
}
