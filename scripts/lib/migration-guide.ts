import { readFileSync } from "node:fs";

import { fromMarkdown } from "mdast-util-from-markdown";

import { compareVersions, parseVersion } from "./release-version.ts";

const template = readFileSync(new URL("../templates/migration-guide.md", import.meta.url), "utf8");
const marker = "<!-- VERSION_HISTORY -->\n\n";

// Keep source offsets: serializing the Markdown tree would rewrite released history.
export function migrationGuideSections(migration: string) {
	const headings = fromMarkdown(migration).children.filter(
		(node) => node.type === "heading" && node.depth <= 3,
	);
	return headings.map((node, index) => {
		const start = node.position?.start.offset;
		const contentStart = node.position?.end.offset;
		if (start === undefined || contentStart === undefined) {
			throw new Error("migration-guide: heading has no source position");
		}
		const end = headings[index + 1]?.position?.start.offset ?? migration.length;
		return {
			heading: migration.slice(start, contentStart),
			start,
			end,
			content: migration.slice(contentStart, end).trim(),
		};
	});
}

/**
 * A migration-guide entry with this heading says that its release cannot run beside the previous one.
 * The upgrade test and the changelog compatibility guard read it; `database-migration.mdx` § Expand and contract owns the rule.
 */
export const STOP_EVERY_ROLE_HEADING = "#### 🔴 Stop every role";

const releaseVersion = (heading: string) =>
	heading.startsWith("### v") ? parseVersion(heading.slice(5)) : undefined;

/**
 * The stop-every-role headings of the entries that an upgrade from `previousVersion` (X.Y.Z) runs: pending
 * `.migration` fragments, and the MIGRATION.md entries of later versions. `release:version` moves the fragments of a
 * release into MIGRATION.md, so the tree of a release carries them there.
 */
export function stopEveryRoleEntries(
	migration: string,
	fragments: readonly string[],
	previousVersion: string,
): string[] {
	const previous = releaseVersion(`### v${previousVersion}`);
	if (previous === undefined) {
		throw new Error(`migration-guide: ${previousVersion} is not an X.Y.Z version`);
	}
	const upgrade = migrationGuideSections(migration)
		.filter((section) => {
			const version = releaseVersion(section.heading);
			return (
				section.heading === "### Next release" ||
				(version !== undefined && compareVersions(version, previous) > 0)
			);
		})
		.map((section) => section.content);
	return [...upgrade, ...fragments].flatMap((entry) =>
		entry.split("\n").filter((line) => line.startsWith(STOP_EVERY_ROLE_HEADING)),
	);
}

export function renderMigrationGuide(migration: string): string {
	const sections = migrationGuideSections(migration);
	const starts = sections.filter((section) => section.heading === "### Next release");
	const ends = sections.filter(
		(section) => section.heading === "## Automatic vs Manual Migrations",
	);
	const from = starts[0]?.start;
	const to = ends[0]?.start;
	if (
		starts.length !== 1 ||
		ends.length !== 1 ||
		from === undefined ||
		to === undefined ||
		from >= to
	) {
		throw new Error(
			"migration-guide: expected exactly one ### Next release before ## Automatic vs Manual Migrations",
		);
	}
	const [head, tail, ...rest] = template.split(marker);
	if (head === undefined || tail === undefined || rest.length > 0) {
		throw new Error("migration-guide: template must contain exactly one version history marker");
	}
	return `${head}${migration.slice(from, to)}${tail}`;
}
