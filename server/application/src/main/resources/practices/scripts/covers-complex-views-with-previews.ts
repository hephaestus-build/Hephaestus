import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { isTestPath, scm } from "@hephaestus/practice-scripts/scm";

const VIEW = /:\s*View\s*\{/u;
const TEST_IMPORT = /import\s+XCTest|import\s+Testing/u;
const CONDITIONAL = /\bif\s+.*\{|else\s*\{|switch\s+/u;

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const files = await work.sources((file) => file.language === "swift" && !isTestPath(file.path));
		const leads: ReviewLead[] = [];
		let viewFiles = 0;
		let complexWithoutPreview = 0;
		let complexWithOnePreview = 0;
		for (const file of files) {
			if (!VIEW.test(file.text) || TEST_IMPORT.test(file.text) || /@main/u.test(file.text)) {
				continue;
			}
			viewFiles += 1;
			const lines = file.text.split("\n");
			const previews = lines.flatMap((line, index) => (/#Preview/u.test(line) ? [index + 1] : []));
			const stateProperties = lines.filter((line) => /@State\s/u.test(line)).length;
			const hasConditional = lines.some((line) => CONDITIONAL.test(line));
			const hasSwitch = lines.some((line) => /\bswitch\s+/u.test(line));
			const facts = { previewBlocks: previews.length, stateProperties, hasConditional, hasSwitch };
			if (!(stateProperties > 2 || hasConditional || hasSwitch)) {
				continue;
			}
			if (previews.length === 0) {
				complexWithoutPreview += 1;
				leads.push({
					at: { file: file.path, line: 1 },
					label: "A changed view file with state or branching and no #Preview block.",
					facts,
				});
			} else if (previews.length === 1 && (hasSwitch || stateProperties > 3)) {
				complexWithOnePreview += 1;
				leads.push({
					at: { file: file.path, line: previews[0] ?? 1 },
					label: "A changed view file with a switch or more than three @State and one #Preview.",
					facts,
				});
			}
		}
		const directions = [
			"A view counts as complex here when its file has more than two @State lines, an if/else or a switch line. These are whole-file line counts: a state or a branch can belong to another type in the file.",
		];
		if (leads.length > 0) {
			directions.push(
				"For each lead, read which states the view can show and whether the file's previews render those states. A single preview can be enough when the other states are trivial.",
			);
		}
		if (viewFiles === 0) {
			directions.push("No changed SwiftUI view file was found.");
		}
		return ctx.review({
			leads,
			facts: { viewFiles, complexWithoutPreview, complexWithOnePreview },
			directions,
		});
	},
});
