import { usePluginData } from "@docusaurus/useGlobalData";
import { Fragment, type ReactNode } from "react";

import type { MigrationEntry, MigrationGuide } from "@site/plugins/migration-entries";

interface MigrationEntriesProps {
	/** The last release whose entries the page lists by hand; later entries and pending notes appear here. */
	after: string;
	/**
	 * `values`: the rows of each entry's values table. `stopped`: the other entries that need every role
	 * stopped. `other`: every remaining entry. Each entry appears in exactly one group.
	 */
	group: "values" | "stopped" | "other";
}

function isGuide(value: unknown): value is MigrationGuide {
	return (
		typeof value === "object" &&
		value !== null &&
		"versions" in value &&
		Array.isArray(value.versions) &&
		"entries" in value &&
		Array.isArray(value.entries)
	);
}

/** The inline Markdown of a note's sentence: code, strong text and links. */
function inline(text: string): ReactNode[] {
	return text
		.split(/(?<token>`[^`]+`|\*\*[^*]+\*\*|\[[^\]]+\]\([^)\s]+\))/u)
		.filter((part) => part !== "")
		.map((part, index) => {
			const link = /^\[(?<label>[^\]]+)\]\((?<href>[^)\s]+)\)$/u.exec(part)?.groups;
			if (link?.label !== undefined && link.href !== undefined) {
				return (
					<a key={index} href={link.href}>
						{link.label}
					</a>
				);
			}
			if (part.startsWith("`") && part.endsWith("`")) {
				return <code key={index}>{part.slice(1, -1)}</code>;
			}
			if (part.startsWith("**") && part.endsWith("**")) {
				return <strong key={index}>{part.slice(2, -2)}</strong>;
			}
			return <Fragment key={index}>{part}</Fragment>;
		});
}

function Title({ entry }: { entry: MigrationEntry }): ReactNode {
	return (
		<a href={entry.link}>
			<em>{inline(entry.title)}</em>
		</a>
	);
}

/**
 * The migration entries after `after` that one group of an upgrade page lists, from the
 * `migration-entries` plugin. It renders nothing when no entry belongs to the group.
 */
export default function MigrationEntries({ after, group }: MigrationEntriesProps): ReactNode {
	const guide = usePluginData("migration-entries");
	if (!isGuide(guide)) {
		throw new Error("The migration-entries plugin provided no migration guide");
	}
	// The guide lists its releases newest first, so a later release has a lower position.
	const position = guide.versions.indexOf(after);
	if (position === -1) {
		throw new Error(`The migration guide has no section for ${after}`);
	}
	const later = guide.entries.filter(
		(entry) => entry.version === null || guide.versions.indexOf(entry.version) < position,
	);
	if (group === "values") {
		const rows = later.flatMap((entry) => entry.rows.map((row) => ({ entry, row })));
		if (rows.length === 0) {
			return null;
		}
		return (
			<table>
				<thead>
					<tr>
						<th>Where</th>
						<th>Before</th>
						<th>After</th>
						<th>Migration entry</th>
					</tr>
				</thead>
				<tbody>
					{rows.map(({ entry, row }) => (
						<tr key={`${entry.link}:${row.join("|")}`}>
							{row.map((cell, index) => (
								<td key={index}>{inline(cell)}</td>
							))}
							<td>
								<Title entry={entry} />
							</td>
						</tr>
					))}
				</tbody>
			</table>
		);
	}
	const listed = later.filter(
		(entry) => entry.rows.length === 0 && entry.stopped === (group === "stopped"),
	);
	if (listed.length === 0) {
		return null;
	}
	return (
		<ul>
			{listed.map((entry) => (
				<li key={entry.link}>
					<Title entry={entry} />: {inline(entry.summary)}
				</li>
			))}
		</ul>
	);
}
