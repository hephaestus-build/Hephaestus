import { existsSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { asRecord, asString, parseJson } from "./lib/json.ts";
import { migrationGuideSections, renderMigrationGuide } from "./lib/migration-guide.ts";

const version = asString(
	asRecord(parseJson(readFileSync("package.json", "utf8")), "package.json").version,
	"package.json version",
);

const edits = [
	{
		file: "docs/admin/install.mdx",
		re: /^VERSION=\S+(\s+# the release you are installing.*)$/m,
		line: `VERSION=${version}$1`,
	},
	{
		file: "README.md",
		re: /^\s*VERSION=\S+(\s+# the release you are installing.*)$/m,
		line: `  VERSION=${version}$1`,
	},
];

for (const { file, re, line } of edits) {
	const text = readFileSync(file, "utf8");
	if (!re.test(text)) {
		throw new Error(`sync-release-version: no version reference matched in ${file}`);
	}
	writeFileSync(file, text.replace(re, line));
}

const migrationFile = "MIGRATION.md";
const migration = renderMigrationGuide(readFileSync(migrationFile, "utf8"));
const sections = migrationGuideSections(migration);
const pendingSection = sections.find((section) => section.heading === "### Next release");
if (!pendingSection) {
	throw new Error("sync-release-version: MIGRATION.md must contain exactly one ### Next release");
}
const pending = pendingSection.content;
const fragmentDirectory = ".migration";
const fragmentFiles = existsSync(fragmentDirectory)
	? readdirSync(fragmentDirectory)
			.filter((file) => file.endsWith(".md") && file !== "README.md")
			.toSorted()
	: [];

const semverAtLeast = (candidate: string, reference: string): boolean => {
	const left = candidate.split(".").map(Number);
	const right = reference.split(".").map(Number);
	for (let index = 0; index < 3; index += 1) {
		if ((left[index] ?? 0) !== (right[index] ?? 0)) return (left[index] ?? 0) > (right[index] ?? 0);
	}
	return true;
};
const regionStart = pendingSection.start;
let regionEnd = pendingSection.end;
const unreleased: string[] = [];
// Changesets selects the next release version; sections at or above it have not shipped.
for (const section of sections.slice(sections.indexOf(pendingSection) + 1)) {
	const next = /^### v(\d+\.\d+\.\d+)$/.exec(section.heading);
	if (!next || !semverAtLeast(next[1] ?? "", version)) break;
	unreleased.push(section.content);
	regionEnd = section.end;
}

if (pending !== "" || unreleased.length > 0 || fragmentFiles.length > 0) {
	if (
		sections.some(
			(section) =>
				section.heading === `### v${version}` &&
				(section.start < regionStart || section.start >= regionEnd),
		)
	) {
		throw new Error(`sync-release-version: MIGRATION.md already contains ### v${version}`);
	}
	const fragments = fragmentFiles.map((file) =>
		readFileSync(join(fragmentDirectory, file), "utf8").trim(),
	);
	const section = ["### Next release", `### v${version}`, pending, ...unreleased, ...fragments]
		.filter(Boolean)
		.join("\n\n");
	writeFileSync(
		migrationFile,
		`${migration.slice(0, regionStart)}${section}\n\n${migration.slice(regionEnd)}`,
	);
	for (const file of fragmentFiles) rmSync(join(fragmentDirectory, file));
} else {
	writeFileSync(migrationFile, migration);
}

console.log(`Synced release version references to ${version}`);
