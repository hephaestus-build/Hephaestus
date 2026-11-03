import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { isTestPath, scm } from "@hephaestus/practice-scripts/scm";

const LABELS = {
	fatalError: "A changed fatalError() call.",
	forcedTry: "A changed try! expression.",
	forceUnwrap: "A changed force unwrap.",
	preconditionFailure: "A changed preconditionFailure() call.",
	firstElementIndex: "A changed [0] subscript with no emptiness check on the same line.",
	forcedCast: "A changed as! cast.",
} as const;
type Cue = keyof typeof LABELS;

function forceUnwrap(line: string): boolean {
	const withoutImplicit = line
		.replaceAll(/[:(,]\s*[[(<]*\s*[A-Z]\w*\s*[)>\]]*\s*!/gu, "")
		.replaceAll(/->\s*[[(<]*\s*[A-Z]\w*\s*[)>\]]*\s*!/gu, "");
	if (!/[\]a-zA-Z0-9_)]!/u.test(line) || !withoutImplicit.includes("!")) {
		return false;
	}
	if (/!=/u.test(line) && !/\w!\./u.test(line) && !/\w!\s/u.test(line)) {
		return false;
	}
	return /\w![\s,).]|!\s*$|\w!$/u.test(line) && !/\bfatalError\b|\btry!|\bas!/u.test(line);
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const files = await work.sources((file) => file.language === "swift");
		const counts = new Map<Cue, number>();
		const leads: ReviewLead[] = [];
		let inPreviewOrTest = 0;
		for (const file of files) {
			const testFile = isTestPath(file.path);
			for (const [index, line] of file.text.split("\n").entries()) {
				const at = index + 1;
				if (!file.isAdded(at) || line.trimStart().startsWith("//")) {
					continue;
				}
				const found: Cue[] = [];
				if (line.includes("fatalError(")) {
					found.push("fatalError");
				}
				if (line.includes("try!")) {
					found.push("forcedTry");
				}
				if (forceUnwrap(line)) {
					found.push("forceUnwrap");
				}
				if (line.includes("preconditionFailure(")) {
					found.push("preconditionFailure");
				}
				if (line.includes("[0]") && !/\.first\b|guard|if.*\.isEmpty|\.count\s*>/u.test(line)) {
					found.push("firstElementIndex");
				}
				if (/\bas!\s|\bas![A-Z]/u.test(line)) {
					found.push("forcedCast");
				}
				const facts = {
					inPreview: /#Preview|PreviewProvider/u.test(line),
					inTest: testFile || /XCTest|Testing|@Test/u.test(line),
					requiredCoderInit: /required init\??\(coder/u.test(line),
					unknownDefault: /@unknown default/u.test(line),
				};
				for (const key of found) {
					counts.set(key, (counts.get(key) ?? 0) + 1);
					inPreviewOrTest += facts.inPreview || facts.inTest ? 1 : 0;
					if (leads.length < 60) {
						leads.push({ at: { file: file.path, line: at }, label: LABELS[key], facts });
					}
				}
			}
		}
		const directions = [
			"Each lead is a changed Swift line. A line pattern sees only that line: a guard or an emptiness check on an earlier line is yours to read.",
			"The authored practice accepts a deliberate crash where the state is truly impossible, such as required init(coder:), @unknown default, previews and tests.",
			"For each other lead, read whether real input, a network response or a missing file can reach it, and whether guard let, ??, try with catch or as? would let the app recover.",
		];
		return ctx.review({
			leads,
			facts: {
				...Object.fromEntries([...counts].map(([key, value]) => [key, value])),
				inPreviewOrTest,
			},
			directions,
		});
	},
});
