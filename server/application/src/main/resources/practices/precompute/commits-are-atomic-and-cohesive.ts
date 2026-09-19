// Precompute FACTS for commits-are-atomic-and-cohesive: every authored subject with whether it lists
// several concerns, read from the change view. The criteria say what a listed subject means and the
// model judges the partition; a flag is a place to look, never a verdict.
import { readCommits } from "../lib/change.ts";
import { describeSubjects, subjectFacts } from "../lib/commit-subjects.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

export default async function commitsAreAtomicAndCohesive(
	_repoPath: string,
	_diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
	_contextDir?: string,
	changeDir?: string,
) {
	const facts = subjectFacts(await readCommits(changeDir));
	const authored = facts.filter((f) => !f.merge);
	const directions =
		authored.length < 2
			? [
					`${authored.length} authored commit(s) in the reviewed range: with fewer than two there is no partition to judge.`,
				]
			: [
					...describeSubjects(facts),
					"A subject that lists several concerns is the shape the criteria name; read it literally and judge whether the listed items are one step or several.",
				];
	return {
		hints: [],
		metrics: {
			authoredCommits: authored.length,
			mergeCommits: facts.length - authored.length,
			conjoinedSubjects: authored.filter((f) => f.conjoined).length,
		},
		directions,
	};
}
