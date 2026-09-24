/** Commit-subject shapes are review hints, not practice verdicts. */

import type { ChangeCommit, ChangedFile } from "./change.ts";
import { contextFile } from "./context.ts";
import type { Hint } from "./types.ts";

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
	files: ChangedFile[];
	/** The line of commits.json carrying the commit's `sha`; 0 when unknown. */
	line: number;
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
			files: commit.files,
			line: commit.line,
		});
		if (!merge) {
			seen.add(normalized);
		}
	}
	return facts;
}

/** A path's kind: its top-level directory, or its extension when it lives at the root. */
function kindOf(path: string): string {
	const slash = path.indexOf("/");
	if (slash > 0) {
		return `${path.slice(0, slash)}/`;
	}
	const dot = path.lastIndexOf(".");
	return dot > 0 ? path.slice(dot) : path;
}

/** One record row per authored commit — its subject's shape and what it touched — for the model to read. */
export function commitRows(facts: readonly SubjectFacts[], contextReference: string): Hint[] {
	return facts
		.filter((f) => !f.merge)
		.map((f) => {
			const paths = f.files.map((file) => file.path);
			return {
				file: contextFile(contextReference, "commits.json"),
				line: f.line,
				pattern: "commit",
				context: `${f.sha} ${f.subject}`,
				inDiff: false,
				flags: {
					bare: f.bare,
					repeat: f.repeat,
					conjoined: f.conjoined,
					cutOff: f.cutOff,
					bodyLines: f.bodyLines,
					files: paths.length,
					paths: paths.slice(0, 5).join(", "),
					kinds: [...new Set(paths.map(kindOf))].slice(0, 5).join(", "),
				},
			};
		});
}

export function describeCommitCount(facts: readonly SubjectFacts[]): string {
	const authored = facts.filter((f) => !f.merge).length;
	const merges = facts.length - authored;
	return `${authored} authored commit(s)${merges ? `, ${merges} merge commit(s) excluded` : ""}, one row each under Record facts.`;
}
