import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

import { parseTree, getNodeValue, type Node as JsonNode, type ParseError } from "jsonc-parser";
import { parseSync } from "vite-plus";
import { parse as parseYaml } from "yaml";

import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { asArray, asRecord, asString, asStringArray, isRecord, parseJson } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

export const baselinePath = "tools/band-aids/baseline.json";
const root = path.resolve(import.meta.dirname, "..");

// These are syntax counts. A handled failure and a nullable value need contract review, not a text verdict.
const rules = [
	["java-suppression", /@\s*(?:[\w.]+\.)?Suppress(?:Warnings|FBWarnings)\s*\([^)]*\)/gu],
	[
		"lint-suppression",
		/(?:\/\/|\/\*|\{\/\*)\s*(?:eslint|oxlint)-disable(?:-next-line|-line)?\b|(?:\/\/|\/\*)\s*biome-ignore\b/gu,
	],
	["typescript-suppression", /(?:\/\/|\/\*\*?)\s*@ts-(?:ignore|expect-error|nocheck)\b/gu],
	[
		"format-suppression",
		/(?:\/\/|\/\*|<!--|#)\s*(?:prettier|oxfmt)-(?:ignore(?:-start)?|disable)\b/gu,
	],
	[
		"prose-suppression",
		/<!--\s*(?:vale\s+(?:off|[\w.]+\s*=\s*NO)|markdownlint-disable(?:-next-line|-line)?)(?=\s|>)/giu,
	],
	[
		"static-analysis-suppression",
		/\/\/\s*(?:NOPMD|NOSONAR)\b|CHECKSTYLE\s*:\s*OFF\b|<suppress(?:ion)?\b|violationSuppress(?:Regex|XPath)/gu,
	],
	[
		"architecture-exception",
		/\b(?:FreezingArchRule|(?<!\.)freeze\s*\(|ignoreDependenc(?:y|ies)\s*\(|ignoreViolations\s*\()|ApplicationModules\.of\s*\([^,;]*,[^;]*;/gu,
	],
	[
		"test-skip",
		/@(?:[\w.]+\.)?(?:Disabled\w*|Enabled\w*)\b|\b(?:Assumptions\.)?(?:assume(?:True|False|That)|assumingThat)\s*\(|\bAssumptions\.abort\s*\(|\b(?:test|it|describe|suite|t)(?:\.(?!skip\b|fixme\b|todo\b|fail\b|fails\b|runIf\b)\w+)*\.(?:skip(?:If)?|fixme|todo|fails?|runIf)\b|\bctx\.skip\b|\bx(?:it|test|describe)\s*\(/gu,
	],
	["test-retry", /@RetryingTest\b|\bretr(?:y|ies)\s*:\s*(?!false\b|0\b)\S[^,}\n]*/gu],
	[
		"test-sleep",
		/\b(?:Thread\.sleep|TimeUnit\.\w+\.sleep|sleep|delay|(?<!test\.)setTimeout|waitForTimeout)\s*\(/gu,
	],
	["test-timeout", /@Timeout\b|\bsetDefaultTimeout\s*\(|\btest\.(?:setTimeout|slow)\s*\(/gu],
	["test-tier-exclusion", /\bexclude(?:Tags|TestsMatching)\s*\(/gu],
	["static-analysis-exclusion", /\bexcludedPaths\.set\s*\(|\bexcludeFilter\b/gu],
	["build-exclusion", /\bexclude\s*\(|\bexcludes\s*=|\bclassDirectories\.setFrom\s*\(/gu],
	[
		"ignored-catch",
		/\bcatch\s*(?:\([^)]*\))?\s*\{\s*(?:return\s*(?:undefined|null|false|void\s+0)?;?\s*)?\}|\.catch\s*\(\s*(?:\([^)]*\)|[\w$]+)\s*=>\s*(?:undefined|null|false|void\s+0|\[\s*\]|\{\s*\})\s*\)|\.catch\s*\(\s*(?:noop|ignoreError)\s*\)|\bcatch\s*(?:\([^)]*\))?\s*\{\s*(?:log|logger|console)\.(?:trace|debug|info|warn|error)\([^;]*;\s*(?:continue;\s*)?\}/gu,
	],
	[
		"failure-bypass",
		/\|\|\s*(?:true\b|:(?=\s|$)|exit\s+0\b|(?:echo|printf)\b)|\bset\s+(?:\+[a-z]*e[a-z]*\b|\+o\s+(?:errexit|pipefail)\b)|continue-on-error\s*:\s*(?:true|\$\{\{)/gu,
	],
	["debt-marker", /\b(?:TODO|FIXME|HACK|XXX|workaround|temporary|for now|until)\b/giu],
] as const;
const policyCategories = [
	"security-exception",
	"release-age-exception",
	"lint-configuration",
	"prose-configuration",
	"format-configuration",
	"typescript-configuration",
] as const;
export type Category = (typeof rules)[number][0] | (typeof policyCategories)[number];
export interface Finding {
	file: string;
	line: number;
	category: Category;
	source: string;
	context: string;
}
export interface Allowance {
	file: string;
	category: Category;
	source: string;
	context: string;
	count: number;
	owner: string;
	reason: string;
}
const categories = [...rules.map(([category]) => category), ...policyCategories];

function isConfiguration(file: string): boolean {
	return /(?:^|\/)(?:\.oxlintrc\.json|oxlint[^/]*\.jsonc|tsconfig[^/]*\.json|\.oxfmtrc\.json|\.markdownlint-cli2\.jsonc|\.vale\.ini)$/u.test(
		file,
	);
}

export function isOwnedSource(file: string): boolean {
	return (
		(/\.(?:java|[cm]?[jt]sx?|kts|sh|ya?ml|xml|html|css|ftl|mdx?)$/u.test(file) ||
			/(?:^|\/)Dockerfile(?:\.[^/]+)?$/u.test(file) ||
			file === "security/vulnerability-policy.json" ||
			isConfiguration(file)) &&
		!/^(?:webapp|extension)\/src\/api\/|^webapp\/src\/routeTree\.gen\.ts$|^webapp\/(?:\.storybook\/)?public\/mockServiceWorker\.js$/u.test(
			file,
		) &&
		!file.startsWith(".changeset/pre/") &&
		!file.endsWith("CHANGELOG.md")
	);
}

interface TestControl {
	start: number;
	end: number;
	value: string;
	category: Category;
}

const controlNames = [
	["retry", "test-retry"],
	["retries", "test-retry"],
	["skip", "test-skip"],
	["timeout", "test-timeout"],
	["testTimeout", "test-timeout"],
	["hookTimeout", "test-timeout"],
] as const;
const frameworkCall = (name: string): boolean =>
	(/^(?:it|test|describe|suite)\b/u.test(name) && !/\.(?:each|for)$/u.test(name)) ||
	name === "vi.setConfig";

function testControl(
	node: Record<string, unknown>,
	caller: string | undefined,
	file: string,
	content: string,
): TestControl | undefined {
	if (node.type !== "Property" || !isRecord(node.key) || !isRecord(node.value)) {
		return undefined;
	}
	const { key } = node;
	const category = controlNames.find(([name]) => name === (key.name ?? key.value))?.[1];
	const framework =
		/(?:vite|vitest|playwright)\.config/u.test(file) ||
		(caller !== undefined && frameworkCall(caller));
	const setting = node.value;
	if (category === undefined || !framework || setting.type === "ObjectExpression") {
		return undefined;
	}
	if (category !== "test-timeout" && (setting.value === false || setting.value === 0)) {
		return undefined;
	}
	if (
		typeof node.key.start !== "number" ||
		typeof node.end !== "number" ||
		typeof setting.start !== "number" ||
		typeof setting.end !== "number"
	) {
		throw new Error(`${file}: test setting has no source range`);
	}
	return {
		category,
		start: node.key.start,
		end: node.end,
		value: content.slice(setting.start, setting.end),
	};
}

/**
 * Local names that `node:test` binds. Its `test(name, options, callback)` takes a callback where
 * Vitest can take a timeout in that position; the import distinguishes these APIs without types.
 */
function nodeTestBindings(program: Record<string, unknown>, file: string): ReadonlySet<string> {
	const names = new Set<string>();
	for (const statement of asArray(program.body, `${file} program body`)) {
		if (
			isRecord(statement) &&
			statement.type === "ImportDeclaration" &&
			isRecord(statement.source) &&
			statement.source.value === "node:test"
		) {
			for (const specifier of asArray(statement.specifiers, `${file} import specifiers`)) {
				if (isRecord(specifier) && isRecord(specifier.local)) {
					names.add(asString(specifier.local.name, `${file} import binding`));
				}
			}
		}
	}
	return names;
}

const wrapperTypes = new Set([
	"ParenthesizedExpression",
	"TSAsExpression",
	"TSSatisfiesExpression",
	"TSNonNullExpression",
]);
// An options object has its own property path; a function is the test body, not a timeout.
const notTimeoutTypes = new Set([
	"ObjectExpression",
	"ArrowFunctionExpression",
	"FunctionExpression",
	"SpreadElement",
]);

function positionalTimeout(
	node: Record<string, unknown>,
	caller: string | undefined,
	content: string,
	nodeTest: ReadonlySet<string>,
): TestControl | undefined {
	if (
		node.type !== "CallExpression" ||
		caller === undefined ||
		!frameworkCall(caller) ||
		nodeTest.has(/^[\w$]*/u.exec(caller)?.[0] ?? "") ||
		!Array.isArray(node.arguments)
	) {
		return undefined;
	}
	const options: unknown = node.arguments[1];
	const argument: unknown = node.arguments[2];
	let inner = argument;
	while (isRecord(inner) && wrapperTypes.has(String(inner.type))) {
		inner = inner.expression;
	}
	if (
		!isRecord(argument) ||
		!isRecord(inner) ||
		notTimeoutTypes.has(String(inner.type)) ||
		(inner.type === "Identifier" && inner.name === "undefined") ||
		(isRecord(options) && options.type === "ObjectExpression")
	) {
		return undefined;
	}
	if (typeof argument.start !== "number" || typeof argument.end !== "number") {
		throw new Error("test timeout has no source range");
	}
	return {
		category: "test-timeout",
		start: argument.start,
		end: argument.end,
		value: content.slice(argument.start, argument.end),
	};
}

interface SourceViews {
	code: string;
	comments: string;
	controls: TestControl[];
}

function blank(source: string): string {
	return source.replaceAll(/[^\r\n]/gu, " ");
}

function isTextToken(node: Record<string, unknown>): boolean {
	return (
		(node.type === "Literal" && (typeof node.value === "string" || node.regex !== undefined)) ||
		node.type === "TemplateElement" ||
		node.type === "JSXText"
	);
}

function scriptExclusions(
	node: Record<string, unknown>,
	file: string,
	content: string,
): TestControl[] {
	const controls: TestControl[] = [];
	if (
		/(?:vite|vitest|playwright)\.config/u.test(file) &&
		node.type === "Property" &&
		isRecord(node.key) &&
		isRecord(node.value) &&
		["exclude", "ignorePatterns"].includes(String(node.key.name ?? node.key.value)) &&
		Array.isArray(node.value.elements)
	) {
		for (const element of node.value.elements) {
			if (
				!isRecord(element) ||
				typeof element.start !== "number" ||
				typeof element.end !== "number"
			) {
				throw new Error(`${file}: exclusion has no source range`);
			}
			controls.push({
				category:
					(node.key.name ?? node.key.value) === "exclude"
						? "test-tier-exclusion"
						: "format-configuration",
				start: element.start,
				end: element.end,
				value: content.slice(element.start, element.end),
			});
		}
	}
	return controls;
}

/** Oxc owns JS/TS grammar, including JSX text and nested template expressions. */
function scriptViews(file: string, content: string): SourceViews {
	const parsed = parseSync(file, content);
	if (parsed.errors.length > 0) {
		throw new Error(
			`${file}: cannot parse source: ${parsed.errors.map((error) => error.message).join("; ")}`,
		);
	}
	const code = content.split("");
	const comments = blank(content).split("");
	const controls: TestControl[] = [];
	const nodeTest = nodeTestBindings(asRecord(parsed.program, `${file} program`), file);
	const mask = (start: number, end: number): void => {
		for (let i = start; i < end; i += 1) {
			const character = content[i];
			code[i] = character === "\n" || character === "\r" ? character : " ";
		}
	};
	for (const entry of parsed.comments) {
		mask(entry.start, entry.end);
		for (const [index, character] of content.slice(entry.start, entry.end).split("").entries()) {
			comments[entry.start + index] = character;
		}
	}
	function visit(value: unknown, owner?: string): void {
		if (Array.isArray(value)) {
			for (const child of value) {
				visit(child, owner);
			}
		} else if (isRecord(value)) {
			let caller = owner;
			if (value.type === "CallExpression" || value.type === "NewExpression") {
				const callee = asRecord(value.callee, "callee");
				if (typeof callee.start !== "number" || typeof callee.end !== "number") {
					throw new Error(`${file}: callee has no source range`);
				}
				caller = content.slice(callee.start, callee.end);
			} else if (
				value.type === "ArrowFunctionExpression" ||
				value.type === "FunctionExpression" ||
				value.type === "FunctionDeclaration"
			) {
				caller = undefined;
			}
			controls.push(...scriptExclusions(value, file, content));
			const control = testControl(value, caller, file, content);
			if (control !== undefined) {
				controls.push(control);
			}
			const timeout = positionalTimeout(value, caller, content, nodeTest);
			if (timeout !== undefined) {
				controls.push(timeout);
			}
			if (isTextToken(value)) {
				if (typeof value.start !== "number" || typeof value.end !== "number") {
					throw new Error(`${file}: parser returned a literal without a source range`);
				}
				mask(value.start, value.end);
			}
			for (const child of Object.values(value)) {
				visit(child, caller);
			}
		}
	}
	visit(parsed.program);
	return { code: code.join(""), comments: comments.join(""), controls };
}

/** Java/Kotlin literals have no JSX or JavaScript regular-expression grammar. */
function jvmViews(content: string): { code: string; comments: string } {
	const token = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/gu;
	const comments = blank(content).split("");
	const code = content.replaceAll(token, (text: string, at: number) => {
		if (text.startsWith("//") || text.startsWith("/*")) {
			for (let i = 0; i < text.length; i += 1) {
				comments[at + i] = text[i] ?? " ";
			}
		}
		return blank(text);
	});
	return { code, comments: comments.join("") };
}

function sourceViews(file: string, content: string): SourceViews {
	if (/\.[cm]?[jt]sx?$/u.test(file)) {
		return scriptViews(file, content);
	}
	if (/\.(?:java|kts|jsonc?|css)$/u.test(file)) {
		return { ...jvmViews(content), controls: [] };
	}
	if (/\.(?:md|mdx|xml|html)$/u.test(file)) {
		return {
			controls: [],
			code: content,
			comments: content.replaceAll(/<!--[\s\S]*?-->|[^<]+|</gu, (text) =>
				text.startsWith("<!--") ? text : blank(text),
			),
		};
	}
	return {
		controls: [],
		code: content,
		comments: content
			.split("\n")
			.map((line) => (/^\s*#/u.test(line) ? line : blank(line)))
			.join("\n"),
	};
}

function disabledSeverity(value: unknown): boolean {
	return (
		value === "allow" ||
		value === "off" ||
		value === "warn" ||
		value === 0 ||
		value === 1 ||
		value === false
	);
}

function configurationCategory(file: string): Category {
	if (/tsconfig/u.test(file)) {
		return "typescript-configuration";
	}
	if (/oxfmt/u.test(file)) {
		return "format-configuration";
	}
	if (/vale|markdownlint/u.test(file)) {
		return "prose-configuration";
	}
	return "lint-configuration";
}

const weakeningOptions = new Set([
	"strict",
	"strictNullChecks",
	"strictFunctionTypes",
	"noImplicitAny",
	"noImplicitReturns",
	"noUncheckedIndexedAccess",
	"noUnusedLocals",
	"noUnusedParameters",
	"checkJs",
	"reportUnusedDisableDirectives",
]);

function weakensConfiguration(node: JsonNode, parts: string[], value: unknown): boolean {
	const key = parts.at(-1) ?? "";
	const parent = parts.at(-2) ?? "";
	const severity: unknown = node.type === "array" ? node.children?.[0]?.value : value;
	const ruleDisabled =
		(parent === "rules" ||
			parent === "categories" ||
			parent === "config" ||
			/^MD\d+$/u.test(key)) &&
		disabledSeverity(severity);
	const optionDisabled =
		(weakeningOptions.has(key) && value === false) ||
		(["skipLibCheck", "allowUnreachableCode", "allowUnusedLabels"].includes(key) && value === true);
	return ruleDisabled || optionDisabled;
}

function configurationFindings(file: string, content: string): Finding[] {
	const findings: Finding[] = [];
	const kind = configurationCategory(file);
	const add = (source: string, context: string, offset: number): void => {
		findings.push({
			file,
			line: content.slice(0, offset).split("\n").length,
			category: kind,
			source,
			context,
		});
	};
	if (file.endsWith(".ini")) {
		let section = "";
		let offset = 0;
		for (const line of content.split("\n")) {
			if (/^\[.*\]$/u.test(line.trim())) {
				section = line.trim();
			} else if (
				/^\s*(?:[\w.]+\s*=\s*NO\b|TokenIgnores\s*=|MinAlertLevel\s*=\s*(?!suggestion\b))/u.test(
					line,
				)
			) {
				add(line.trim(), section || "<root>", offset);
			}
			offset += line.length + 1;
		}
		return findings;
	}
	const errors: ParseError[] = [];
	const tree = parseTree(content, errors, { allowTrailingComma: true });
	if (tree === undefined || errors.length > 0) {
		throw new Error(`${file}: invalid configuration JSON`);
	}
	function visit(node: JsonNode, parts: string[]): void {
		if (node.type === "object") {
			const object = asRecord(getNodeValue(node), "configuration object");
			const scoped =
				object.files === undefined ? parts : [...parts, `files=${JSON.stringify(object.files)}`];
			for (const property of node.children ?? []) {
				const [key, value] = property.children ?? [];
				if (key === undefined || value === undefined) {
					throw new Error(`${file}: configuration property has no key or value`);
				}
				visit(value, [...scoped, asString(key.value, "configuration key")]);
			}
			return;
		}
		const key = parts.at(-1) ?? "";
		const value: unknown = getNodeValue(node);
		if (weakensConfiguration(node, parts, value)) {
			add(JSON.stringify(value), parts.join("/"), node.offset);
		} else if (/^(?:ignorePatterns|ignores|exclude|allowed_elements)$/u.test(key)) {
			for (const child of node.children ?? []) {
				add(JSON.stringify(getNodeValue(child)), parts.join("/"), child.offset);
			}
		} else if (node.type === "array") {
			for (const [index, child] of (node.children ?? []).entries()) {
				// A files-scoped object is named by its selector, so removing a sibling keeps its identity.
				const scoped =
					key === "overrides" &&
					child.type === "object" &&
					asRecord(getNodeValue(child), file).files !== undefined;
				visit(child, scoped ? parts : [...parts, String(index)]);
			}
		}
	}
	visit(tree, []);
	return findings;
}

function policyFindings(file: string, content: string): Finding[] {
	if (isConfiguration(file)) {
		return configurationFindings(file, content);
	}
	if (file === "security/vulnerability-policy.json") {
		const policy = asRecord(parseJson(content), file);
		let offset = 0;
		return asArray(policy.exceptions, `${file}#exceptions`).map((value) => {
			const item = asRecord(value, "security exception");
			const fields = [
				"image",
				"platform",
				"vulnerability",
				"package",
				"installedVersion",
				"status",
				"expires",
			];
			const subject = [
				...fields.map((field) => asString(item[field], field)),
				...["digest", "componentInventory", "goAdvisory", "jarInventory"].map((field) =>
					asString(item[field] ?? "", field),
				),
			].join(" | ");
			const source = createHash("sha256").update(subject).digest("hex");
			const start = content.indexOf(JSON.stringify(item.vulnerability), offset);
			offset = start + 1;
			return {
				file,
				line: content.slice(0, start).split("\n").length,
				category: "security-exception",
				context: "exceptions",
				source,
			};
		});
	}
	if (file.endsWith("pnpm-workspace.yaml")) {
		const config = asRecord(parseYaml(content), file);
		return asStringArray(
			config.minimumReleaseAgeExclude ?? [],
			`${file}#minimumReleaseAgeExclude`,
		).map((source) => ({
			file,
			line: content.slice(0, content.indexOf(source)).split("\n").length,
			category: "release-age-exception",
			context: "minimumReleaseAgeExclude",
			source,
		}));
	}
	return [];
}

function applies(category: Category, file: string): boolean {
	if (category === "java-suppression") {
		return /\.(?:java|ftl)$/u.test(file);
	}
	if (category === "test-retry") {
		return file.endsWith(".java");
	}
	if (category === "build-exclusion") {
		return file.endsWith(".kts");
	}
	if (category === "architecture-exception") {
		return file.includes("/architecture/") && file.endsWith(".java");
	}
	if (/^(?:lint|typescript)-suppression$/u.test(category) || category === "ignored-catch") {
		return /\.(?:java|[cm]?[jt]sx?|kts)$/u.test(file);
	}
	return (
		!category.startsWith("test-") ||
		category === "test-tier-exclusion" ||
		/(?:\/test\/|\.(?:test|spec)\.|e2e\/|(?:vite|playwright|vitest)\.config)/u.test(file)
	);
}

function codeContext(code: string, text: string, start: number, end: number): string {
	const scopes: string[] = [];
	let boundary = 0;
	for (let i = 0; i < start; i += 1) {
		if (code[i] === "{") {
			scopes.push(text.slice(boundary, i).replaceAll(/\s+/gu, " ").trim());
			boundary = i + 1;
		} else if (code[i] === "}") {
			scopes.pop();
			boundary = i + 1;
		} else if (code[i] === ";") {
			boundary = i + 1;
		}
	}
	const next = text
		.slice(end)
		.split("\n")
		.map((line) => line.trim())
		.find((line) => line !== "" && !line.startsWith("@"));
	return `${scopes.join(" > ") || "<module>"} -> ${next?.replaceAll(/\s+/gu, " ") ?? "<end>"}`;
}

function markerSource(category: Category, content: string, start: number, match: string): string {
	if (category === "debt-marker") {
		return match.toLowerCase();
	}
	if (category === "lint-suppression") {
		const close = content.startsWith("/*", start)
			? content.indexOf("*/", start)
			: content.indexOf("\n", start);
		const text = content.slice(start, close === -1 ? undefined : close);
		return (
			text
				.split(/\s+--\s*/u)[0]
				?.replaceAll(/\s+/gu, " ")
				.trim() ?? match
		);
	}
	return content
		.slice(start, start + match.length)
		.replaceAll(/\s+/gu, " ")
		.trim();
}

export function scanSource(file: string, content: string): Finding[] {
	if (!isOwnedSource(file)) {
		return [];
	}
	const policy = policyFindings(file, content);
	if (file === "security/vulnerability-policy.json") {
		return policy;
	}
	const views = sourceViews(file, content);
	const text = content
		.split("")
		.map((character, index) =>
			views.comments[index] !== " " && character !== "\n" && character !== "\r" ? " " : character,
		)
		.join("");
	const findings = policy;
	for (const [category, pattern] of rules) {
		if (!applies(category, file)) {
			continue;
		}
		const commentsOnly =
			/^(?:lint|typescript|format|prose)-suppression$/u.test(category) ||
			category === "debt-marker" ||
			(category === "static-analysis-suppression" && /\.(?:java|[cm]?[jt]sx?|kts)$/u.test(file));
		const tested = commentsOnly ? views.comments : views.code;
		for (const match of tested.matchAll(pattern)) {
			const line = content.slice(0, match.index).split("\n").length;
			const source = markerSource(category, commentsOnly ? content : text, match.index, match[0]);
			const context = codeContext(views.code, text, match.index, match.index + match[0].length);
			findings.push({ file, line, category, source, context });
		}
	}
	for (const control of views.controls) {
		// A helper named `describe` in a production file is no test framework call.
		if (control.category === "test-timeout" && !applies(control.category, file)) {
			continue;
		}
		findings.push({
			file,
			line: content.slice(0, control.start).split("\n").length,
			category: control.category,
			source: control.value.replaceAll(/\s+/gu, " "),
			context: codeContext(views.code, text, control.start, control.end),
		});
	}
	return findings;
}

function identity(entry: Pick<Allowance, "file" | "category" | "source" | "context">): string {
	return JSON.stringify([entry.file, entry.category, entry.source, entry.context]);
}

export function parseBaseline(text: string): Allowance[] {
	const entries = asArray(parseJson(text), baselinePath);
	const seen = new Set<string>();
	return entries.map((value, index) => {
		const item = asRecord(value, `${baselinePath}[${index}]`);
		const file = asString(item.file, "file");
		const requestedCategory = asString(item.category, "category");
		const category = categories.find((name) => name === requestedCategory);
		const source = asString(item.source, "source");
		const context = asString(item.context, "context");
		const owner = asString(item.owner, "owner");
		const reason = asString(item.reason, "reason");
		if (
			category === undefined ||
			!isOwnedSource(file) ||
			/^(?:\/|\.\.)|(?:^|\/)\.\.(?:\/|$)/u.test(file) ||
			source.trim() !== source ||
			source === "" ||
			context.trim() === "" ||
			owner.trim() === "" ||
			!/^(?:constraint|root-cause|dead): \S.{10,}$/u.test(reason) ||
			!Number.isSafeInteger(item.count) ||
			typeof item.count !== "number" ||
			item.count <= 0
		) {
			throw new Error(`Invalid allowance at ${baselinePath}[${index}]`);
		}
		const entry = { file, category, source, context, count: item.count, owner, reason };
		const key = identity(entry);
		if (seen.has(key)) {
			throw new Error(`Duplicate allowance: ${file} (${category})`);
		}
		seen.add(key);
		return entry;
	});
}

export function assertShrinks(before: readonly Allowance[], after: readonly Allowance[]): void {
	const previous = new Map(before.map((entry) => [identity(entry), entry]));
	for (const entry of after) {
		const old = previous.get(identity(entry));
		if (old === undefined || entry.count > old.count) {
			throw new Error(`Baseline grew: ${entry.file} (${entry.category}); fix the cause instead.`);
		}
		if (!old.reason.startsWith("constraint:") && entry.reason.startsWith("constraint:")) {
			throw new Error(`Cause became an exception: ${entry.file} (${entry.category})`);
		}
	}
}

export function reconcile(
	findings: readonly Finding[],
	baseline: readonly Allowance[],
	shrink: boolean,
): Allowance[] {
	const counts = new Map<string, number>();
	const known = new Map(baseline.map((entry) => [identity(entry), entry]));
	for (const finding of findings) {
		const key = identity(finding);
		const count = (counts.get(key) ?? 0) + 1;
		counts.set(key, count);
		const allowed = known.get(key);
		if (allowed === undefined || count > allowed.count) {
			throw new Error(
				`New ${finding.category}: ${finding.file}:${finding.line}; fix the cause instead of adding an allowance.`,
			);
		}
	}
	return baseline.flatMap((entry) => {
		const count = counts.get(identity(entry)) ?? 0;
		if (!shrink && count !== entry.count) {
			throw new Error(
				`Stale baseline: ${entry.file} (${entry.category}); run vp run gate:band-aids --shrink.`,
			);
		}
		return count === 0 ? [] : [{ ...entry, count }];
	});
}

function git(cwd: string, ...args: string[]): string {
	return execFileSync("git", args, {
		cwd,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
		env: environmentWithoutGitRepository(),
	});
}

export function check(cwd: string, base: string, shrink = false): Finding[] {
	const baseline = parseBaseline(readFileSync(path.join(cwd, baselinePath), "utf8"));
	const revision = git(cwd, "rev-parse", "--verify", `${base}^{commit}`).trim();
	if (git(cwd, "ls-tree", "--name-only", revision, "--", baselinePath).trim() !== "") {
		assertShrinks(parseBaseline(git(cwd, "show", `${revision}:${baselinePath}`)), baseline);
	}
	const files = [
		...new Set(
			git(cwd, "ls-files", "-z", "--cached", "--others", "--exclude-standard").split("\0"),
		),
	]
		.filter(isOwnedSource)
		.toSorted();
	const findings = files.flatMap((file) => {
		const absolute = path.join(cwd, file);
		try {
			return scanSource(file, readFileSync(absolute, "utf8"));
		} catch (error) {
			if (error instanceof Error && "code" in error && error.code === "ENOENT") {
				return [];
			}
			throw error;
		}
	});
	if (files.length === 0) {
		throw new Error("No source files found; refusing an empty scan.");
	}
	const next = reconcile(findings, baseline, shrink);
	if (shrink) {
		writeFileSync(path.join(cwd, baselinePath), `${JSON.stringify(next, null, "\t")}\n`);
	}
	return findings;
}

if (import.meta.main) {
	const args = process.argv.slice(2);
	if (args.some((arg) => arg !== "--shrink")) {
		throw new Error("Usage: vp run gate:band-aids [--shrink]");
	}
	const findings = check(root, "origin/main", args.includes("--shrink"));
	for (const category of categories) {
		console.log(
			`${category}: ${findings.filter((finding) => finding.category === category).length}`,
		);
	}
}
