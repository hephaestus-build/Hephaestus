// Precompute FACTS for commits-are-atomic-and-cohesive: one row per authored commit — its subject,
// whether it lists several concerns, and the files it touched — read from the change view. The
// criteria say what a listed subject means and the model judges the partition; a flag is a place to
// look, never a verdict.
import { readCommits } from "../lib/change.ts";
import { commitRows, describeCommitCount, subjectFacts } from "../lib/commit-subjects.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

export default async function commitsAreAtomicAndCohesive(
	_repoPath: string,
	_diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
	contextDir?: string,
) {
	const facts = subjectFacts(await readCommits(contextDir));
	const authored = facts.filter((f) => !f.merge);
	const directions =
		authored.length < 2
			? [
					`${authored.length} authored commit(s) in the reviewed range: with fewer than two there is no partition to judge.`,
				]
			: [
					describeCommitCount(facts),
					"A subject that lists several concerns is the shape the criteria name; read it literally, beside the paths and kinds the commit touched, and judge whether the listed items are one step or several.",
				];
	return {
		hints: commitRows(facts),
		metrics: {
			authoredCommits: authored.length,
			mergeCommits: facts.length - authored.length,
			conjoinedSubjects: authored.filter((f) => f.conjoined).length,
		},
		directions,
	};
}
