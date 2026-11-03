import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { scm } from "@hephaestus/practice-scripts/scm";

const LABELS = {
	unlabeledImage:
		"A changed Image(systemName:) with no accessibility label or hiding nearby and no text label around it.",
	fixedFontSize: "A changed .font(.system(size:)) with a fixed point size.",
	tapWithoutTraits: "A changed .onTapGesture with no accessibility trait or label nearby.",
} as const;
type Cue = keyof typeof LABELS;

function labelledImage(lines: readonly string[], index: number): boolean {
	for (let i = Math.max(0, index - 3); i <= Math.min(lines.length - 1, index + 5); i += 1) {
		const line = lines[i] ?? "";
		if (
			/\.accessibilityLabel|\.accessibilityHidden/u.test(line) ||
			/Button\(.*,\s*systemImage:|Label\(.*,\s*systemImage:/u.test(line) ||
			(i < index &&
				/Button.*\{/u.test(line) &&
				lines.slice(i, index + 4).some((l) => /Text\(/u.test(l)))
		) {
			return true;
		}
	}
	return false;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const files = await work.sources((file) => file.language === "swift");
		const counts = new Map<Cue, number>();
		const leads: ReviewLead[] = [];
		for (const file of files) {
			if (!/:\s*View\s*\{/u.test(file.text)) {
				continue;
			}
			const lines = file.text.split("\n");
			for (const [index, line] of lines.entries()) {
				if (!file.isAdded(index + 1)) {
					continue;
				}
				const found: Cue[] = [];
				if (/Image\(systemName:/u.test(line) && !labelledImage(lines, index)) {
					found.push("unlabeledImage");
				}
				if (/\.font\(\.system\(size:\s*\d+/u.test(line)) {
					found.push("fixedFontSize");
				}
				if (
					/\.onTapGesture/u.test(line) &&
					!lines
						.slice(Math.max(0, index - 2), index + 6)
						.some((l) => /\.accessibilityAddTraits|\.accessibilityLabel/u.test(l))
				) {
					found.push("tapWithoutTraits");
				}
				for (const key of found) {
					counts.set(key, (counts.get(key) ?? 0) + 1);
					if (leads.length < 60) {
						leads.push({ at: { file: file.path, line: index + 1 }, label: LABELS[key] });
					}
				}
			}
		}
		const directions = [
			"Each lead is a changed line of a Swift file that declares a view. Nearby means three lines before to five lines after; a label set further away or on a parent view is yours to read.",
			"The authored practice asks that VoiceOver users hear what an icon means, that text follows Dynamic Type, and that a tappable element is announced as a button. A decorative image can be hidden instead of labelled, and a fixed size can be right for a graphic that is not text.",
		];
		return ctx.review({
			leads,
			facts: Object.fromEntries([...counts].map(([key, value]) => [key, value])),
			directions,
		});
	},
});
