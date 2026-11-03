import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { isTestPath, languageOf, scm } from "@hephaestus/practice-scripts/scm";

const VIEW_DECLARATION =
	/^\s*(?:@\w+\s+)*(?:(?:public|private|fileprivate|internal)\s+)?struct\s+(?<name>[A-Za-z_]\w*)\s*(?:<[^>]*>)?\s*:\s*[^{]*\bView\b/u;
const PREVIEW = /^\s*#Preview\b|:\s*PreviewProvider\b/u;

export const LABELS = {
	"new-view-type": "An added struct that conforms to View, with its name.",
	preview: "An added #Preview or PreviewProvider.",
};

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const leads: ReviewLead[] = [];
		let newViews = 0;
		let previewsAdded = 0;
		let filesWithNewViewAndNoPreview = 0;
		for (const [path, df] of work.changedFiles()) {
			if (languageOf(path) !== "swift" || isTestPath(path)) {
				continue;
			}
			let views = 0;
			let previews = 0;
			for (const [line, content] of df.addedLines) {
				if (isCommentLine(content, "swift")) {
					continue;
				}
				const view = VIEW_DECLARATION.exec(content);
				if (view) {
					views += 1;
					leads.push({
						at: { change: path, line },
						label: LABELS["new-view-type"],
						facts: { name: view.groups?.name ?? "" },
					});
				} else if (PREVIEW.test(content)) {
					previews += 1;
					leads.push({ at: { change: path, line }, label: LABELS.preview });
				}
			}
			newViews += views;
			previewsAdded += previews;
			if (views > 0 && previews === 0) {
				filesWithNewViewAndNoPreview += 1;
			}
		}
		const directions: string[] = [];
		if (newViews > 0) {
			directions.push(
				`${newViews} new view type(s) declared and ${previewsAdded} preview(s) added; ${filesWithNewViewAndNoPreview} file(s) declare a view and add no preview — a preview may live in another file of the change, so scan the whole diff before deciding.`,
				"These counts pair no preview with a view. Read the previews and view bodies to trace the presentation coverage described in the criteria, including nested views and content delegated by an effect-only owner.",
			);
		}
		return ctx.review({
			leads: leads.slice(0, 40),
			facts: { newViews, previewsAdded, filesWithNewViewAndNoPreview },
			directions,
		});
	},
});

function isCommentLine(content: string, language: string): boolean {
	const trimmed = content.trimStart();
	if (language === "python" || language === "ruby") {
		return trimmed.startsWith("#");
	}
	return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
}
