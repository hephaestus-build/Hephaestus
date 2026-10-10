/**
 * Added comments as items, and a closed vocabulary to rate them. A starting point for the practices
 * that judge comments; the kinds follow Pascarella & Bacchelli (MSR 2017) and the inline comment
 * smells of Jabrayilzade et al. (EMSE 2024), cut to what a practice can act on.
 */
import type { Citation } from "./contract.ts";
import { isCommentLine } from "./declarations.ts";
import { languageOf } from "./languages.ts";
import type { QuestionSet } from "./precompute.ts";
import type { DiffFile } from "./types.ts";

export interface AddedComment {
	at: Extract<Citation, { change: string }>;
	language: string;
	/** The comment lines, trimmed and joined. */
	text: string;
	/** Added lines around the comment, as the change shows them. */
	code: string;
}

const AROUND = 6;

/** Each run of added comment lines in a hand-written source file, with the added code around it. */
export function addedComments(change: ReadonlyMap<string, DiffFile>): AddedComment[] {
	const found: AddedComment[] = [];
	for (const [path, file] of change) {
		const language = languageOf(path);
		if (language === null) {
			continue;
		}
		const lines = [...file.addedLines];
		for (let i = 0; i < lines.length; i += 1) {
			const [line, content] = lines[i] ?? [0, ""];
			const previous = lines[i - 1];
			if (
				!isCommentLine(content, language) ||
				(previous !== undefined && previous[0] === line - 1 && isCommentLine(previous[1], language))
			) {
				continue;
			}
			let end = i;
			for (
				let next = lines[end + 1];
				next !== undefined &&
				next[0] === (lines[end]?.[0] ?? 0) + 1 &&
				isCommentLine(next[1], language);
				next = lines[end + 1]
			) {
				end += 1;
			}
			const last = lines[end]?.[0] ?? line;
			found.push({
				at: { change: path, line, ...(last > line ? { endLine: last } : {}), side: "NEW" },
				language,
				text: lines
					.slice(i, end + 1)
					.map(([, text]) => text.trim())
					.join("\n"),
				code: lines
					.filter(([n]) => n >= line - AROUND && n <= last + AROUND + 2)
					.map(([, text]) => text)
					.join("\n"),
			});
		}
	}
	return found;
}

/** The kinds a comment can be. A practice decides in code which kinds deserve a look. */
export const commentKinds: QuestionSet<AddedComment> = {
	name: "comment kind",
	noun: "added code comment with the code around it",
	question: "What kind of comment is it, judged against the code around it?",
	values: {
		"explains-why":
			"Gives a reason, constraint, unit, caveat, invariant or reference that the code alone does not convey",
		"documents-contract":
			"Documents a function or type's parameters, return value, errors or usage",
		"tracked-todo":
			"Marks open work and references a tracking item such as an issue number, ticket key or URL",
		directive:
			"A tool directive or required marker: lint suppression, compiler pragma, code generation, license header",
		"restates-code": "Only says what the adjacent code already says plainly",
		"commented-out-code": "Source code disabled behind a comment marker",
		"untracked-todo": "Marks open work (TODO, FIXME, HACK, XXX) without any tracking reference",
		"narrates-change":
			"Narrates the editing session or change history instead of the code as it is",
		"temporary-stub": "Marks temporary, debug or placeholder residue meant to be removed",
		misleading: "States something that the adjacent code does not do",
	},
	render: (comment) =>
		`Comment: ${comment.text}\nCode around it (${comment.language}):\n${comment.code}`,
};
