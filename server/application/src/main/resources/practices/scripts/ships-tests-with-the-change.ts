import { definePracticeScript, listWithin } from "@hephaestus/practice-scripts";
import { isTestPath, languageOf, scm } from "@hephaestus/practice-scripts/scm";

const DIRECTION_CHARS = 300;

const DECLARED_NAME =
	/\b(?:func|class|struct|enum|protocol|interface|type|record|object|fun|def|actor|typealias|namespace|trait)\s+(?<name>[A-Za-z_][A-Za-z0-9_]*)/u;
const METHOD_NAME = /(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*\(/u;
const BINDING = /\b(?:const|let|var|val)\b/u;
const NOT_NAMES = new Set([
	"if",
	"for",
	"while",
	"switch",
	"catch",
	"return",
	"new",
	"super",
	"this",
	"function",
]);
const MAX_NAMES = 8;
/** A line that declares rather than uses: a declaration keyword, or a modifier before a method. */
const DECLARATION_LINE =
	/\b(?:func|class|struct|enum|protocol|interface|type|record|object|fun|def|actor|trait|public|protected|private|internal|export|override)\b/u;

/** The name a declaration line declares; a binding or a call is no declaration. */
function declaredName(line: string): string | null {
	const keyword = DECLARED_NAME.exec(line)?.groups?.name;
	if (keyword !== undefined) {
		return keyword.length >= 3 ? keyword : null;
	}
	if (BINDING.test(line)) {
		return null;
	}
	const method = METHOD_NAME.exec(line)?.groups?.name;
	return method !== undefined && method.length >= 3 && !NOT_NAMES.has(method) ? method : null;
}

/**
 * A pattern that admission can read: words joined by `|`, each bounded by `\b`. A claim that no test
 * elsewhere exercises a symbol is backed only by a search whose plain pattern names it.
 */
function plainPattern(names: readonly string[], max: number): string {
	let pattern = "";
	for (const name of names.slice(0, MAX_NAMES)) {
		const next = pattern === "" ? `\\b${name}\\b` : `${pattern}|\\b${name}\\b`;
		if (next.length > max) {
			break;
		}
		pattern = next;
	}
	return pattern;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const change = work.changedFiles();
		const source = [...change.keys()].filter((path) => languageOf(path) !== null);
		const tests = source.filter((path) => isTestPath(path));
		const production = source.filter((path) => !isTestPath(path));
		const names = new Set<string>();
		for (const path of production) {
			for (const content of change.get(path)?.addedLines.values() ?? []) {
				const name = DECLARATION_LINE.test(content) ? declaredName(content) : null;
				if (name !== null) {
					names.add(name);
				}
			}
		}

		const directions: string[] = [];
		if (source.length > 0) {
			const counts = `The diff changes ${String(production.length)} production source file(s) and ${String(tests.length)} test file(s), by path`;
			directions.push(
				tests.length > 0
					? `${counts}: ${listWithin(tests, DIRECTION_CHARS - counts.length - 3)}.`
					: `${counts}.`,
				"Whether the repository already has a test target does not decide this practice: judge the changed behaviour against the tests in this diff. A statement about the tests in this change rests on the diff: cite its file list, and do not name the checkout as searched for it.",
			);
		}
		const pattern = plainPattern([...names], 280);
		if (pattern !== "") {
			directions.push(
				"To claim that no test elsewhere in the repository exercises the changed code, search the whole checkout with the grep tool, not a shell command, using the next direction's pattern, and name these symbols in the claim and in lookedFor.",
				pattern,
			);
		}
		return ctx.review({
			leads: [],
			facts: {
				diffProductionFiles: production.length,
				diffTestFiles: tests.length,
			},
			directions,
		});
	},
});
