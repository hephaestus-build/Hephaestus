import { definePracticeScript, type EvidenceLocation } from "@hephaestus/practice-scripts";
import { languageOf, scm, type DiffFile } from "@hephaestus/practice-scripts/scm";

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const comments = addedComments(work.changedFiles());
		return ctx.review({
			leads: comments
				.filter((comment) => /\bTODO\b/u.test(comment.text))
				.map((comment) => ({ at: comment.at, label: "An added TODO comment." })),
			facts: { comments: comments.length },
			directions: ["Check each added TODO against the criteria."],
			limitations: [{ what: "comments in generated files", count: 1, reason: "selection" }],
		});
	},
});

interface AddedComment {
	at: Extract<EvidenceLocation, { change: string }>;
	language: string;
	/** The comment lines, trimmed and joined. */
	text: string;
	/** Added lines around the comment, as the change shows them. */
	code: string;
}

function addedComments(change: ReadonlyMap<string, DiffFile>): AddedComment[] {
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

function isCommentLine(content: string, language: string): boolean {
	const trimmed = content.trimStart();
	if (language === "python" || language === "ruby") {
		return trimmed.startsWith("#");
	}
	return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
}

const AROUND = 6;
