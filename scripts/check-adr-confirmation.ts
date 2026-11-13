import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

import { fromMarkdown } from "mdast-util-from-markdown";

import { checkDebtRatchet } from "./lib/debt-ratchet.ts";
import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { isRecord } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";
import { loadTasks } from "./lib/task-graph.ts";

export const ADR_BASELINE = "scripts/baselines/adr-confirmation.json";

function inlineReferences(nodes: readonly unknown[]): string[] {
	const references: string[] = [];
	const visit = (node: unknown) => {
		if (!isRecord(node)) {
			return;
		}
		if (node.type === "inlineCode" && typeof node.value === "string") {
			references.push(node.value);
		}
		if (Array.isArray(node.children)) {
			for (const child of node.children) {
				visit(child);
			}
		}
	};
	for (const node of nodes) {
		visit(node);
	}
	return references;
}

function resolvesCheck(
	reference: string,
	files: ReadonlySet<string>,
	tasks: ReadonlySet<string>,
): boolean | undefined {
	const task = /^(?:vp run )?(?<task>(?:gate|test|check):[\w:-]+)$/u.exec(reference)?.groups?.task;
	const test =
		/^(?<name>[\w*.-]+(?:Test|Tests|ArchTest|ArchitectureTest)|[\w*.-]+\.(?:test|spec)\.[cm]?[jt]sx?)(?:#[\w$]+)?$/u.exec(
			reference,
		)?.groups?.name;
	const script = /^(?:scripts|docker|webapp|extension)\/[^\s`]+\.(?:[cm]?[jt]sx?|sh)$/u.test(
		reference,
	)
		? reference
		: undefined;
	if (task === undefined && test === undefined && script === undefined) {
		return undefined;
	}
	let found = false;
	if (task !== undefined) {
		found = tasks.has(task);
	} else if (script !== undefined) {
		found = files.has(script);
	} else if (test !== undefined) {
		const expected = test.includes(".") ? test : `${test}.java`;
		const pattern = new RegExp(
			`^${expected.replaceAll(/[.+?^${}()|[\]\\]/gu, String.raw`\$&`).replaceAll("*", ".*")}$`,
			"u",
		);
		found = [...files].some(
			(candidate) =>
				/(?:\/src\/test\/|\.(?:test|spec)\.[cm]?[jt]sx?$)/u.test(candidate) &&
				pattern.test(path.posix.basename(candidate)),
		);
	}
	return found;
}

export function confirmationFindings(
	file: string,
	source: string,
	files: ReadonlySet<string>,
	tasks: ReadonlySet<string>,
): string[] {
	const header = source.split(/^## /mu, 1)[0] ?? "";
	const status = /^\*\*Status:\*\* (?<status>.+)$/mu.exec(header)?.groups?.status;
	if (
		status === undefined ||
		!/^(?:Accepted|Proposed|Deprecated|Superseded by \[(?:ADR )?\d{4}\]\(\d{4}-[^)]+\.md\))$/u.test(
			status,
		)
	) {
		return [`${file}: use an ADR status from docs/decisions/0000-template.md`];
	}
	if (status !== "Accepted") {
		return [];
	}
	const tree = fromMarkdown(source);
	const start = tree.children.findIndex(
		(node) =>
			node.type === "heading" &&
			node.depth === 2 &&
			node.children.some((child) => child.type === "text" && child.value === "Confirmation"),
	);
	if (start === -1) {
		return [`${file}: missing Confirmation section`];
	}
	const section = [];
	for (const node of tree.children.slice(start + 1)) {
		if (node.type === "heading" && node.depth <= 2) {
			break;
		}
		section.push(node);
	}
	const references = inlineReferences(section);
	const findings: string[] = [];
	let checks = 0;
	for (const reference of references) {
		const found = resolvesCheck(reference, files, tasks);
		if (found === undefined) {
			continue;
		}
		if (found) {
			checks += 1;
		} else {
			findings.push(`${file}: missing check ${reference}`);
		}
	}
	if (checks === 0) {
		findings.push(`${file}: Confirmation names no existing executable check`);
	}
	return [...new Set(findings)].toSorted();
}

export async function measureConfirmations(root: string): Promise<string[]> {
	const listed = execFileSync(
		"git",
		["ls-files", "-z", "--cached", "--others", "--exclude-standard"],
		{
			cwd: root,
			encoding: "utf8",
			env: environmentWithoutGitRepository(),
			maxBuffer: CAPTURE_LIMIT_BYTES,
		},
	);
	const files = new Set(
		listed.split("\0").filter((file) => file !== "" && existsSync(path.join(root, file))),
	);
	const tasks = new Set(Object.keys(await loadTasks()));
	const adrs = [...files].filter((file) => /^docs\/decisions\/(?!0000)\d{4}-.+\.md$/u.test(file));
	if (adrs.length === 0) {
		throw new Error("docs/decisions: no ADR files found. Restore the decision records.");
	}
	return adrs
		.flatMap((file) =>
			confirmationFindings(file, readFileSync(path.join(root, file), "utf8"), files, tasks),
		)
		.toSorted();
}

if (import.meta.main) {
	const root = path.resolve(import.meta.dirname, "..");
	checkDebtRatchet(await measureConfirmations(root), ADR_BASELINE, root);
}
