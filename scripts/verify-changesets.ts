import { existsSync, readFileSync } from "node:fs";
import { basename } from "node:path";

import { asArray, asRecord, asString, readJsonFile } from "./lib/json.ts";
import { CLAUDE_SESSION_PATTERN } from "./lib/model-attribution.ts";

const changesetEntries = (status: unknown): Map<string, Record<string, unknown>> => {
	const entries = asArray(
		asRecord(status, "changeset status").changesets,
		"changeset status.changesets",
	);
	return new Map(
		entries.map((value, index) => {
			const entry = asRecord(value, `changeset status.changesets[${index}]`);
			return [asString(entry.id, `changeset status.changesets[${index}].id`), entry];
		}),
	);
};

export const verifyMigrationFragment = (file: string, summary: string, content: string): void => {
	if (!summary.includes("**Operators:**")) {
		throw new Error(`${file}: matching changeset summary must contain **Operators:**`);
	}
	// Fenced code blocks may contain `# comment` lines (see MIGRATION.md v0.74.0); only prose
	// outside them is subject to the heading rules.
	const prose = content.replace(/^```[^\n]*\n[\s\S]*?^```[^\S\n]*$/gm, "");
	const entryHeadings = prose.match(/^#### /gm) ?? [];
	if (entryHeadings.length !== 1 || !/^#### 🔴 \S/m.test(prose) || /^#{1,3} /m.test(prose)) {
		throw new Error(`${file}: must contain exactly one #### 🔴 entry and no level 1-3 headings`);
	}
};

export const verifyChangesetMigration = (
	file: string,
	summary: string,
	fragment: string | undefined,
): void => {
	if (summary.includes("**Operators:**") && fragment === undefined) {
		throw new Error(`${file}: **Operators:** requires .migration/${basename(file)}`);
	}
	if (fragment !== undefined)
		verifyMigrationFragment(`.migration/${basename(file)}`, summary, fragment);
};

export const verifyChangesets = (
	status: unknown,
	files: readonly string[],
	preOne = true,
): void => {
	const byId = changesetEntries(status);

	for (const file of files) {
		const entry = byId.get(basename(file, ".md"));
		if (!entry) throw new Error(`${file}: Changesets did not parse this file`);

		const summary = asString(entry.summary, `${file} summary`).trim();
		const releases = asArray(entry.releases, `${file} releases`);
		if (summary === "") {
			throw new Error(
				releases.length === 0
					? `${file}: an empty changeset must explain why no release note is needed`
					: `${file}: a release changeset needs a user- or operator-facing summary`,
			);
		}
		// A changeset summary is a release note: it carries no trailer at all, not even a human
		// co-author's, so `Co-authored-by:` is refused outright rather than only the model-named
		// shape `scripts/lib/model-attribution.ts` matches.
		if (/^co-authored-by:/im.test(summary) || CLAUDE_SESSION_PATTERN.test(summary)) {
			throw new Error(
				`${file}: release notes must not contain Co-authored-by or Claude-Session metadata`,
			);
		}
		if (releases.length === 0) continue;
		if (releases.length !== 1)
			throw new Error(`${file}: must release only the root hephaestus package`);

		const release = asRecord(releases[0], `${file} release`);
		const name = asString(release.name, `${file} release name`);
		const bump = asString(release.type, `${file} release type`);
		if (name !== "hephaestus")
			throw new Error(`${file}: must release only the root hephaestus package`);
		if (preOne && bump === "major") {
			throw new Error(
				`${file}: pre-1.0 major changesets are reserved for the deliberate 1.0 release; use minor`,
			);
		}
		if (bump === "major" && !summary.includes("**Operators:**")) {
			throw new Error(`${file}: a major changeset must contain **Operators:**`);
		}
	}
};

if (import.meta.main) {
	try {
		const [statusJson, ...arguments_] = process.argv.slice(2);
		if (!statusJson)
			throw new Error("usage: verify-changesets.ts <changeset-status-json> <files...>");
		const status = await readJsonFile(statusJson);
		if (arguments_[0] === "--migration") {
			const entries = changesetEntries(status);
			for (const file of arguments_.slice(1)) {
				const entry = entries.get(basename(file, ".md"));
				if (!entry) throw new Error(`${file}: requires a changeset with the same slug`);
				const summary = asString(entry.summary, `${file} summary`);
				verifyMigrationFragment(file, summary, readFileSync(file, "utf8"));
			}
		} else {
			const root = asRecord(await readJsonFile("package.json"), "package.json");
			verifyChangesets(
				status,
				arguments_,
				asString(root.version, "package.json version").startsWith("0."),
			);
			const entries = changesetEntries(status);
			for (const file of arguments_) {
				const entry = entries.get(basename(file, ".md"));
				if (entry) {
					const summary = asString(entry.summary, `${file} summary`);
					const fragmentFile = `.migration/${basename(file)}`;
					verifyChangesetMigration(
						file,
						summary,
						existsSync(fragmentFile) ? readFileSync(fragmentFile, "utf8") : undefined,
					);
				}
			}
		}
	} catch (error) {
		console.error(`::error::${error instanceof Error ? error.message : String(error)}`);
		process.exitCode = 1;
	}
}
