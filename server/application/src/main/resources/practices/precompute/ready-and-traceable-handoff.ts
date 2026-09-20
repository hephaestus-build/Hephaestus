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
			`Issue-mention syntax candidates: ${[...allRefs].join(", ")}${branchRefs.size > 0 ? ` (branch '${branch}' encodes ${[...branchRefs].join(", ")})` : ""}. Inspect each mention in context before treating it as the author's motivating issue: templates, examples and branch numbers may be unrelated. A genuine reference need not contain a closing keyword.`,
		);
	} else {
		directions.push(
			`Traceability fact: no issue reference (#N, 'Refs #N', closing keyword, or issue-number branch prefix) was found in the body, the ${commits.length} commit message(s), or branch '${branch}' — confirm in the body before concluding the handoff is untraceable.`,
		);
	}

	// --- Readiness: the checklist as written, counted, so a tick is never guessed at. ---
	const description = m.body ?? "";
	const ticked = (description.match(/^\s*[-*]\s*\[[xX]\]/gmu) ?? []).length;
	const unticked = (description.match(/^\s*[-*]\s*\[ \]/gmu) ?? []).length;
	const draftMarker = /\b(?:wip|do not merge|draft)\b/iu.test(m.title ?? "");
	if (ticked + unticked > 0) {
		directions.push(
			`Checklist fact: the description carries ${ticked} ticked and ${unticked} unticked checkbox line(s)${draftMarker ? "; the title carries a draft-style word" : ""}. Read the lines in description.md to tell a real Definition of Done from a template that was left in place.`,
		);
	} else if (draftMarker) {
		directions.push(
			"Readiness fact: the title carries a draft-style word; check the draft flag in metadata.json.",
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
