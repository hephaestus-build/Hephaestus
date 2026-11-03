import { definePracticeScript, listWithin, type Facts } from "@hephaestus/practice-scripts";
import { isTestPath, scm, type Site } from "@hephaestus/practice-scripts/scm";

const DIRECTION_CHARS = 300;

export const LABELS = {
	"edge-construct":
		"A selected line of code with a construct that can fail or mislead on an edge value, with its language and constructs.",
};

const COMMON: [string, RegExp][] = [
	[
		"subscript or slice",
		/[A-Za-z0-9_)\]]\[(?![^\]]*\bdefault:)[^\]]+\](?!\s*(?:\?\?|as\?|=(?!=)))/u,
	],
	["division or remainder by a name", /[A-Za-z0-9_)\]]\s*[/%](?![/*=])\s*[A-Za-z_(]/u],
	[
		"extreme sentinel",
		/\b(?:U?Int\d*|Double|Float|CGFloat)\.(?:max|min|infinity|greatestFiniteMagnitude)\b|\b(?:Integer|Long|Double|Float)\.(?:MAX|MIN)_VALUE\b|\bNumber\.(?:MAX|MIN)_(?:SAFE_INTEGER|VALUE)\b|\bsys\.maxsize\b|\bmath\.inf\b/u,
	],
];

const LANG_PATTERNS: Record<string, [string, RegExp][]> = {
	swift: [["payload decode", /(?<![Cc]ontainer)\.decode\s*\(/u]],
	typescript: [
		["parseInt/parseFloat", /\bparse(?:Int|Float)\s*\(/u],
		["Number(...) parse", /\bNumber\s*\(/u],
		["JSON.parse", /\bJSON\.parse\s*\(/u],
	],
	javascript: [
		["parseInt/parseFloat", /\bparse(?:Int|Float)\s*\(/u],
		["Number(...) parse", /\bNumber\s*\(/u],
		["JSON.parse", /\bJSON\.parse\s*\(/u],
	],
	python: [
		["int()/float() parse", /\b(?:int|float)\s*\(/u],
		["json.loads", /\bjson\.loads\s*\(/u],
		[".pop()", /\.pop\s*\(/u],
		["next(...)", /\bnext\s*\(/u],
	],
	go: [
		["strconv parse", /\bstrconv\.(?:Atoi|Parse[A-Za-z]+)\s*\(/u],
		["json.Unmarshal", /\bjson\.Unmarshal\s*\(/u],
	],
	java: [
		[".get(i) access", /\.get\s*\(\s*[A-Za-z_]/u],
		["Integer/Long/Double.parse", /\b(?:Integer|Long|Double|Float)\.parse[A-Za-z]+\s*\(/u],
		["valueOf parse", /\b(?:Integer|Long|Double|Float)\.valueOf\s*\(/u],
		[".charAt/.substring", /\.(?:charAt|substring)\s*\(/u],
	],
	kotlin: [
		["toInt/toDouble parse", /\.to(?:Int|Long|Double|Float)\s*\(\s*\)/u],
		[".first()/.last()", /\.(?:first|last)\s*\(\s*\)/u],
	],
	rust: [],
	ruby: [
		["JSON.parse", /\bJSON\.parse\s*\(/u],
		[".fetch", /\.fetch\s*\(/u],
	],
	c: [
		["atoi/strtol parse", /\b(?:atoi|atol|atof|strtol|strtod)\s*\(/u],
		["pointer deref *", /(?:^|[^\w)])\*[A-Za-z_]/u],
	],
	csharp: [
		[
			"int.Parse/Convert",
			/\b(?:int|long|double|float|decimal|Int32|Int64|Convert)\.(?:Parse|To[A-Za-z]+)\s*\(/u,
		],
		["JsonSerializer.Deserialize", /\.Deserialize\s*\(/u],
		[".First()/.Last()/.Single()", /\.(?:First|Last|Single)\s*\(\s*\)/u],
	],
};

const REQUEST_INPUT: Record<string, RegExp> = {
	java: /@(?:RequestParam|PathVariable|RequestBody|RequestHeader)\b/u,
	kotlin: /@(?:RequestParam|PathVariable|RequestBody|RequestHeader)\b/u,
	typescript: /\breq(?:uest)?\.(?:params|query|body|headers)\b/u,
	javascript: /\breq(?:uest)?\.(?:params|query|body|headers)\b/u,
	python: /\brequest\.(?:args|form|json|GET|POST|query_params|data)\b/u,
	go: /\br\.(?:URL\.Query|FormValue|PostFormValue|PathValue)\b/u,
	swift: /\breq\.(?:parameters|query|content)\b/u,
};

function factKey(label: string): string {
	const words = label.match(/[A-Za-z0-9]+/gu) ?? [];
	return words
		.map((word, index) =>
			index === 0 ? word.toLowerCase() : `${word.charAt(0).toUpperCase()}${word.slice(1)}`,
		)
		.join("");
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request", "scm.file");
		const sites: Site[] = [];
		const byKind = new Map<string, number>();
		let filesScanned = 0;
		let linesScanned = 0;

		for (const file of await work.sources(
			(source) =>
				source.language !== null &&
				LANG_PATTERNS[source.language] !== undefined &&
				!isTestPath(source.path),
		)) {
			const lang = file.language;
			const own = lang === null ? undefined : LANG_PATTERNS[lang];
			if (lang === null || own === undefined) {
				continue;
			}
			const request = REQUEST_INPUT[lang];
			const patterns: [string, RegExp][] =
				request === undefined
					? [...COMMON, ...own]
					: [["request input", request], ...COMMON, ...own];
			filesScanned += 1;
			linesScanned +=
				ctx.scope.kind === "SCAN"
					? file.text.split("\n").length
					: (work.changedFiles().get(file.path)?.addedLines.size ?? 0);
			for (const [kind, test] of patterns) {
				const found = file.sites([{ kind, label: LABELS["edge-construct"], test }]);
				byKind.set(kind, (byKind.get(kind) ?? 0) + found.length);
				sites.push(...found);
			}
		}
		const leads = sites
			.toSorted((a, b) => (a.path < b.path ? -1 : Number(a.path > b.path)) || a.line - b.line)
			.map((site) => ({ ...site.lead, facts: { ...site.facts, constructs: site.kind } }));
		const counts: Facts = {};
		for (const [label, count] of byKind) {
			counts[factKey(label)] = count;
		}
		const kinds = [...byKind].map(([kind, count]) => `${String(count)} ${kind}`);
		const opening = `${String(leads.length)} selected line(s) hold a construct that can fail or mislead on an edge value`;
		const directions =
			leads.length > 0
				? [
						`${opening} (${listWithin(kinds, DIRECTION_CHARS - opening.length - 3)}).`,
						"String-literal text, comments and test files were not scanned; lookups and parses whose result the language makes optional, and crash operators, are not listed.",
						"A listed line marks where a consuming site may be, not a problem: whether its value comes from outside the local logic, and what already handles its bad case, is read from the code.",
					]
				: [];

		return ctx.review({
			leads: leads.slice(0, 40),
			facts: {
				edgeConstructLines: leads.length,
				filesScanned,
				linesScanned,
				...counts,
			},
			directions,
		});
	},
});
