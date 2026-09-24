// Precompute HINTS for validates-and-escapes-untrusted-input: surface ADDED lines where an untrusted-INPUT
// token (a SOURCE) and a dangerous OPERATION token (a SINK) co-occur in the same file within a small line
// window — i.e. the taint flow a reviewer is most likely to miss by eye. CANDIDATES only: the LLM traces
// whether the source actually reaches the sink unvalidated/unescaped and decides. General by design: a
// per-language SOURCE table + a SINK table keyed off the file extension. Adding a language = adding rows,
// no engine change. The taint flow spans lines, so we pair each source to nearby sinks and hand the LLM
// the exact span to trace.
import { isCommentLine } from "../lib/declarations.ts";
import { languageOf } from "../lib/languages.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

/** A label -> pattern table row. The label is what a hint reports, so it is rendered verbatim. */
type PatternRows = [string, RegExp][];

/**
 * `all` is checked for every file whatever its language; `byLanguage` refines it for the languages
 * that have their own surfaces. Splitting the two apart makes "there are always cross-language rows"
 * a fact the type carries, instead of one key of a string-indexed record that reads as optional.
 */
interface PatternTable {
	all: PatternRows;
	byLanguage: Record<string, PatternRows>;
}

// Sources of untrusted input (request/CLI/env/file/stdin). Cross-language patterns live under "all" and are
// checked for every file; language-specific rows refine the common web/runtime surfaces.
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

// Sinks: dangerous operations that must receive validated/escaped input (SQL, command/eval, markup, path,
// templating, deserialization). Cross-language rows under "all"; language rows refine.
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
		["template render", /\b(?:render(?:_template)?|template|Mustache|Handlebars|Jinja|ejs)\b/iu],
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

// Sources and sinks co-occurring within this many added lines are surfaced as a candidate flow.
const WINDOW = 25;

/** JavaScript shares TypeScript's surfaces; the lib names the two apart. */
function rowsLanguage(lang: string | null): string | null {
	return lang === "javascript" ? "typescript" : lang;
}

/** Cross-language rows first, then the rows for this file's language (none when it is unrecognised). */
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

export default function validatesAndEscapesUntrustedInput(
	_repo: string,
	diffFiles: Map<string, DiffFile>,
	_m: PullRequestMetadata,
) {
	const hints: Hint[] = [];
	let flowCount = 0;
	let linesAdded = 0;

	for (const [path, df] of diffFiles) {
		const lang = languageOf(path);
		const sourceRows = rowsFor(SOURCES, rowsLanguage(lang));
		const sinkRows = rowsFor(SINKS, rowsLanguage(lang));

		// Collect source / sink positions on ADDED lines only.
		const srcLines: { line: number; label: string; content: string }[] = [];
		const sinkLines: { line: number; label: string; content: string }[] = [];
		for (const [line, content] of df.addedLines) {
			if (isCommentLine(content, lang ?? "")) {
				continue;
			}
			linesAdded += 1;
			const s = firstMatch(sourceRows, content);
			if (s !== null) {
				srcLines.push({ line, label: s, content });
			}
			const k = firstMatch(sinkRows, content);
			if (k !== null) {
				sinkLines.push({ line, label: k, content });
			}
		}
		if (srcLines.length === 0 || sinkLines.length === 0) {
			continue;
		}

		// Surface each sink that has a source within WINDOW lines — that pairing is the flow to trace.
		for (const sink of sinkLines) {
			const near = srcLines.find((s) => Math.abs(s.line - sink.line) <= WINDOW);
			if (!near) {
				continue;
			}
			flowCount += 1;
			hints.push({
				file: path,
				line: sink.line,
				pattern: `source→sink: ${near.label} → ${sink.label}`,
				context: sink.content.trim().slice(0, 160),
				inDiff: true,
				flags: {
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
					`Found ${flowCount} added line(s) where an untrusted-input source (request/params/env/file/stdin/argv) and a dangerous sink (raw SQL concat, exec/eval/system, innerHTML, path join, template render, deserialize) co-occur within ${WINDOW} lines — trace whether the source reaches the sink without validation/escaping/parameterization, and confirm before deciding.`,
				]
			: [];

	return {
		hints: hints.slice(0, 40),
		metrics: { sourceSinkFlows: flowCount, filesScanned: diffFiles.size, linesAdded },
		directions,
	};
}
