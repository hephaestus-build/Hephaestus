import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { scm } from "@hephaestus/practice-scripts/scm";

const STATEFUL_NAME =
	/save|delete|remove|add|create|update|fetch|load|send|submit|persist|store|insert|edit|modify/iu;
const LOG = /Logger|os_log|log\./u;
const CATCH = /\}\s*catch|catch\s*\{|catch\s+let/u;

const LABELS = {
	emptyCatch: "A changed catch block with no statement.",
	catchOnlyPrints: "A changed catch block that only prints.",
	catchOnlyLogs: "A changed catch block that only logs.",
	catchReturnsEmpty: "A changed catch block that returns nil or an empty value.",
	catchLogsThenReturnsEmpty:
		"A changed catch block that logs or prints and then returns nil or an empty array.",
	optionalTrySave: "A changed try? on a save call.",
	optionalTryNetwork: "A changed try? await on a network call.",
	functionReturnsNilFromCatches:
		"A changed function that returns nil from two or more catch paths.",
	emptyStatefulFunction: "A changed function with a data-changing name and an empty body.",
} as const;
type Cue = keyof typeof LABELS;

/**
 * The trimmed lines after `start` up to the end of the block that opens on it, at most `limit` lines
 * on. Braces count from column `from`, so the `}` before `catch` does not close the catch block.
 */
function blockAfter(lines: readonly string[], start: number, limit: number, from = 0): string[] {
	const body: string[] = [];
	let depth = 0;
	let started = false;
	for (let i = start; i <= Math.min(start + limit, lines.length - 1); i += 1) {
		const line = i === start ? (lines[i] ?? "").slice(from) : (lines[i] ?? "");
		for (const char of line) {
			if (char === "{") {
				depth += 1;
				started = true;
			} else if (char === "}") {
				depth -= 1;
			}
		}
		if (i > start) {
			body.push(line.trim());
		}
		if (started && depth <= 0) {
			break;
		}
	}
	return body;
}

function catchCue(body: string): Cue | null {
	if (
		/fatalError\(|\bthrow\b|errorMessage|errorText|error\s*=|showError|isError|alertMessage/u.test(
			body,
		)
	) {
		return null;
	}
	const statements = body
		.split("\n")
		.filter((line) => line.trim() !== "" && line.trim() !== "}").length;
	if (body.trim() === "") {
		return "emptyCatch";
	}
	if (/^\s*(?:print\(|debugPrint\()/u.test(body) && statements <= 2) {
		return "catchOnlyPrints";
	}
	if (LOG.test(body) && statements <= 2) {
		return "catchOnlyLogs";
	}
	if (/^\s*return\s+(?:nil|\.init\(\)|\[\s*\]|\[\s*:\s*\])/u.test(body.trim())) {
		return "catchReturnsEmpty";
	}
	if (
		/Logger|os_log|log\.|print\(|debugPrint\(/u.test(body) &&
		/return\s+(?:nil|\[\s*\])/u.test(body)
	) {
		return "catchLogsThenReturnsEmpty";
	}
	return null;
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
			if (/import\s+XCTest|import\s+Testing/u.test(file.text)) {
				continue;
			}
			const { isAdded } = file;
			const lines = file.text.split("\n");
			for (const [index, line] of lines.entries()) {
				const at = index + 1;
				if (isAdded(at) && line.includes("} catch")) {
					const found = catchCue(
						blockAfter(lines, index, 30, line.indexOf("catch"))
							.filter((l) => l !== "" && l !== "}")
							.join("\n"),
					);
					if (found !== null) {
						cue(found, file.path, at);
					}
				}
				if (
					isAdded(at) &&
					line.includes("try?") &&
					!line.trimStart().startsWith("//") &&
					!/#Preview|PreviewProvider|@Previewable|Task\.sleep|\?\?/u.test(line)
				) {
					if (/modelContext\.save|context\.save|viewContext\.save|\.save\(\)/u.test(line)) {
						cue("optionalTrySave", file.path, at);
					} else if (
						/try\?\s*await/u.test(line) &&
						/URLSession|\.data\(from|\.data\(for|session\.data|fetch|download/u.test(line)
					) {
						cue("optionalTryNetwork", file.path, at);
					}
				}
				const name = /func\s+(?<name>\w+)\s*\(/u.exec(line)?.groups?.name;
				if (
					isAdded(at) &&
					name !== undefined &&
					STATEFUL_NAME.test(name) &&
					!/viewDidLoad|viewWillAppear|viewDidAppear|viewWillDisappear|viewDidDisappear/u.test(name)
				) {
					const open = lines.slice(index, index + 4).findIndex((l) => l.includes("{"));
					const body = open === -1 ? [] : blockAfter(lines, index + open, 15);
					if (body.length > 0 && body.every((l) => l === "" || l === "}" || l.startsWith("//"))) {
						cue("emptyStatefulFunction", file.path, at, { function: name });
					}
				}
			}
			for (const found of functionsReturningNilFromCatches(lines)) {
				if (found.nils.some((line) => isAdded(line))) {
					cue("functionReturnsNilFromCatches", file.path, found.line, {
						function: found.name,
						nilReturns: found.nils.length,
					});
				}
			}
		}
		const directions = [
			"Each lead is a changed line of non-test Swift. A catch block that throws, stops the app or sets an error property is not a lead, and a try? with a ?? fallback is not a lead.",
			"The authored practice asks that a failure the user or the developer needs to know about is not lost: read whether each lead shows, logs with context, rethrows or deliberately ignores the error, and whether returning nil hides why the work failed.",
		];
		return ctx.review({
			leads,
			facts: Object.fromEntries([...counts].map(([key, value]) => [key, value])),
			directions,
		});
	},
});

function functionsReturningNilFromCatches(
	lines: readonly string[],
): { name: string; line: number; nils: number[] }[] {
	const found: { name: string; line: number; nils: number[] }[] = [];
	let current: {
		name: string;
		line: number;
		nils: number[];
		depth: number;
		inCatch: boolean;
		catchDepth: number;
	} | null = null;
	for (const [index, line] of lines.entries()) {
		const at = index + 1;
		const name = /func\s+(?<name>\w+)/u.exec(line)?.groups?.name;
		if (name !== undefined && /->/u.test(line) && !/\bthrows\b/u.test(line)) {
			current = { name, line: at, nils: [], depth: 0, inCatch: false, catchDepth: 0 };
		}
		if (current === null) {
			continue;
		}
		for (const char of line) {
			if (char === "{") {
				current.depth += 1;
			} else if (char === "}") {
				current.depth -= 1;
				if (current.inCatch && current.depth < current.catchDepth) {
					current.inCatch = false;
				}
			}
		}
		if (CATCH.test(line)) {
			current.inCatch = true;
			current.catchDepth = current.depth;
		}
		if (current.inCatch && /return\s+nil/u.test(line)) {
			current.nils.push(at);
		}
		if (current.depth <= 0 && at > current.line) {
			if (current.nils.length >= 2) {
				found.push({ name: current.name, line: current.line, nils: current.nils });
			}
			current = null;
		}
	}
	return found;
}
