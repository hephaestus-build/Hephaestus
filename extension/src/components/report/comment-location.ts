import type { WorkComment } from "~/shared/review-context";

function lines({ startLine, endLine }: WorkComment): string {
	if (startLine === undefined) {
		return "";
	}
	return endLine !== undefined && endLine > startLine
		? `:${startLine}–${endLine}`
		: `:${startLine}`;
}

/**
 * Where a comment is on the work, as the provider would point to it: the summary comment, or the
 * file, and lines when it has them, of a comment on the changes. `short` names the file alone, for a
 * list row where the whole path would crowd out everything else; the full path stays in
 * `where(comment, false)`.
 */
export function where(comment: WorkComment, short: boolean): string {
	if (comment.kind === "SUMMARY") {
		return short ? "Summary" : "Summary comment";
	}
	if (comment.path === undefined) {
		if (comment.kind === "FILE_NOTE") {
			return "A file of the changes";
		}
		// A kind from a newer server says nothing that this build can trust about its place.
		return comment.kind === "LINE_NOTE" ? "A line of the changes" : "A comment on this work";
	}
	const file = short ? (comment.path.split("/").at(-1) ?? comment.path) : comment.path;
	return `${file}${lines(comment)}`;
}
