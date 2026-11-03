import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { isTestPath, scm } from "@hephaestus/practice-scripts/scm";

const CONTAINER =
	/\b(?:VStack|HStack|ZStack|LazyVStack|LazyHStack|LazyVGrid|LazyHGrid|List|Form|ScrollView|Group|NavigationStack|NavigationSplitView|TabView|Section)\s*(?:\(.*?\))?\s*\{/u;

interface ViewBody {
	name: string;
	line: number;
	bodyStart: number;
	bodyEnd: number;
	bodyLines: number;
	stateProperties: number;
	nestingDepth: number;
	topLevelContainers: number;
	sameTaskAndRefresh: boolean;
}

const braces = (line: string) =>
	(line.match(/\{/gu) ?? []).length - (line.match(/\}/gu) ?? []).length;

/** True when a .task block and a .refreshable block in the body run (nearly) the same code. */
function sameTaskAndRefresh(lines: readonly string[], start: number, end: number): boolean {
	const blocks: Record<"task" | "refreshable", string[]> = { task: [], refreshable: [] };
	let kind: "task" | "refreshable" | null = null;
	let depth = 0;
	let body = "";
	for (let index = start; index <= end; index += 1) {
		const line = lines[index - 1] ?? "";
		const trimmed = line.trim();
		if (kind === null) {
			if (/\.task\s*(?:\(.*?\))?\s*\{/u.test(trimmed)) {
				kind = "task";
			} else if (/\.refreshable\s*\{/u.test(trimmed)) {
				kind = "refreshable";
			}
			depth = 0;
			body = "";
		}
		if (kind !== null) {
			depth += braces(line);
			body += `${trimmed}\n`;
			if (depth <= 0) {
				blocks[kind].push(
					body
						.replace(/\.(?:task|refreshable)\s*(?:\(.*?\))?\s*\{/u, "")
						.replace(/\}\s*$/u, "")
						.replaceAll(/\s+/gu, " ")
						.trim(),
				);
				kind = null;
			}
		}
	}
	return blocks.task.some((task) =>
		blocks.refreshable.some((refresh) => {
			if (task.length < 10 || refresh.length < 10) {
				return false;
			}
			if (task.includes(refresh) || refresh.includes(task)) {
				return true;
			}
			const a = new Set(task.split(/\W+/u).filter(Boolean));
			const b = new Set(refresh.split(/\W+/u).filter(Boolean));
			const shared = [...a].filter((word) => b.has(word)).length;
			const union = new Set([...a, ...b]).size;
			return union > 0 && shared / union > 0.8;
		}),
	);
}

/** The deepest nesting of layout containers in a body, and how many sit at its top level. */
function containerShape(lines: readonly string[], start: number, end: number) {
	let stack = 0;
	let nestingDepth = 0;
	let topLevelContainers = 0;
	let relative = 0;
	for (let i = start; i <= end; i += 1) {
		const text = lines[i - 1] ?? "";
		if (CONTAINER.test(text) && !/^\s*\/\//u.test(text)) {
			topLevelContainers += relative === 1 ? 1 : 0;
			stack += 1;
			nestingDepth = Math.max(nestingDepth, stack);
		}
		for (const char of text) {
			relative += char === "{" ? 1 : 0;
			if (char === "}") {
				relative -= 1;
				stack = stack > 0 && relative < stack ? Math.max(0, relative) : stack;
			}
		}
	}
	return { nestingDepth, topLevelContainers };
}

function viewBodies(lines: readonly string[]): ViewBody[] {
	const views: ViewBody[] = [];
	for (const [index, line] of lines.entries()) {
		const name = /struct\s+(?<name>\w+)\s*:\s*(?:.*\bView\b)/u.exec(line)?.groups?.name;
		const at = index + 1;
		if (
			name === undefined ||
			lines.slice(Math.max(0, index - 3), index).some((l) => /@main/u.test(l))
		) {
			continue;
		}
		const offset = lines
			.slice(index, index + 51)
			.findIndex((l) => /var\s+body\s*:\s*some\s+View\s*\{/u.test(l));
		if (offset === -1) {
			continue;
		}
		const bodyStart = at + offset;
		let bodyEnd = lines.length;
		let depth = 0;
		let started = false;
		for (let i = bodyStart; i <= lines.length; i += 1) {
			for (const char of lines[i - 1] ?? "") {
				if (char === "{") {
					depth += 1;
					started = true;
				} else if (char === "}") {
					depth -= 1;
				}
			}
			if (started && depth <= 0) {
				bodyEnd = i;
				break;
			}
		}
		const bodyLines = bodyEnd - bodyStart - 1;
		if (bodyLines < 10) {
			continue;
		}
		const { nestingDepth, topLevelContainers } = containerShape(lines, bodyStart, bodyEnd);
		views.push({
			name,
			line: at,
			bodyStart,
			bodyEnd,
			bodyLines,
			stateProperties: lines.slice(index, bodyStart - 1).filter((l) => /@State\s/u.test(l)).length,
			nestingDepth,
			topLevelContainers,
			sameTaskAndRefresh: sameTaskAndRefresh(lines, bodyStart, bodyEnd),
		});
	}
	return views;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const files = await work.sources((file) => file.language === "swift" && !isTestPath(file.path));
		const leads: ReviewLead[] = [];
		const facts = {
			viewBodies: 0,
			bodiesOver50Lines: 0,
			bodiesOver80Lines: 0,
			bodiesOver120Lines: 0,
			viewsOver5State: 0,
			viewsOver8State: 0,
			deepNesting: 0,
			containerSprawl: 0,
			sameTaskAndRefresh: 0,
		};
		for (const file of files) {
			if (
				!/:\s*View\s*\{/u.test(file.text) ||
				/import\s+XCTest|import\s+Testing/u.test(file.text)
			) {
				continue;
			}
			for (const view of viewBodies(file.text.split("\n"))) {
				facts.viewBodies += 1;
				facts.bodiesOver50Lines += view.bodyLines > 50 ? 1 : 0;
				facts.bodiesOver80Lines += view.bodyLines > 80 ? 1 : 0;
				facts.bodiesOver120Lines += view.bodyLines > 120 ? 1 : 0;
				facts.viewsOver5State += view.stateProperties > 5 ? 1 : 0;
				facts.viewsOver8State += view.stateProperties > 8 ? 1 : 0;
				facts.deepNesting += view.nestingDepth > 5 ? 1 : 0;
				facts.containerSprawl += view.topLevelContainers >= 4 ? 1 : 0;
				facts.sameTaskAndRefresh += view.sameTaskAndRefresh ? 1 : 0;
				if (
					view.bodyLines > 50 ||
					view.stateProperties > 5 ||
					view.nestingDepth > 5 ||
					view.topLevelContainers >= 4 ||
					view.sameTaskAndRefresh
				) {
					leads.push({
						at: { file: file.path, line: view.bodyStart, endLine: view.bodyEnd },
						label: `The body of the changed view ${view.name}.`,
						facts: {
							bodyLines: view.bodyLines,
							stateProperties: view.stateProperties,
							nestingDepth: view.nestingDepth,
							topLevelContainers: view.topLevelContainers,
							sameTaskAndRefresh: view.sameTaskAndRefresh,
						},
					});
				}
			}
		}
		const directions = [
			"Leads are view bodies of changed files with more than 50 lines, more than five @State properties, more than five nested layout containers, four or more top-level containers, or .task and .refreshable blocks with nearly the same code.",
			"The authored program read bodies over 80 and 120 lines and views with over eight and twelve @State properties as stronger signs.",
			"These are counts of text lines and braces. Read whether the body mixes separate parts that a named subview or a shared method would make clearer; a long body of one simple list can be fine.",
		];
		if (facts.viewBodies === 0) {
			directions.push("No changed SwiftUI view body of ten or more lines was found.");
		}
		return ctx.review({ leads: leads.slice(0, 60), facts, directions });
	},
});
