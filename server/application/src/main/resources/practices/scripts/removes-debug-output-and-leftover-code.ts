import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { scm } from "@hephaestus/practice-scripts/scm";

const COMMENTED_CODE =
	/^\/\/\s*(?:let |var |func |if |for |return |guard |switch |case |struct |class |import |@)/u;

const LABELS = {
	print: "A changed print() call outside a catch block.",
	printOnlyButton: "A changed button whose action only prints.",
	debugPrint: "A changed debugPrint() call.",
	dump: "A changed dump() call.",
	nslog: "A changed NSLog() call.",
	commentedCode: "A changed block of two or more commented-out code lines.",
	xcodePlaceholder: "A changed line with an Xcode placeholder token.",
	staleHeader: "A changed file header that names another file.",
	todo: "A changed TODO or FIXME comment.",
} as const;
type Cue = keyof typeof LABELS;

function printOnlyButton(lines: readonly string[], index: number): boolean {
	for (let i = index; i >= Math.max(0, index - 3); i -= 1) {
		if (/Button.*\{/u.test(lines[i] ?? "")) {
			return lines
				.slice(i, index + 3)
				.every((l) => l.trim() === "" || /Button|print\(|\{|\}/u.test(l.trim()));
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
		const cue = (
			key: Cue,
			file: string,
			line: number,
			facts: Record<string, string | number> = {},
		) => {
			counts.set(key, (counts.get(key) ?? 0) + 1);
			if (leads.length < 60) {
				leads.push({ at: { file, line }, label: LABELS[key], facts });
			}
		};
		for (const file of files) {
			const { isAdded } = file;
			const lines = file.text.split("\n");
			const header = /^\/\/\s+(?<name>\S+\.swift)/u.exec(lines[0] ?? "")?.groups?.name;
			const actual = file.path.split("/").pop() ?? "";
			if (header !== undefined && header !== actual && isAdded(1)) {
				cue("staleHeader", file.path, 1, { headerName: header, fileName: actual });
			}
			let run = 0;
			for (const [index, line] of [...lines, ""].entries()) {
				const at = index + 1;
				if (COMMENTED_CODE.test(line.trim())) {
					run += 1;
				} else {
					if (run >= 2 && isAdded(at - run)) {
						cue("commentedCode", file.path, at - run, { lines: run });
					}
					run = 0;
				}
				if (!isAdded(at)) {
					continue;
				}
				if (/TODO|FIXME/u.test(line)) {
					cue("todo", file.path, at);
				}
				if (line.includes("@START_MENU_TOKEN@")) {
					cue("xcodePlaceholder", file.path, at);
				}
				if (line.trimStart().startsWith("//")) {
					continue;
				}
				const debugBlock = /#if DEBUG/u.test(line);
				if (
					line.includes("print(") &&
					!debugBlock &&
					!/^import\s/u.test(line.trimStart()) &&
					!lines
						.slice(Math.max(0, index - 5), index + 1)
						.some((l) => /\}\s*catch|catch\s*\{|catch\s+let/u.test(l))
				) {
					cue(printOnlyButton(lines, index) ? "printOnlyButton" : "print", file.path, at);
				}
				if (line.includes("debugPrint(") && !debugBlock) {
					cue("debugPrint", file.path, at);
				}
				if (line.includes("dump(") && !debugBlock) {
					cue("dump", file.path, at);
				}
				if (line.includes("NSLog(")) {
					cue("nslog", file.path, at);
				}
			}
		}
		const count = (key: Cue) => counts.get(key) ?? 0;
		const directions = [
			"Each lead is a changed line of a changed Swift file. A print inside a catch block is left to the error-handling practice, and a line pattern cannot see an enclosing #if DEBUG block.",
		];
		if (count("print") + count("debugPrint") + count("dump") + count("nslog") > 0) {
			directions.push(
				"The authored practice expects diagnostic output to go through os.Logger or to be removed before merge. Read whether each call is a leftover or deliberate output, such as a command-line tool's.",
			);
		}
		if (count("printOnlyButton") > 0) {
			directions.push(
				"A button that only prints does nothing for the user. Read whether the action is still a placeholder.",
			);
		}
		if (count("commentedCode") > 0) {
			directions.push(
				"The authored practice treats commented-out code as removable because version control keeps the old code. Read whether a block is an example or documentation instead.",
			);
		}
		if (count("todo") > 0) {
			directions.push(
				"Read whether each TODO or FIXME is tracked in an issue or resolved by this change.",
			);
		}
		return ctx.review({
			leads,
			facts: Object.fromEntries([...counts].map(([key, value]) => [key, value])),
			directions,
		});
	},
});
