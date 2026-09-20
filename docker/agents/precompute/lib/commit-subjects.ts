/**
 * Facts about the authored commit subjects of the reviewed range, for the two commit practices. A
 * fact here is a shape the subject has — a merge, a bare word, a repeat, a list of concerns, a cut-off
 * phrase — never a verdict: the criteria say what each shape means, and the model decides.
 */

import type { ChangeCommit } from "./change.ts";

export interface SubjectFacts {
	sha: string;
	subject: string;
	merge: boolean;
	/** One word or less of content, a lone punctuation mark, or a bare filler such as "fix" or "wip". */
	bare: boolean;
	/** The same subject, ignoring case and trailing punctuation, appears on an earlier commit. */
	repeat: boolean;
	/** Two or more concerns joined by "and", commas, "+", "&", ";" or a bullet list in the message. */
	conjoined: boolean;
	/** Ends mid-phrase: on an article, a preposition or a conjunction, or with an unbalanced quote. */
	cutOff: boolean;
	bodyLines: number;
}

const FILLER =
	/^(?:wip|fix(?:es|ed)?|update[sd]?|change[sd]?|stuff|misc|minor(?: changes?)?|lint(?:ing)?|cleanup|clean up|refactor(?:ing)?|tweak[s]?|test(?:s|ing)?|done|final|changes?)[.!]?$/iu;
const CONJUNCTION = /\s(?:and|&|\+)\s|,\s*\w|;\s*\w/u;
const DANGLING = /\b(?:a|an|the|and|or|to|for|of|in|on|with|by)$|["'(]$/iu;

export function subjectFacts(commits: readonly ChangeCommit[]): SubjectFacts[] {
	const seen = new Set<string>();
	const facts: SubjectFacts[] = [];
	for (const commit of commits) {
		const lines = commit.message.split(/\r?\n/u);
		const subject = (lines[0] ?? "").trim();
		const body = lines.slice(1).filter((line) => line.trim().length > 0);
		const merge =
			commit.parents.length > 1 ||
			/^Merge (?:branch|remote-tracking branch|pull request|request)\b/iu.test(subject);
		const normalized = subject.toLowerCase().replace(/[.!\s]+$/u, "");
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
			repeat: !merge && seen.has(normalized),
			conjoined: !merge && (CONJUNCTION.test(subject) || bulleted),
			cutOff: !merge && DANGLING.test(subject),
			bodyLines: body.length,
		});
		if (!merge) {
			seen.add(normalized);
		}
	}
	return facts;
}

/** The facts as lines the model reads, one per authored commit, plus the merge count. */
export function describeSubjects(facts: readonly SubjectFacts[]): string[] {
	const authored = facts.filter((f) => !f.merge);
	const lines = authored.map((f) => {
		const flags = [
			f.bare ? "bare" : "",
			f.repeat ? "repeats an earlier subject" : "",
			f.conjoined ? "lists several concerns" : "",
			f.cutOff ? "cut off mid-phrase" : "",
			f.bodyLines ? `${f.bodyLines} body line(s)` : "",
		].filter(Boolean);
		return `${f.sha} "${f.subject}"${flags.length > 0 ? ` — ${flags.join("; ")}` : ""}`;
	});
	const merges = facts.length - authored.length;
	return [
		`${authored.length} authored commit(s)${merges ? `, ${merges} merge commit(s) excluded` : ""}:`,
		...lines,
	];
}
