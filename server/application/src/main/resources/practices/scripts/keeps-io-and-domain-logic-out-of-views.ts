import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { scm } from "@hephaestus/practice-scripts/scm";

const NETWORK_TYPES = ["URLSession", "URLRequest", "HTTPURLResponse", "URLComponents"];
const NETWORK_CALLS = [
	/URLSession\s*\.\s*shared/u,
	/\.data\s*\(\s*from:/u,
	/\.data\s*\(\s*for:/u,
	/\.dataTask\s*\(/u,
	/\.download\s*\(\s*from:/u,
	/\.upload\s*\(/u,
];
const UI_CODE = /NavigationLink|\.sheet|\.alert|\.toolbar|Text\(|Image\(|VStack|HStack|Button/u;

const LABELS = {
	networking: "A changed line in a view file that uses a networking type or call.",
	awaitedFetch: "A changed line in a view file that awaits a fetch, request or URL.",
	jsonCoding: "A changed line in a view file that creates a JSON encoder or decoder.",
	userDefaults: "A changed line in a view file that uses UserDefaults.standard.",
	timer: "A changed line in a view file that creates a Timer.",
	fileManager: "A changed line in a view file that uses FileManager.default.",
	longPrivateFunction:
		"A changed private function of more than 15 lines in a view file, with no UI code.",
	manyPrivateFunctions: "A changed view file with three or more private functions.",
} as const;
type Cue = keyof typeof LABELS;

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
			const lines = file.text.split("\n");
			const views = lines.flatMap(
				(line) =>
					/struct\s+(?<name>\w+)\s*:\s*(?:\w+\s*,\s*)*View\s*[,{]/u.exec(line)?.groups?.name ?? [],
			);
			if (views.length === 0 || /import\s+XCTest|import\s+Testing/u.test(file.text)) {
				continue;
			}
			const view = views.join(", ").slice(0, 200);
			const { isAdded } = file;
			for (const [index, line] of lines.entries()) {
				const at = index + 1;
				if (!isAdded(at) || line.trimStart().startsWith("//")) {
					continue;
				}
				const type = NETWORK_TYPES.find((name) => line.includes(name));
				if (type !== undefined || NETWORK_CALLS.some((pattern) => pattern.test(line))) {
					cue("networking", file.path, at, { networkType: type ?? "method call", view });
				} else if (
					/\bawait\b/u.test(line) &&
					/url|URL|fetch|request|api|API|endpoint|data\(from|data\(for/iu.test(line)
				) {
					cue("awaitedFetch", file.path, at, { view });
				}
				if (/(?:JSONDecoder|JSONEncoder)\(\)/u.test(line)) {
					cue("jsonCoding", file.path, at, { view });
				}
				if (line.includes("UserDefaults.standard")) {
					cue("userDefaults", file.path, at, { view });
				}
				if (line.includes("Timer.publish") || line.includes("Timer.scheduledTimer")) {
					cue("timer", file.path, at, { view });
				}
				if (line.includes("FileManager.default")) {
					cue("fileManager", file.path, at, { view });
				}
			}
			let privateFunctions = 0;
			let current: { name: string; start: number; depth: number; lines: number } | null = null;
			for (const [index, line] of lines.entries()) {
				const name = /private\s+func\s+(?<name>\w+)/u.exec(line)?.groups?.name;
				if (name !== undefined) {
					current = { name, start: index + 1, depth: 0, lines: 0 };
					privateFunctions += 1;
				}
				if (current === null) {
					continue;
				}
				current.lines += 1;
				current.depth += (line.match(/\{/gu) ?? []).length - (line.match(/\}/gu) ?? []).length;
				if (current.depth <= 0 && current.lines > 1) {
					const body = lines.slice(current.start - 1, current.start - 1 + current.lines).join("\n");
					if (current.lines > 15 && !UI_CODE.test(body) && isAdded(current.start)) {
						cue("longPrivateFunction", file.path, current.start, {
							function: current.name,
							lines: current.lines,
							view,
						});
					}
					current = null;
				}
			}
			if (privateFunctions >= 3) {
				cue("manyPrivateFunctions", file.path, 1, { privateFunctions, view });
			}
		}
		const directions = [
			"Leads are changed lines of Swift files that declare a SwiftUI view, so a networking or storage line can belong to another type in the same file. Read which type owns each line.",
			"The authored practice keeps views to layout and user intent: networking, JSON coding, storage, timers and multi-step domain rules belong in a model, service or view model that the view calls. A small private helper that formats a value for display can stay.",
		];
		return ctx.review({
			leads,
			facts: Object.fromEntries([...counts].map(([key, value]) => [key, value])),
			directions,
		});
	},
});
