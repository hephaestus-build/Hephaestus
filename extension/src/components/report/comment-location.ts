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
 * Where a comment is on the work, as the provider would point to it: the summary comment, or the file
 * and lines a comment on the changes sits on. `short` names the file alone, for a list row where the
 * whole path would crowd out everything else; the full path stays in `where(comment, false)`.
 */
export function where(comment: WorkComment, short: boolean): string {
	if (comment.kind === "SUMMARY") {
		return short ? "Summary" : "Summary comment";
	}
	if (comment.path === undefined) {
		return "A line of the changes";
	}
	const file = short ? (comment.path.split("/").at(-1) ?? comment.path) : comment.path;
	return `${file}${lines(comment)}`;
}
