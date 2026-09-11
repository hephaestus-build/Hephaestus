import { readFileSync } from "node:fs";

import { fromMarkdown } from "mdast-util-from-markdown";

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
	const parts = template.split(marker);
	if (parts.length !== 2) {
		throw new Error("migration-guide: template must contain exactly one version history marker");
	}
	return `${parts[0]}${migration.slice(from, to)}${parts[1]}`;
}
