// Precompute FACTS for commit-subjects-explain-each-change: one row per authored commit — its subject
// with the shape it has (bare, a repeat, cut off) and the files it touched — read from the change
// view. The criteria say what a shape means and the model judges each subject; a flag is a place to
// look, never a verdict. The files only locate the commit: the criteria judge a subject's clarity,
// never its accuracy against what the commit touched.
import { readCapturedCommits } from "../lib/change.ts";
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
	const captured = await readCapturedCommits(contextDir);
	const facts = subjectFacts(captured ?? []);
	const authored = facts.filter((f) => !f.merge);
	let directions = [
		describeCommitCount(facts),
		"Judge every subject on whether it names what that step did for a reader of the history. The paths locate a commit; they never measure whether its subject is accurate or complete. A flag names a shape to check, not a lapse.",
	];
	if (captured === null) {
		directions = [
			"commits.json is missing or malformed: a collection gap, not a history without authored commits.",
		];
	} else if (authored.length === 0) {
		directions = ["No authored commit in the reviewed range: only merge commits, or none."];
	}
	return {
		hints: commitRows(facts, contextReference),
		metrics:
			captured === null
				? {}
				: {
						authoredCommits: authored.length,
						mergeCommits: facts.length - authored.length,
						bareSubjects: authored.filter((f) => f.bare).length,
						repeatedSubjects: authored.filter((f) => f.repeat).length,
						cutOffSubjects: authored.filter((f) => f.cutOff).length,
					},
		directions,
	};
}
