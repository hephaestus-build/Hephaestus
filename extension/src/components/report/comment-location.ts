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
 * Where a comment is on the work, as the provider would point to it: the summary comment, the file
 * and lines a comment on the changes sits on, or the lines a comment on the work links to. `short`
 * names the file alone, for a list row where the whole path would crowd out everything else; the full
 * path stays in `where(comment, false)`.
 */
export function where(comment: WorkComment, short: boolean): string {
	if (comment.kind === "SUMMARY") {
		return short ? "Summary" : "Summary comment";
	}
	if (comment.path === undefined) {
		return comment.kind === "LOCATION_COMMENT"
			? "A comment linking to the changes"
			: "A line of the changes";
	}
	const file = short ? (comment.path.split("/").at(-1) ?? comment.path) : comment.path;
	return comment.kind === "LOCATION_COMMENT" && !short
		? `Comment linking to ${file}${lines(comment)}`
		: `${file}${lines(comment)}`;
}
