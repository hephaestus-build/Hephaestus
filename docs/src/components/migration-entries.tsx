import { Fragment, type ReactNode } from "react";
import guide from "virtual:migration-entries";

import { sectionFor, type MigrationEntry } from "@/lib/migration-entries";

interface MigrationEntriesProps {
	/** The last release whose entries the page lists by hand; later entries and pending notes appear here. */
	after: string;
	/** `values`: the rows of each entry's values table. `entries`: each entry with its section, when and action. */
	group: "values" | "entries";
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

/** The guide section of an entry, as the page's own rows name it: `rc.6`, or `Target` for a pending note. */
function section(entry: MigrationEntry): string {
	return entry.version === null ? "Target" : entry.version.replace(/^\d+\.\d+\.\d+-/u, "");
}

function Title({ entry }: { entry: MigrationEntry }): ReactNode {
	return (
		<a href={entry.link}>
			<em>{inline(entry.title)}</em>
		</a>
	);
}

/**
 * The migration entries after `after` that one group of an upgrade page lists, from `virtual:migration-entries`
 * (`astro.config.ts`). It renders nothing when no entry belongs to the group.
 */
export function MigrationEntries({ after, group }: MigrationEntriesProps): ReactNode {
	// The guide lists its releases newest first, so a later release has a lower position.
	const position = guide.versions.indexOf(sectionFor(guide.versions, after));
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
	if (later.length === 0) {
		return null;
	}
	return (
		<table>
			<thead>
				<tr>
					<th>Migration entry</th>
					<th>Section</th>
					<th>When</th>
					<th>Action</th>
				</tr>
			</thead>
			<tbody>
				{later.map((entry) => {
					if (entry.when === null || entry.action === null) {
						throw new Error(`The migration entry "${entry.title}" lacks its When or Action field`);
					}
					const when = entry.when.join(", ");
					return (
						<tr key={entry.link}>
							<td>
								<Title entry={entry} />
							</td>
							<td>{section(entry)}</td>
							<td>{when.charAt(0).toUpperCase() + when.slice(1)}</td>
							<td>{inline(entry.action)}</td>
						</tr>
					);
				})}
			</tbody>
		</table>
	);
}
