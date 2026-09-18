// Precompute FACTS for commit-subjects-explain-each-change: every authored subject with the shape it
// has (bare, a repeat, cut off), read from the change view. The criteria say what a shape means and the
// model judges each subject; a flag is a place to look, never a verdict.
import { readCommits } from "../lib/change.ts";
import { describeSubjects, subjectFacts } from "../lib/commit-subjects.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

export default async function commitSubjectsExplainEachChange(
	_repoPath: string,
	_diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
	_contextDir?: string,
	changeDir?: string,
) {
	const facts = subjectFacts(await readCommits(changeDir));
	const authored = facts.filter((f) => !f.merge);
	const directions =
		authored.length === 0
			? ["No authored commit in the reviewed range: only merge commits, or no change view."]
			: [
					...describeSubjects(facts),
					"Judge every subject on what it tells a reader; a flag names a shape to check, not a lapse.",
				];
	return {
		hints: [],
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
