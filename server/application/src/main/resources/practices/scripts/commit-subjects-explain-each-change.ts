import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { scm, type ChangeCommit } from "@hephaestus/practice-scripts/scm";

export const LABELS = {
	commit:
		"An authored commit of the reviewed range, with the shapes of its subject: bare, repeated or cut off.",
};

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const captured = await work.commits();
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
		return ctx.review({
			leads: subjectLeads(facts, LABELS.commit).slice(0, 200),
			...(captured === null
				? {}
				: {
						facts: {
							authoredCommits: authored.length,
							mergeCommits: facts.length - authored.length,
							bareSubjects: authored.filter((f) => f.bare).length,
							repeatedSubjects: authored.filter((f) => f.repeat).length,
							cutOffSubjects: authored.filter((f) => f.cutOff).length,
						},
					}),
			directions,
		});
	},
});

interface SubjectFacts {
	sha: string;
	subject: string;
	merge: boolean;
	/** One word or less of content, a lone punctuation mark, or a bare filler such as "fix" or "wip". */
	bare: boolean;
	/** The same subject, ignoring case and trailing punctuation, appears on another authored commit. */
	repeat: boolean;
	/**
	 * The subject joins clauses with "and", "&", "+", a comma, a semicolon or a sentence break, or the body lists two or more
	 * bullets. Punctuation, not a count of changes: "parse the header and validate it" is one step.
	 */
	joinedClauses: boolean;
	/** Ends mid-phrase: on an article, a preposition or a conjunction, or with an unbalanced quote. */
	cutOff: boolean;
	bodyLines: number;
	files: ChangeCommit["files"];
}

const FILLER =
	/^(?:wip|fix(?:es|ed)?|update[sd]?|change[sd]?|stuff|misc|minor(?: changes?)?|lint(?:ing)?|cleanup|clean up|refactor(?:ing)?|tweak[s]?|test(?:s|ing)?|done|final|changes?)[.!]?$/iu;
const CONJUNCTION = /\s(?:and|&|\+)\s|,\s*\w|;\s*\w|\.\s+\p{Lu}/u;
const DANGLING = /\b(?:a|an|the|and|or|to|for|of|in|on|with|by)$|["'(]$/iu;

const normalizedSubject = (subject: string) => subject.toLowerCase().replace(/[.!\s]+$/u, "");

const isMerge = (commit: ChangeCommit, subject: string) =>
	commit.parents.length > 1 ||
	/^Merge (?:branch|remote-tracking branch|pull request|request)\b/iu.test(subject);

const subjectOf = (commit: ChangeCommit) => (commit.message.split(/\r?\n/u)[0] ?? "").trim();

function subjectFacts(commits: readonly ChangeCommit[]): SubjectFacts[] {
	// commits.json promises no order, so a repeat is counted across the range, never against "earlier".
	const authoredSubjects = new Map<string, number>();
	for (const commit of commits) {
		const subject = subjectOf(commit);
		if (!isMerge(commit, subject)) {
			const normalized = normalizedSubject(subject);
			authoredSubjects.set(normalized, (authoredSubjects.get(normalized) ?? 0) + 1);
		}
	}
	const facts: SubjectFacts[] = [];
	for (const commit of commits) {
		const lines = commit.message.split(/\r?\n/u);
		const subject = subjectOf(commit);
		const body = lines.slice(1).filter((line) => line.trim().length > 0);
		const merge = isMerge(commit, subject);
		const normalized = normalizedSubject(subject);
		const words = subject
			.replace(/^[a-z]+(?:\([^)]*\))?!?:\s*/iu, "")
			.split(/\s+/u)
			.filter(Boolean);
		const bare =
			!merge &&
			(subject.length === 0 ||
				/^[\p{P}\p{S}]+$/u.test(subject) ||
				words.length <= 1 ||
				FILLER.test(subject.replace(/^[a-z]+(?:\([^)]*\))?!?:\s*/iu, "")));
		const bulleted = body.filter((line) => /^\s*(?:[-*+]|\d+[.)])\s+/u.test(line)).length >= 2;
		facts.push({
			sha: commit.sha.slice(0, 7),
			subject,
			merge,
			bare,
			repeat: !merge && (authoredSubjects.get(normalized) ?? 0) > 1,
			joinedClauses: !merge && (CONJUNCTION.test(subject) || bulleted),
			cutOff: !merge && DANGLING.test(subject),
			bodyLines: body.length,
			files: commit.files,
		});
	}
	return facts;
}

/**
 * Subject clarity uses the words and their shapes, not a commit's code or scope. The server writes
 * `commits.json` on one line, so a lead cites the whole record and names the commit by its short `sha`.
 */
function subjectLeads(facts: readonly SubjectFacts[], label: string): ReviewLead[] {
	return facts
		.filter((fact) => !fact.merge)
		.map((fact) => ({
			at: { record: "commits.json" },
			label,
			facts: { sha: fact.sha, bare: fact.bare, repeat: fact.repeat, cutOff: fact.cutOff },
		}));
}

function describeCommitCount(facts: readonly SubjectFacts[]): string {
	const authored = facts.filter((f) => !f.merge).length;
	const merges = facts.length - authored;
	return `${authored} authored commit(s)${merges ? `, ${merges} merge commit(s) excluded` : ""}, one lead each.`;
}
