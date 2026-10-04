// Precompute FACTS for commits-are-atomic-and-cohesive: one row per authored commit — its subject,
// whether it joins clauses, and the files it touched with their line counts and moves — read from the
// commit record. The criteria say what one logical change is and the model judges the partition; a
// flag is a place to look, never a verdict.
import { readCommits } from "../lib/change.ts";
import { commitRows, describeCommitCount, subjectFacts } from "../lib/commit-subjects.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

export default async function commitsAreAtomicAndCohesive(
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
		authored.length < 2
			? [
					`${authored.length} authored commit(s) in the reviewed range: a single commit is the whole partition, and it holds one logical change or a bundle like any other.`,
				]
			: [
					describeCommitCount(facts),
					"`joinedClauses` marks a subject that joins clauses with 'and', '&', '+', a comma, a semicolon or a sentence break, or a body with two or more bullets; `moved` counts files renamed with no line changed. Both describe the record, not how many changes a commit holds.",
				];
	return {
		hints: commitRows(facts, contextReference),
		metrics: {
			authoredCommits: authored.length,
			mergeCommits: facts.length - authored.length,
			joinedSubjects: authored.filter((f) => f.joinedClauses).length,
		},
		directions,
	};
}
