/**
 * The entries of the migration guide that an upgrade page lists: the released entries in `MIGRATION.md`
 * and the pending notes in `.migration/`. The `migration-entries` plugin of `docusaurus.config.ts`
 * reads them for the `MigrationEntries` component, so a change writes its operator action once, in its
 * own note. A note that breaks these rules fails the docs build.
 */

export interface MigrationEntry {
	/** The heading without its `#### 🔴` marker. */
	title: string;
	/** The release whose section holds the entry, such as `1.0.0-rc.7`; null for a pending note. */
	version: string | null;
	/** Where the whole entry is: its anchor in the migration guide, or the pending note. */
	link: string;
	/** The first sentence of the entry's `**Operators:**` paragraph, else of its first paragraph. */
	summary: string;
	/** The entry needs every role stopped: its heading starts with "Stop every role". */
	stopped: boolean;
	/** The rows of the entry's `| Where | Before | After |` table: one key, file or image each. */
	rows: [string, string, string][];
}

export interface Note {
	/** Relative to the repository root, such as `.migration/run-gauges.md`. */
	file: string;
	source: string;
}

const HEADING = /^#### (?:🔴 )?(?<title>.+)$/u;
const VALUES_HEADER = ["Where", "Before", "After"];

/** GitHub's anchor for a heading: lower case, letters, digits, spaces and hyphens, spaces as hyphens. */
function anchor(heading: string, seen: Map<string, number>): string {
	const base = heading
		.toLowerCase()
		.replaceAll(/[^\p{L}\p{M}\p{N}\s_-]/gu, "")
		.replaceAll(/\s/gu, "-");
	const count = seen.get(base) ?? 0;
	seen.set(base, count + 1);
	return count === 0 ? base : `${base}-${count}`;
}

const cells = (line: string) =>
	line
		.trim()
		.replace(/^\|/u, "")
		.replace(/\|$/u, "")
		.split("|")
		.map((cell) => cell.trim());

/** The values table of an entry's body, or why its table is not one. */
function valuesTable(body: string): { rows: [string, string, string][]; problem?: string } {
	const lines = body.split("\n");
	const start = lines.findIndex((line) => /^\|\s*Where\s*\|/u.test(line));
	if (start === -1) {
		return { rows: [] };
	}
	const header = cells(lines[start] ?? "");
	if (header.join("|") !== VALUES_HEADER.join("|")) {
		return { rows: [], problem: `the values table's columns must be ${VALUES_HEADER.join(", ")}` };
	}
	const rows: [string, string, string][] = [];
	for (const line of lines.slice(start + 2)) {
		if (!line.trim().startsWith("|")) {
			break;
		}
		const [where = "", before = "", after = ""] = cells(line);
		rows.push([where, before, after]);
	}
	return { rows };
}

/** The first sentence of a paragraph, without a list marker or the `**Operators:**` label. */
function firstSentence(paragraph: string): string {
	const text = paragraph
		.replaceAll(/\s*\n\s*/gu, " ")
		.replace(/^(?:\d+\.|[-*])\s+/u, "")
		.replace(/^\*\*Operators:\*\*\s*/u, "")
		.trim();
	return /^.+?[.!?](?=\s|$)/u.exec(text)?.[0] ?? text;
}

/**
 * The sentence that says what an operator does: the `**Operators:**` paragraph wins, or the paragraph
 * after a label that stands alone.
 */
function summaryOf(body: string): string {
	const paragraphs = body
		.split(/\n\s*\n/u)
		.map((paragraph) => paragraph.trim())
		.filter(
			(paragraph) => paragraph !== "" && !paragraph.startsWith("|") && !paragraph.startsWith("```"),
		);
	const operators = paragraphs.findIndex((paragraph) => paragraph.startsWith("**Operators:**"));
	if (operators === -1) {
		return firstSentence(paragraphs[0] ?? "");
	}
	return (
		firstSentence(paragraphs[operators] ?? "") || firstSentence(paragraphs[operators + 1] ?? "")
	);
}

function entry(
	heading: string,
	body: string,
	version: string | null,
	link: string,
): { listed: MigrationEntry; problems: string[] } {
	const title = HEADING.exec(heading)?.groups?.title?.trim() ?? "";
	const table = valuesTable(body);
	const summary = summaryOf(body);
	return {
		listed: {
			title,
			version,
			link,
			summary,
			stopped: title.startsWith("Stop every role"),
			rows: table.rows,
		},
		problems: [
			...(table.problem === undefined ? [] : [table.problem]),
			...(summary === "" ? ["say the operator's action in the first sentence"] : []),
		],
	};
}

/** Why a pending note cannot be listed: the rules of `.migration/README.md`. */
export function noteProblems(note: Note): string[] {
	const [heading = "", ...body] = note.source.split("\n");
	if (!HEADING.test(heading)) {
		return [`${note.file}:1: Start the migration note with a "#### " heading.`];
	}
	return entry(heading, body.join("\n"), null, note.file).problems.map(
		(problem) => `${note.file}: ${problem}.`,
	);
}

/** What an upgrade page reads: the guide's releases, newest first, and every entry. */
export interface MigrationGuide {
	versions: string[];
	entries: MigrationEntry[];
}

/**
 * Every entry of the migration guide's version sections, then every pending note. `repository` is
 * the URL that the guide and the notes are read at, such as `https://github.com/o/r/blob/main`.
 */
export function migrationEntries(
	guide: string,
	notes: readonly Note[],
	repository: string,
): MigrationGuide {
	const entries: MigrationEntry[] = [];
	const versions: string[] = [];
	const seen = new Map<string, number>();
	let version: string | null = null;
	let current: { heading: string; link: string; body: string[]; version: string } | undefined;
	const close = () => {
		if (current !== undefined) {
			entries.push(
				entry(current.heading, current.body.join("\n"), current.version, current.link).listed,
			);
		}
		current = undefined;
	};
	for (const line of guide.split("\n")) {
		const level = /^(?<hashes>#{1,6}) (?<text>.+)$/u.exec(line)?.groups;
		if (level === undefined) {
			current?.body.push(line);
			continue;
		}
		const slug = anchor(level.text ?? "", seen);
		if ((level.hashes?.length ?? 0) <= 3) {
			close();
			version = /^### v(?<version>\d+\.\d+\.\d+(?:-[\w.]+)?)$/u.exec(line)?.groups?.version ?? null;
			if (version !== null) {
				versions.push(version);
			}
		} else if (level.hashes === "####") {
			close();
			if (version !== null) {
				current = { heading: line, link: `${repository}/MIGRATION.md#${slug}`, body: [], version };
			}
		} else {
			current?.body.push(line);
		}
	}
	close();
	for (const note of [...notes].sort((a, b) => a.file.localeCompare(b.file))) {
		const [heading = "", ...body] = note.source.split("\n");
		const problems = noteProblems(note);
		if (problems.length > 0) {
			throw new Error(problems.join("\n"));
		}
		entries.push(entry(heading, body.join("\n"), null, `${repository}/${note.file}`).listed);
	}
	return { versions, entries };
}
