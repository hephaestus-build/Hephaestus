import { BASELINE_RELEASE } from "./previous-release.ts";
import { type Version, compareVersions, formatVersion, parseVersion } from "./release-version.ts";

/**
 * The release headings of CHANGELOG.md (`## 1.0.0-rc.8`) and MIGRATION.md (`### v1.0.0-rc.8`). A Version PR writes
 * the number before the cut, so a cut that fails leaves a heading that no release carries. Release history from the
 * 1.0 baseline on names only published releases and the version being cut.
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
		const marker = /^ {0,3}(?<fence>`{3,}|~{3,})/u.exec(line)?.groups?.fence;
		if (marker !== undefined && (fence === undefined || marker.startsWith(fence))) {
			fence = fence === undefined ? marker : undefined;
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

/** The release headings from the baseline on that name neither a published release nor the version being cut. */
export function unpublishedHeadings(
	markdown: string,
	style: HeadingStyle,
	published: ReadonlySet<string>,
	current: string,
): string[] {
	return releaseSections(markdown, style)
		.map((section) => formatVersion(section.version))
		.filter((version) => version !== current && !published.has(version))
		.map((version) => `${style.prefix}${version}`);
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
		.map(([key, entries]) => [key, ...entries].filter(Boolean).join("\n\n"))
		.join("\n\n");
}

/**
 * Moves each section whose version was never published into the section of the release that shipped its changes:
 * the first published release or the version being cut after it. If that release has no section yet, the newest
 * folded section takes its name. Published sections keep their place and their entries.
 */
export function foldUnpublished(
	markdown: string,
	style: HeadingStyle,
	published: ReadonlySet<string>,
	current: string,
): string {
	const releases = [...published, current]
		.flatMap((version) => {
			const parsed = parseVersion(version);
			return parsed === undefined ? [] : [parsed];
		})
		.toSorted(compareVersions);
	const sections = releaseSections(markdown, style);
	const shipped = (version: Version) => {
		const name = formatVersion(version);
		return name === current || published.has(name);
	};
	const target = new Map<Section, string>();
	for (const section of sections.filter((candidate) => !shipped(candidate.version))) {
		const release = releases.find((version) => compareVersions(version, section.version) > 0);
		if (release === undefined) {
			throw new Error(
				`${style.prefix}${formatVersion(section.version)} is newer than the version being cut, ${current}`,
			);
		}
		target.set(section, formatVersion(release));
	}
	if (target.size === 0) {
		return markdown;
	}
	// Each release gets one section: its own, or the newest one that folds into it.
	const holder = new Map<string, Section>();
	for (const section of sections) {
		const release = target.get(section) ?? formatVersion(section.version);
		const held = holder.get(release);
		const own = !target.has(section);
		if (
			held === undefined ||
			own ||
			(target.has(held) && compareVersions(section.version, held.version) > 0)
		) {
			holder.set(release, section);
		}
	}
	let result = "";
	let cursor = 0;
	for (const section of sections) {
		const release = target.get(section) ?? formatVersion(section.version);
		result += markdown.slice(cursor, section.start);
		cursor = section.end;
		if (holder.get(release) !== section) {
			continue;
		}
		const members = sections
			.filter((member) => (target.get(member) ?? formatVersion(member.version)) === release)
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
 * What the release history names wrongly at a cut: a heading from the baseline on whose version no published release
 * carries, and a published release without its CHANGELOG section.
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
		...unpublishedHeadings(changelog, CHANGELOG_HEADINGS, published, current).map(
			(heading) => `CHANGELOG.md has "${heading}", which no published release carries`,
		),
		...unpublishedHeadings(migration, MIGRATION_HEADINGS, published, current).map(
			(heading) => `MIGRATION.md has "${heading}", which no published release carries`,
		),
		...missing.map(
			(version) => `CHANGELOG.md has no "## ${version}" section for a published release`,
		),
	];
}
