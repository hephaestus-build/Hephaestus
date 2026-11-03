import { definePracticeScript } from "@hephaestus/practice-scripts";
import { scm } from "@hephaestus/practice-scripts/scm";

export const LABELS = {
	"try-discards-error": "A Swift `try?` that turns a thrown error into nil.",
	"empty-catch": "A catch block with an empty body.",
	"catch-with-only-print": "A Swift catch block whose only statement is a print call.",
	"error-ignored-if-let-error": "A Swift `if let error` block with an empty body.",
	"fallback-on-a-failable-conversion":
		"A `??` fallback on a failable conversion, which the criteria ask to classify.",
	"guard-else-return":
		"A Swift guard whose else branch only returns, which the criteria ask to classify.",
	"catch-noop": "A promise `.catch` whose handler has an empty body.",
	"catch-with-only-console": "A catch block whose only statement is a console call.",
	"except-pass": "A Python except clause whose body is only `pass`.",
	"bare-except": "A Python except clause that names no exception type.",
	"except-continue": "A Python except clause whose body is only `continue`.",
	"blank-identifier-assignment":
		"A Go assignment of a call result to the blank identifier, which can drop an error.",
	"empty-if-err-not-nil": "A Go `if err != nil` block with an empty body.",
	"catch-print-stack-trace-only": "A Java catch block whose only statement is printStackTrace.",
	"run-catching-get-or-null": "A Kotlin runCatching block that ends in getOrNull.",
	"ok-discards-err": "A Rust `.ok();` statement that drops the Err value.",
	"let-underscore-call": "A Rust `let _ =` binding of a call result.",
	"empty-if-let-err": "A Rust `if let Err(_)` block with an empty body.",
	"rescue-unused-binding": "A Ruby rescue that binds the error and ends the line.",
	"rescue-end": "A Ruby rescue that ends with no statement.",
};
type ErrorKind = keyof typeof LABELS;

const LANG_PATTERNS: Record<string, [ErrorKind, RegExp][]> = {
	swift: [
		["try-discards-error", /\btry\?\s/u],
		["empty-catch", /\bcatch\s*\{\s*\}/u],
		["catch-with-only-print", /\bcatch\s*\{\s*print\s*\([^)]*\)\s*\}/u],
		["error-ignored-if-let-error", /\bif\s+let\s+error\b[^{]*\{\s*\}/u],
		// Failable conversions and early returns need review; their syntax alone proves no swallowed error.
		[
			"fallback-on-a-failable-conversion",
			/\b(?:Int|Double|Float|Decimal|Bool|URL|UUID)\s*\([^)]*\)\s*\?\?/u,
		],
		["guard-else-return", /\bguard\b[^{]*\belse\s*\{\s*return\s*\}/u],
	],
	typescript: [
		["empty-catch", /\bcatch\s*(?:\([^)]*\))?\s*\{\s*\}/u],
		["catch-noop", /\.catch\s*\(\s*\(\s*[^)]*\)\s*=>\s*\{\s*\}\s*\)/u],
		[
			"catch-with-only-console",
			/\bcatch\s*(?:\([^)]*\))?\s*\{\s*console\.[a-z]+\([^)]*\)\s*;?\s*\}/u,
		],
	],
	javascript: [
		["empty-catch", /\bcatch\s*(?:\([^)]*\))?\s*\{\s*\}/u],
		["catch-noop", /\.catch\s*\(\s*\(\s*[^)]*\)\s*=>\s*\{\s*\}\s*\)/u],
		[
			"catch-with-only-console",
			/\bcatch\s*(?:\([^)]*\))?\s*\{\s*console\.[a-z]+\([^)]*\)\s*;?\s*\}/u,
		],
	],
	python: [
		["except-pass", /\bexcept[^:]*:\s*pass\b/u],
		["bare-except", /^\s*except\s*:/mu],
		["except-continue", /\bexcept[^:]*:\s*continue\b/u],
	],
	go: [
		[
			"blank-identifier-assignment",
			/\b_\s*(?:,\s*[a-zA-Z0-9_]+)?\s*:?=\s*[a-zA-Z_][a-zA-Z0-9_.]*\s*\(/u,
		],
		["empty-if-err-not-nil", /\bif\s+err\s*!=\s*nil\s*\{\s*\}/u],
	],
	java: [
		["empty-catch", /\bcatch\s*\([^)]*\)\s*\{\s*\}/u],
		[
			"catch-print-stack-trace-only",
			/\bcatch\s*\([^)]*\)\s*\{\s*[a-zA-Z0-9_.]*\.printStackTrace\s*\(\s*\)\s*;?\s*\}/u,
		],
	],
	kotlin: [
		["empty-catch", /\bcatch\s*\([^)]*\)\s*\{\s*\}/u],
		["run-catching-get-or-null", /runCatching\s*\{[\s\S]*?\}\s*\.getOrNull\s*\(\s*\)/u],
	],
	rust: [
		["ok-discards-err", /\.ok\s*\(\s*\)\s*;/u],
		["let-underscore-call", /\blet\s+_\s*=\s*[a-zA-Z_][a-zA-Z0-9_.:]*\s*\(/u],
		["empty-if-let-err", /\bif\s+let\s+Err\s*\(\s*_\s*\)[^{]*\{\s*\}/u],
	],
	ruby: [
		["rescue-unused-binding", /\brescue\s*=>\s*[a-z_]+\s*$/mu],
		["rescue-end", /\brescue\b[^;\n]*;\s*end\b/u],
	],
};

const RULES = Object.fromEntries(
	Object.entries(LANG_PATTERNS).map(([language, rows]) => [
		language,
		rows.map(([kind, test]) => ({ kind, label: LABELS[kind], test })),
	]),
);

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request", "scm.file");
		const found = await work.sites(RULES, { tests: true, listed: 40 });
		const { filesScanned, linesSelected } = found;
		const byLang = Object.fromEntries(found.languages);
		const leads = found.sites;
		const directions =
			leads.length > 0
				? [
						`Found ${leads.length} possible error-discard constructs in ${Object.keys(byLang).length} languages. First establish whether each call can fail. For each failure, inspect whether it stays visible.`,
						"A valid fixed URL has no runtime-dependent parse failure. A later empty-value check does not swallow an error already recorded by its handler.",
					]
				: found.noMatch;
		return ctx.review({
			leads: found.leads,
			facts: { errorSwallowCandidates: leads.length, filesScanned, linesSelected, ...byLang },
			directions,
		});
	},
});
