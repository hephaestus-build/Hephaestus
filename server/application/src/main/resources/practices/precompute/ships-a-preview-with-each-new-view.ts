// Precompute HINTS for ships-a-preview-with-each-new-view: the view types this change declares and the
// previews it adds, per file. The pairing is a fact the review checks; whether a preview's data is
// representative is the review's to read.
import { isCommentLine } from "../lib/declarations.ts";
import { isTestPath, languageOf } from "../lib/languages.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

const VIEW_DECLARATION =
	/^\s*(?:@\w+\s+)*(?:(?:public|private|fileprivate|internal)\s+)?struct\s+([A-Za-z_]\w*)\s*(?:<[^>]*>)?\s*:\s*[^{]*\bView\b/;
const PREVIEW = /^\s*#Preview\b|:\s*PreviewProvider\b/;

export default function shipsAPreviewWithEachNewView(
	_repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
) {
	const hints: Hint[] = [];
	let newViews = 0;
	let previewsAdded = 0;
	let filesWithNewViewAndNoPreview = 0;
	for (const [path, df] of diffFiles) {
		if (languageOf(path) !== "swift" || isTestPath(path)) continue;
		let views = 0;
		let previews = 0;
		for (const [line, content] of df.addedLines) {
			if (isCommentLine(content, "swift")) continue;
			const view = VIEW_DECLARATION.exec(content);
			if (view) {
				views++;
				hints.push({
					file: path,
					line,
					pattern: "new view type",
					context: content.trim().slice(0, 160),
					inDiff: true,
					flags: { name: view[1] ?? "" },
				});
			} else if (PREVIEW.test(content)) {
				previews++;
				hints.push({
					file: path,
					line,
					pattern: "preview",
					context: content.trim().slice(0, 160),
					inDiff: true,
					flags: {},
				});
			}
		}
		newViews += views;
		previewsAdded += previews;
		if (views > 0 && previews === 0) filesWithNewViewAndNoPreview++;
	}
	const directions: string[] = [];
	if (newViews > 0) {
		directions.push(
			`${newViews} new view type(s) declared and ${previewsAdded} preview(s) added; ${filesWithNewViewAndNoPreview} file(s) declare a view and add no preview — a preview may live in another file of the change, so scan the whole diff before deciding.`,
		);
	}
	return {
		hints: hints.slice(0, 40),
		metrics: { newViews, previewsAdded, filesWithNewViewAndNoPreview },
		directions,
	};
}
