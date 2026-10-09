// Subject clarity reads authored subject text and its shapes from the captured commit record.
import { readCapturedCommits } from "../lib/change.ts";
import { describeCommitCount, subjectFacts, subjectRows } from "../lib/commit-subjects.ts";
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
		"Judge every subject on whether it names a specific action and object for a reader of the history. A flag names a shape to check, not a lapse.",
	];
	if (captured === null) {
		directions = [
			"commits.json is missing or malformed: a collection gap, not a history without authored commits.",
		];
	} else if (authored.length === 0) {
		directions = ["No authored commit in the reviewed range: only merge commits, or none."];
	}
	return {
		hints: subjectRows(facts, contextReference),
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
