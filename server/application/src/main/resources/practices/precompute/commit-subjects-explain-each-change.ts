// Precompute FACTS for commit-subjects-explain-each-change: one row per authored commit — its subject
// with the shape it has (bare, a repeat, cut off) and the files it touched — read from the change
// view. The criteria say what a shape means and the model judges each subject; a flag is a place to
// look, never a verdict.
import { readCommits } from "../lib/change.ts";
import { commitRows, describeCommitCount, subjectFacts } from "../lib/commit-subjects.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

export default async function commitSubjectsExplainEachChange(
	_repoPath: string,
	_diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
	contextDir: string | undefined,
	_changeDir: string | undefined,
	contextReference: string,
) {
	const facts = subjectFacts(await readCommits(contextDir));
	const authored = facts.filter((f) => !f.merge);
	const directions =
		authored.length === 0
			? ["No authored commit in the reviewed range: only merge commits, or no change view."]
			: [
					describeCommitCount(facts),
					"Judge every subject on what it tells a reader about the files it touched; a flag names a shape to check, not a lapse.",
				];
	return {
		hints: commitRows(facts, contextReference),
		metrics: {
			authoredCommits: authored.length,
			mergeCommits: facts.length - authored.length,
			bareSubjects: authored.filter((f) => f.bare).length,
			repeatedSubjects: authored.filter((f) => f.repeat).length,
			cutOffSubjects: authored.filter((f) => f.cutOff).length,
		},
		directions,
	};
}
