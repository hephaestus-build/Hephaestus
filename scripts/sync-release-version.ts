import { existsSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";

import { asRecord, asString, parseJson } from "./lib/json.ts";
import { migrationGuideSections, renderMigrationGuide } from "./lib/migration-guide.ts";
import { compareVersions, parseVersion } from "./lib/release-version.ts";

const manifest = asRecord(parseJson(readFileSync("package.json", "utf8")), "package.json");
let version = asString(manifest.version, "package.json version");

// Changesets starts at rc.0; the public release train numbers its first candidate rc.1.
if (version.endsWith("-rc.0")) {
	const original = version;
	version = `${version.slice(0, -1)}1`;
	const changelog = readFileSync("CHANGELOG.md", "utf8");
	const heading = `## ${original}`;
	const first = migrationGuideSections(changelog).find((section) =>
		section.heading.startsWith("## "),
	);
	if (first?.heading !== heading) {
		throw new Error("sync-release-version: first candidate needs its generated changelog heading");
	}
	writeFileSync("package.json", `${JSON.stringify({ ...manifest, version }, null, "\t")}\n`);
	writeFileSync(
		"CHANGELOG.md",
		`${changelog.slice(0, first.start)}## ${version}${changelog.slice(first.start + heading.length)}`,
	);
}

const edits = [
	{
		file: "docs/admin/kubernetes.mdx",
		re: /^(?<indent>[ \t]*)VERSION=\S+(?<comment>\s+# the release, without the leading "v".*)$/mu,
		line: `$<indent>VERSION=${version}$<comment>`,
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
	const left = parseVersion(candidate);
	const right = parseVersion(reference);
	return left !== undefined && right !== undefined && compareVersions(left, right) >= 0;
};
const regionStart = pendingSection.start;
let regionEnd = pendingSection.end;
const unreleased: string[] = [];
// Changesets selects the next release version; sections at or above it have not shipped.
for (const section of sections.slice(sections.indexOf(pendingSection) + 1)) {
	const next = section.heading.startsWith("### v") ? section.heading.slice(5) : "";
	if (!semverAtLeast(next, version)) {
		break;
	}
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
		readFileSync(path.join(fragmentDirectory, file), "utf8").trim(),
	);
	const section = ["### Next release", `### v${version}`, pending, ...unreleased, ...fragments]
		.filter(Boolean)
		.join("\n\n");
	writeFileSync(
		migrationFile,
		`${migration.slice(0, regionStart)}${section}\n\n${migration.slice(regionEnd)}`,
	);
	for (const file of fragmentFiles) {
		rmSync(path.join(fragmentDirectory, file));
	}
} else {
	writeFileSync(migrationFile, migration);
}

console.log(`Synced release version references to ${version}`);
