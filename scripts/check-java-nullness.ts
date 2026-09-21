import { access, readFile } from "node:fs/promises";
import path from "node:path";

import { output } from "./lib/process.ts";

const REPO_ROOT = path.resolve(import.meta.dirname, "..");
const JAVA_SOURCE = /^server\/application\/src\/(?:main|test)\/java\/.*\.java$/u;

export interface JavaSource {
	readonly path: string;
	readonly content: string;
}

export function isHandwrittenJavaSource(file: string): boolean {
	return JAVA_SOURCE.test(file);
}

export async function discoverJavaSourcePaths(
	root: string = REPO_ROOT,
): Promise<readonly string[]> {
	const stdout = await output(
		"git",
		["ls-files", "-z", "--cached", "--others", "--exclude-standard"],
		{
			cwd: root,
			env: { GIT_DIR: undefined, GIT_INDEX_FILE: undefined, GIT_WORK_TREE: undefined },
		},
	);
	const candidates = stdout.split("\0").filter(isHandwrittenJavaSource);
	const present = await Promise.all(
		candidates.map(async (candidate) => {
			try {
				await access(path.resolve(root, candidate));
				return candidate;
			} catch (error) {
				if (isNodeError(error) && error.code === "ENOENT") {
					return;
				}
				throw error;
			}
		}),
	);
	const paths = present.filter((file): file is string => file !== undefined);
	if (paths.length === 0) {
		throw new Error(
			"No handwritten Java sources found under server/application/src/{main,test}/java; refusing to pass without checking anything.",
		);
	}
	return paths;
}

function isNodeError(error: unknown): error is NodeJS.ErrnoException {
	return error instanceof Error;
}

function unicodeEscapes(source: string): string {
	return source.replaceAll(/\\u+(?<hex>[0-9a-fA-F]{4})/gu, (_, hex: string) =>
		String.fromCodePoint(Number.parseInt(hex, 16)),
	);
}

function suppressionBodies(source: string): readonly string[] {
	const text = unicodeEscapes(source);
	const bodies: string[] = [];
	const annotation = /@(?:java\.lang\.)?SuppressWarnings\s*\(/gu;
	for (let match = annotation.exec(text); match !== null; match = annotation.exec(text)) {
		const start = annotation.lastIndex;
		let depth = 1;
		let quoted = false;
		let escaped = false;
		for (let index = start; index < text.length; index += 1) {
			const character = text[index];
			if (quoted) {
				if (escaped) {
					escaped = false;
				} else if (character === "\\") {
					escaped = true;
				} else if (character === '"') {
					quoted = false;
				}
				continue;
			}
			if (character === '"') {
				quoted = true;
			} else if (character === "(") {
				depth += 1;
			} else if (character === ")") {
				depth -= 1;
				if (depth === 0) {
					bodies.push(text.slice(start, index));
					annotation.lastIndex = index + 1;
					break;
				}
			}
		}
	}
	return bodies;
}

function stringValues(body: string): string {
	return [...body.matchAll(/"(?<value>(?:\\.|[^"\\])*)"/gu)]
		.map((match) => match.groups?.value?.replaceAll(/\\(?<quoted>["\\])/gu, "$<quoted>") ?? "")
		.join("");
}

function violatesPolicy(body: string): boolean {
	if (stringValues(body).includes("NullAway")) {
		return true;
	}
	const withoutStrings = body.replaceAll(/"(?:\\.|[^"\\])*"/gu, "");
	return !/^[\s{},+]*$/u.test(withoutStrings);
}

export function nullnessPolicyViolations(sources: readonly JavaSource[]): readonly string[] {
	return sources
		.filter(({ content }) => suppressionBodies(content).some(violatesPolicy))
		.map((source) => source.path);
}

async function main(): Promise<void> {
	const paths = await discoverJavaSourcePaths();
	const sources: JavaSource[] = [];
	for (const file of paths) {
		sources.push({ path: file, content: await readFile(path.resolve(REPO_ROOT, file), "utf8") });
	}
	const suppressed = nullnessPolicyViolations(sources);
	if (suppressed.length > 0) {
		throw new Error(
			`NullAway suppressions and indirect suppression names are forbidden; fix the contract or implementation:\n${suppressed.map((file) => `  ${file}`).join("\n")}`,
		);
	}
	console.log(
		`Java nullness policy: ${sources.length} handwritten source file(s), no NullAway suppressions.`,
	);
}

if (import.meta.main) {
	await main();
}
