import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { languageOf, scm } from "@hephaestus/practice-scripts/scm";

type PatternRows = [string, RegExp][];

interface PatternTable {
	all: PatternRows;
	byLanguage: Record<string, PatternRows>;
}

const SOURCES: PatternTable = {
	all: [
		["request/req param", /\b(?:request|req)\b\s*[.[]/iu],
		[
			"params/query/body/headers/cookies",
			/\b(?:params|query|queryString|body|headers|cookies)\b\s*[.[]/u,
		],
		["env var", /\b(?:process\.env|os\.environ|System\.getenv|getenv|std::env::var|ENV)\b/u],
		[
			"argv / CLI args",
			/\b(?:argv|sys\.args|os\.Args|process\.argv|CommandLine\.arguments|args\[)\b/u,
		],
		["stdin / scanner read", /\b(?:stdin|readLine|Scanner|BufferedReader|input\s*\(|gets\b)\b/u],
	],
	byLanguage: {
		java: [
			[
				"servlet getParameter/getHeader",
				/\.get(?:Parameter|Header|QueryString|Cookies|InputStream|Reader)\s*\(/u,
			],
			[
				"@RequestParam/@PathVariable/@RequestBody",
				/@(?:RequestParam|PathVariable|RequestBody|RequestHeader|CookieValue)\b/u,
			],
			[
				"file read",
				/\bnew\s+(?:FileReader|FileInputStream)\s*\(|Files\.(?:read|newInputStream)\b/u,
			],
		],
		typescript: [
			["express/koa req", /\breq\.(?:params|query|body|headers|cookies|get)\b/u],
			["fs read", /\bfs\.(?:readFile|readFileSync|createReadStream)\b/u],
			["URL/searchParams", /\b(?:searchParams|URLSearchParams|location\.(?:search|hash|href))\b/u],
		],
		python: [
			[
				"flask/django request",
				/\brequest\.(?:args|form|values|json|GET|POST|data|files|headers|cookies)\b/u,
			],
			["open() read", /\bopen\s*\([^)]*['"]r/u],
		],
		go: [
			["http.Request fields", /\br\.(?:URL|Form|PostForm|Body|Header|Cookie)\b/u],
			["FormValue/Query", /\.(?:FormValue|Query|PathValue)\s*\(/u],
		],
		ruby: [["rails params", /\bparams\[/u]],
		php: [["superglobals", /\$_(?:GET|POST|REQUEST|COOKIE|SERVER|FILES)\b/u]],
		csharp: [["Request fields", /\bRequest\.(?:Query|Form|Headers|Cookies|Body|QueryString)\b/u]],
		swift: [
			["URLSession / response data", /\bURLSession\b|\.dataTask\b|\bdata\s*\(\s*for\s*:/u],
			[
				"request header / URL component",
				/\.value\s*\(\s*forHTTPHeaderField|\bURLComponents\b|\.queryItems\b/u,
			],
			[
				"UserDefaults / FileManager / env",
				/\b(?:UserDefaults\.standard|FileManager\.default|ProcessInfo\.processInfo\.environment)\b/u,
			],
		],
	},
};

const SINKS: PatternTable = {
	all: [
		[
			"raw SQL string-concat",
			/\b(?:SELECT|INSERT|UPDATE|DELETE|WHERE|FROM)\b[^;]*(?:\+|\$\{|%s|f["']|`|\|\||\.\.)/iu,
		],
		["eval", /\beval\s*\(/u],
		[
			"exec / shell",
			/\b(?:exec|execSync|execve|spawn|popen|os\.system|subprocess\.(?:call|run|Popen)|shell_exec)\s*\(|(?<![.\w])system\s*\(/u,
		],
		[
			"deserialize",
			/\b(?:pickle\.loads|yaml\.load\b|Marshal\.load|unserialize|JSON\.parse|deserialize)\s*\(/iu,
		],
		// Template compilation does not render; SwiftUI renderingMode(.template) is an image mode.
		[
			"template render",
			/\b(?:render(?:_template(?:_string)?)?|Mustache\.render|ejs\.render)\s*\(/u,
		],
		[
			"path join with input",
			/\b(?:path\.join|os\.path\.join|filepath\.Join|Paths\.get|File\s*\()/u,
		],
	],
	byLanguage: {
		java: [
			["Runtime.exec / ProcessBuilder", /\b(?:Runtime\.getRuntime\(\)\.exec|ProcessBuilder)\b/u],
			[
				"Statement.execute (no prepare)",
				/\b(?:createStatement|Statement)\b[^;]*\.(?:execute|executeQuery|executeUpdate)\b/u,
			],
			["ObjectInputStream", /\bObjectInputStream\b/u],
		],
		typescript: [
			[
				"innerHTML / dangerouslySetInnerHTML",
				/\b(?:innerHTML|outerHTML|dangerouslySetInnerHTML|insertAdjacentHTML|document\.write)\b/u,
			],
			["new Function", /\bnew\s+Function\s*\(/u],
		],
		python: [
			["cursor.execute concat", /\bcursor\.execute\b/u],
			["os.system / subprocess shell=True", /shell\s*=\s*True/u],
		],
		php: [["echo/print to HTML", /\b(?:echo|print)\b/u]],
		csharp: [
			["SqlCommand concat", /\bnew\s+SqlCommand\b/u],
			["Html.Raw", /\bHtml\.Raw\s*\(/u],
		],
		swift: [
			[
				"WKWebView loadHTMLString / evaluateJavaScript",
				/\b(?:loadHTMLString|evaluateJavaScript)\s*\(/u,
			],
			["sqlite3 exec/prepare", /\bsqlite3_(?:exec|prepare(?:_v2)?)\s*\(/u],
			["Process / shell launch", /\bProcess\s*\(\)|\.launchPath\b|\blaunch\s*\(\)/u],
			["NSExpression / String(format:)", /\bNSExpression\b|\bString\s*\(\s*format\s*:/u],
		],
	},
};

const WINDOW = 25;

export const LABELS = {
	"source-near-sink": `An added line with a dangerous operation (a sink) and an untrusted input (a source) at most ${String(WINDOW)} added lines away.`,
};

function rowsLanguage(lang: string | null): string | null {
	return lang === "javascript" ? "typescript" : lang;
}

function rowsFor(table: PatternTable, lang: string | null): PatternRows {
	return [...table.all, ...(lang === null ? [] : (table.byLanguage[lang] ?? []))];
}

function firstMatch(rows: PatternRows, content: string): string | null {
	for (const [label, re] of rows) {
		if (re.test(content)) {
			return label;
		}
	}
	return null;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const change = work.changedFiles();
		const leads: ReviewLead[] = [];
		let flowCount = 0;
		let linesAdded = 0;

		for (const [path, df] of change) {
			const lang = languageOf(path);
			const sourceRows = rowsFor(SOURCES, rowsLanguage(lang));
			const sinkRows = rowsFor(SINKS, rowsLanguage(lang));

			const srcLines: { line: number; label: string }[] = [];
			const sinkLines: { line: number; label: string }[] = [];
			for (const [line, content] of df.addedLines) {
				if (isCommentLine(content, lang ?? "")) {
					continue;
				}
				linesAdded += 1;
				const s = firstMatch(sourceRows, content);
				if (s !== null) {
					srcLines.push({ line, label: s });
				}
				const k = firstMatch(sinkRows, content);
				if (k !== null) {
					sinkLines.push({ line, label: k });
				}
			}
			if (srcLines.length === 0 || sinkLines.length === 0) {
				continue;
			}

			for (const sink of sinkLines) {
				const near = srcLines.find((s) => Math.abs(s.line - sink.line) <= WINDOW);
				if (!near) {
					continue;
				}
				flowCount += 1;
				leads.push({
					at: { change: path, line: sink.line },
					label: LABELS["source-near-sink"],
					facts: {
						sourceLine: near.line,
						sourceToken: near.label,
						sinkToken: sink.label,
						lineDistance: Math.abs(near.line - sink.line),
					},
				});
			}
		}

		const directions =
			flowCount > 0
				? [
						`Found ${flowCount} added line(s) where an untrusted-input source (request/params/env/file/stdin/argv) and a dangerous sink (raw SQL concat, exec/eval/system, innerHTML, path join, template render, deserialize) co-occur within ${WINDOW} lines.`,
						"Trace whether the source reaches the sink without validation/escaping/parameterization, and confirm before deciding.",
					]
				: noMatchDirections(linesAdded, change.size);

		return ctx.review({
			leads: leads.slice(0, 40),
			facts: { sourceSinkFlows: flowCount, filesScanned: change.size, linesAdded },
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

function noMatchDirections(linesAdded: number, filesScanned: number): string[] {
	return [
		`Scanned ${String(linesAdded)} added lines in ${String(filesScanned)} files for this practice's line patterns; none matched.`,
		"A pattern sees one line: what spans lines or has no keyword, such as an early return, a discarded result or a missing else, is yours to enumerate from the diff.",
	];
}
