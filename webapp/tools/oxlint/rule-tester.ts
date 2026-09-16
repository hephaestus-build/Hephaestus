import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";

import type { Rule } from "@oxlint/plugins";
import { describe, expect, it } from "vitest";
import { z } from "zod";

import registeredPlugin from "./index.ts";

interface TestCase {
	code: string;
	only?: boolean;
	filename?: string;
	cwd?: string;
	options?: unknown[];
}

interface ExpectedError {
	message?: string | RegExp;
	messageId?: string;
	data?: Record<string, string>;
	line?: number;
	column?: number;
	endLine?: number;
	endColumn?: number;
}

interface InvalidTestCase extends TestCase {
	errors: number | ExpectedError[];
}

interface TestCases {
	valid: (string | TestCase)[];
	invalid: InvalidTestCase[];
}

const outputSchema = z.object({
	diagnostics: z.array(
		z.object({
			message: z.string(),
			labels: z.array(
				z.object({
					span: z.object({
						offset: z.number(),
						length: z.number(),
						line: z.number(),
						column: z.number(),
					}),
				}),
			),
		}),
	),
});

const root = resolve(import.meta.dirname, "../../..");
const oxlint = join(root, "node_modules/.bin/oxlint");
const plugin = join(root, "webapp/tools/oxlint/index.ts");

function asTestCase(value: string | TestCase): TestCase {
	return typeof value === "string" ? { code: value } : value;
}

function interpolate(template: string, data: Record<string, string> = {}): string {
	return template.replaceAll(
		/\{\{\s*(?<key>[^}\s]+)\s*\}\}/gu,
		(_, key: string) => data[key] ?? "",
	);
}

function messagePattern(template: string): RegExp {
	const escaped = template.replaceAll(/[.*+?^${}()|[\]\\]/gu, String.raw`\$&`);
	return new RegExp(`^${escaped.replaceAll(/\\\{\\\{\s*[^}]+\s*\\\}\\\}/gu, ".+?")}$`, "u");
}

function endPosition(code: string, byteOffset: number): { line: number; column: number } {
	const prefix = new TextDecoder().decode(new TextEncoder().encode(code).slice(0, byteOffset));
	const lines = prefix.split("\n");
	return { line: lines.length, column: (lines.at(-1)?.length ?? 0) + 1 };
}

function lint(ruleName: string, test: TestCase) {
	const directory = mkdtempSync(join(tmpdir(), "hephaestus-oxlint-"));
	try {
		const logicalCwd = test.cwd ?? process.cwd();
		const logicalFilename = test.filename ?? "test.tsx";
		const sandboxCwd = join(directory, "root", resolve(logicalCwd).slice(1));
		const filename = isAbsolute(logicalFilename)
			? join(directory, "root", logicalFilename.slice(1))
			: resolve(sandboxCwd, logicalFilename);
		const sandboxPath = relative(directory, filename);
		if (sandboxPath === ".." || sandboxPath.startsWith(`..${sep}`) || isAbsolute(sandboxPath)) {
			throw new Error(`Test filename escapes its temporary directory: ${logicalFilename}`);
		}
		const relativeFilename = relative(sandboxCwd, filename);
		mkdirSync(dirname(filename), { recursive: true });
		mkdirSync(sandboxCwd, { recursive: true });
		writeFileSync(filename, test.code);
		writeFileSync(
			join(sandboxCwd, "oxlint.json"),
			JSON.stringify({
				jsPlugins: [plugin],
				categories: { correctness: "off" },
				rules: {
					[`hephaestus/${ruleName}`]: test.options ? ["error", ...test.options] : "error",
				},
			}),
		);
		const result = spawnSync(oxlint, ["-c", "oxlint.json", "-f", "json", relativeFilename], {
			cwd: sandboxCwd,
			encoding: "utf8",
		});
		if (result.status !== 0 && result.status !== 1) {
			throw new Error(result.stderr || result.stdout || `oxlint exited ${result.status}`);
		}
		return outputSchema.parse(JSON.parse(result.stdout)).diagnostics;
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
}

export const ruleTester = {
	run(ruleName: string, rule: Rule, tests: TestCases): void {
		if (registeredPlugin.rules[ruleName] !== rule) {
			throw new Error(`Rule ${ruleName} is not registered by the Hephaestus plugin`);
		}
		// oxlint-disable-next-line vitest/valid-title -- The rule name is the subject of this shared suite.
		describe(ruleName, () => {
			for (const value of tests.valid) {
				const test = asTestCase(value);
				const run = test.only === true ? it.only : it;
				run("accepts valid code", () => {
					expect(lint(ruleName, test)).toStrictEqual([]);
				});
			}
			for (const test of tests.invalid) {
				const run = test.only === true ? it.only : it;
				run("reports invalid code", () => {
					const diagnostics = lint(ruleName, test);
					if (typeof test.errors === "number") {
						expect(diagnostics).toHaveLength(test.errors);
						return;
					}
					expect(diagnostics).toHaveLength(test.errors.length);
					for (const [errorIndex, expected] of test.errors.entries()) {
						const diagnostic = diagnostics[errorIndex];
						expect(diagnostic).toBeDefined();
						if (!diagnostic) {
							continue;
						}
						const template =
							expected.messageId === undefined
								? undefined
								: rule.meta?.messages?.[expected.messageId];
						if (expected.messageId !== undefined) {
							expect(template).toBeDefined();
						}
						const message =
							expected.message ??
							(template === undefined
								? undefined
								: expected.data
									? interpolate(template, expected.data)
									: messagePattern(template));
						if (message !== undefined) {
							expect(diagnostic.message).toMatch(message);
						}
						const span = diagnostic.labels[0]?.span;
						expect(span).toBeDefined();
						if (!span) {
							continue;
						}
						if (expected.line !== undefined) {
							expect(span.line).toBe(expected.line);
						}
						if (expected.column !== undefined) {
							expect(span.column).toBe(expected.column);
						}
						const end = endPosition(test.code, span.offset + span.length);
						if (expected.endLine !== undefined) {
							expect(end.line).toBe(expected.endLine);
						}
						if (expected.endColumn !== undefined) {
							expect(end.column).toBe(expected.endColumn);
						}
					}
				});
			}
		});
	},
};
