import { BASELINE_RELEASE } from "./previous-release.ts";
import { type Version, compareVersions, formatVersion, parseVersion } from "./release-version.ts";

/**
 * The release headings of CHANGELOG.md (`## 1.0.0-rc.8`) and MIGRATION.md (`### v1.0.0-rc.8`). A Version PR writes
 * the number before the cut, so a cut that fails leaves a heading that no release carries. Once a later release is
 * published, release history from the 1.0 baseline on names only published releases.
 */
export interface HeadingStyle {
	readonly depth: 2 | 3;
	readonly prefix: "## " | "### v";
}

export const CHANGELOG_HEADINGS: HeadingStyle = { depth: 2, prefix: "## " };
export const MIGRATION_HEADINGS: HeadingStyle = { depth: 3, prefix: "### v" };

const FLOOR = parseVersion(BASELINE_RELEASE.slice(1));

interface Section {
	readonly start: number;
	readonly end: number;
	readonly version: Version;
	readonly body: string;
}

/** The ATX headings outside fenced code. The release planner runs without dependencies, so no Markdown parser. */
function headings(markdown: string): { depth: number; start: number; contentStart: number }[] {
	const found: { depth: number; start: number; contentStart: number }[] = [];
	let fence: string | undefined;
	let offset = 0;
	for (const line of markdown.split("\n")) {
		const marker = /^ {0,3}(?<fence>`{3,}|~{3,})(?<info>.*)$/u.exec(line)?.groups;
		if (marker?.fence !== undefined && fence === undefined) {
			fence = marker.fence;
		} else if (
			marker?.fence !== undefined &&
			fence !== undefined &&
			marker.fence.startsWith(fence) &&
			marker.info?.trim() === ""
		) {
			// Only a bare fence of the same kind, at least as long, closes the block.
			fence = undefined;
		} else if (fence === undefined) {
			const depth = /^(?<hashes>#{1,6})(?: |$)/u.exec(line)?.groups?.hashes?.length;
			if (depth !== undefined) {
				found.push({ depth, start: offset, contentStart: offset + line.trimEnd().length });
			}
		}
		offset += line.length + 1;
	}
	return found;
}

/** Each release section from the baseline on: from its heading to the next heading of the same or a higher level. */
function releaseSections(markdown: string, style: HeadingStyle): Section[] {
	const all = headings(markdown).filter((heading) => heading.depth <= style.depth);
	return all.flatMap((heading, index) => {
		const text = markdown.slice(heading.start, heading.contentStart);
		const named = text.startsWith(style.prefix) ? text.slice(style.prefix.length) : "";
		const version = heading.depth === style.depth ? parseVersion(named) : undefined;
		if (version === undefined || FLOOR === undefined || compareVersions(version, FLOOR) < 0) {
			return [];
		}
		const end = Math.min(all[index + 1]?.start ?? markdown.length, markdown.length);
		return [
			{
				start: heading.start,
				end,
				version,
				body: markdown.slice(heading.contentStart, end).trim(),
			},
		];
	});
}

const parsedReleases = (published: ReadonlySet<string>): Version[] =>
	[...published]
		.flatMap((version) => {
			const parsed = parseVersion(version);
			return parsed === undefined ? [] : [parsed];
		})
		.toSorted(compareVersions);

/**
 * The published release that shipped the changes of a section whose number no release carries: the first published
 * release after it. A version without a tag and without a later published release can still be a cut in progress,
 * so it has no target and keeps its section.
 */
function shippedBy(version: Version, published: ReadonlySet<string>): string | undefined {
	if (published.has(formatVersion(version))) {
		return undefined;
	}
	const release = parsedReleases(published).find(
		(candidate) => compareVersions(candidate, version) > 0,
	);
	return release === undefined ? undefined : formatVersion(release);
}

/** The release headings from the baseline on whose number no release carries, although a later release is published. */
export function burnedHeadings(
	markdown: string,
	style: HeadingStyle,
	published: ReadonlySet<string>,
): string[] {
	return releaseSections(markdown, style)
		.filter((section) => shippedBy(section.version, published) !== undefined)
		.map((section) => `${style.prefix}${formatVersion(section.version)}`);
}

// Changesets writes these groups in this order; a merged section keeps it. Text before the first group comes first.
const CHANGE_GROUPS = ["", "### Major Changes", "### Minor Changes", "### Patch Changes"];

function groupOrder(key: string): number {
	const index = CHANGE_GROUPS.indexOf(key);
	return index === -1 ? CHANGE_GROUPS.length : index;
}

/** Merges Changesets bodies group by group, and other bodies one after the other. */
function mergeBodies(bodies: readonly string[], style: HeadingStyle): string {
	const present = bodies.filter(Boolean);
	if (style !== CHANGELOG_HEADINGS) {
		return present.join("\n\n");
	}
	const groups = new Map<string, string[]>();
	for (const body of present) {
		for (const part of body.split(/^(?=### )/mu)) {
			const [heading = "", ...rest] = part.split("\n");
			const key = heading.startsWith("### ") ? heading.trim() : "";
			const entries = (key === "" ? part : rest.join("\n")).trim();
			if (entries !== "") {
				groups.set(key, [...(groups.get(key) ?? []), entries]);
			}
		}
	}
	return [...groups]
		.toSorted(([left], [right]) => groupOrder(left) - groupOrder(right))
		.map(([key, entries]) => [key, entries.join("\n")].filter(Boolean).join("\n\n"))
		.join("\n\n");
}

/**
 * Moves each burned section into the section of the published release that shipped its changes. If that release has
 * no section, the newest burned section takes its name. Published sections keep their place and their entries.
 */
export function foldUnpublished(
	markdown: string,
	style: HeadingStyle,
	published: ReadonlySet<string>,
): string {
	const sections = releaseSections(markdown, style);
	const target = new Map<Section, string>();
	for (const section of sections) {
		const release = shippedBy(section.version, published);
		if (release !== undefined) {
			target.set(section, release);
		}
	}
	if (target.size === 0) {
		return markdown;
	}
	const releaseOf = (section: Section) => target.get(section) ?? formatVersion(section.version);
	// Each release gets one section: its own, or the newest one that folds into it.
	const holder = new Map<string, Section>();
	for (const section of sections) {
		const held = holder.get(releaseOf(section));
		if (
			held === undefined ||
			!target.has(section) ||
			(target.has(held) && compareVersions(section.version, held.version) > 0)
		) {
			holder.set(releaseOf(section), section);
		}
	}
	let result = "";
	let cursor = 0;
	for (const section of sections) {
		const release = releaseOf(section);
		result += markdown.slice(cursor, section.start);
		cursor = section.end;
		if (holder.get(release) !== section) {
			continue;
		}
		const members = sections
			.filter((member) => releaseOf(member) === release)
			.toSorted((left, right) => compareVersions(right.version, left.version));
		const body = mergeBodies(
			members.map((member) => member.body),
			style,
		);
		result += `${style.prefix}${release}\n\n${body}${body === "" ? "" : "\n\n"}`;
	}
	return result + markdown.slice(cursor);
}

/**
 * What the release history names wrongly at a cut: a burned heading that the Version PR did not fold, and a published
 * release without its CHANGELOG section.
 */
export function releaseHistoryProblems(
	changelog: string,
	migration: string,
	published: ReadonlySet<string>,
	current: string,
): string[] {
	const named = new Set(
		releaseSections(changelog, CHANGELOG_HEADINGS).map((section) => formatVersion(section.version)),
	);
	const cut = parseVersion(current);
	const missing = [...published].filter((version) => {
		const parsed = parseVersion(version);
		return (
			parsed !== undefined &&
			cut !== undefined &&
			FLOOR !== undefined &&
			compareVersions(parsed, FLOOR) >= 0 &&
			compareVersions(parsed, cut) < 0 &&
			!named.has(version)
		);
	});
	return [
		...burnedHeadings(changelog, CHANGELOG_HEADINGS, published).map(
			(heading) => `CHANGELOG.md has "${heading}", which no release carries`,
		),
		...burnedHeadings(migration, MIGRATION_HEADINGS, published).map(
			(heading) => `MIGRATION.md has "${heading}", which no release carries`,
		),
		...missing.map(
			(version) => `CHANGELOG.md has no "## ${version}" section for a published release`,
		),
	];
}
