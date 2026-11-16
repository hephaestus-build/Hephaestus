/**
 * The entries of the migration guide that an upgrade page lists: the released entries in `MIGRATION.md`
 * and the pending notes in `.migration/`. `virtual:migration-entries` in `astro.config.ts` reads them
 * for the `MigrationEntries` component, so a change writes its operator action once, in its own note.
 * A note that breaks these rules fails the docs build.
 */

export interface MigrationEntry {
	/** The heading without its `#### 🔴` marker. */
	title: string;
	/** The release whose section holds the entry, such as `1.0.0-rc.7`; null for a pending note. */
	version: string | null;
	/** Where the whole entry is: its anchor in the migration guide, or the pending note. */
	link: string;
	/** When the operator acts, from the entry's `**When:**` field; null for an entry without one. */
	when: When[] | null;
	/** What the operator does, from the entry's one-line `**Action:**` field; null without one. */
	action: string | null;
	/** The rows of the entry's `| Where | Before | After |` table: one key, file or image each. */
	rows: [string, string, string][];
}

export interface Note {
	/** Relative to the repository root, such as `.migration/run-gauges.md`. */
	file: string;
	source: string;
}

/** The moments of an upgrade that an entry's action can belong to, in the order an upgrade meets them. */
export const WHEN = ["before the window", "in the window", "after the upgrade"] as const;
export type When = (typeof WHEN)[number];

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

// A cell can hold an escaped pipe, `\|`, which Markdown renders as a pipe and does not split on.
const cells = (line: string) =>
	line
		.trim()
		.replace(/^\|/u, "")
		.replace(/(?<!\\)\|$/u, "")
		.split(/(?<!\\)\|/u)
		.map((cell) => cell.trim().replaceAll(String.raw`\|`, "|"));

/**
 * Each line of a text, and whether it is literal: a fence line or inside a fenced code block. A
 * fence opens with three or more backticks or tildes and closes on a bare line of the same character
 * that is at least as long, as CommonMark says.
 */
function fenced(text: string): { line: string; literal: boolean }[] {
	let fence: string | null = null;
	return text.split("\n").map((line) => {
		const match = /^ {0,3}(?<marker>`{3,}|~{3,})(?<rest>.*)$/u.exec(line)?.groups;
		const marker = match?.marker;
		if (marker === undefined) {
			return { line, literal: fence !== null };
		}
		if (fence === null) {
			fence = marker;
		} else if (
			marker.startsWith(fence[0] ?? "") &&
			marker.length >= fence.length &&
			(match?.rest ?? "").trim() === ""
		) {
			fence = null;
		}
		return { line, literal: true };
	});
}

/** The lines of a text outside fenced code blocks: a fenced example is never an entry's field or table. */
const prose = (text: string): string[] =>
	fenced(text)
		.filter(({ literal }) => !literal)
		.map(({ line }) => line);

/** The guide's lines, each with the heading it starts, if any. A `#` line in a code block starts none. */
function headings(guide: string): { line: string; hashes?: string; text?: string }[] {
	return fenced(guide).map(({ line, literal }) => {
		const level = literal ? undefined : /^(?<hashes>#{1,6}) (?<text>.+)$/u.exec(line)?.groups;
		return { line, ...level };
	});
}

/** The values table of an entry's body, or why its table is not one. */
function valuesTable(body: string): { rows: [string, string, string][]; problem?: string } {
	const lines = prose(body);
	const start = lines.findIndex((line) => /^\|\s*Where\s*\|/u.test(line));
	if (start === -1) {
		return { rows: [] };
	}
	const header = cells(lines[start] ?? "");
	if (header.join("|") !== VALUES_HEADER.join("|")) {
		return { rows: [], problem: `the values table's columns must be ${VALUES_HEADER.join(", ")}` };
	}
	// Without a separator row of one cell per column, Markdown renders no table, and the next row would
	// be dropped unseen.
	const separator = cells(lines[start + 1] ?? "");
	if (
		separator.length !== VALUES_HEADER.length ||
		!separator.every((cell) => /^:?-+:?$/u.test(cell))
	) {
		return {
			rows: [],
			problem: "the values table needs a `| --- | --- | --- |` row under its header",
		};
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

const isWhen = (value: string): value is When => WHEN.some((when) => when === value);

/** The value of an entry's `- **<label>:** …` line, or why it has no single one. */
function field(body: string, label: string): { value: string | null; problem?: string } {
	const prefix = `- **${label}:**`;
	const values = prose(body)
		.filter((line) => line.startsWith(prefix))
		.map((line) => line.slice(prefix.length).trim());
	const [value] = values;
	if (values.length === 1 && value !== undefined && value !== "") {
		return { value };
	}
	return { value: null, problem: `give one non-empty "${prefix} …" line` };
}

/** The `**When:**` field: one or more of {@link WHEN}, in upgrade order, separated by commas. */
function whenOf(body: string): { value: When[] | null; problem?: string } {
	const { value, problem } = field(body, "When");
	if (value === null) {
		return { value, problem };
	}
	const parts = value.split(",").map((part) => part.trim().toLowerCase());
	const known = parts.filter(isWhen);
	const ordered = WHEN.filter((when) => known.includes(when));
	if (known.length !== parts.length || known.join(",") !== ordered.join(",")) {
		return {
			value: null,
			problem: `list ${WHEN.map((when) => `"${when}"`).join(", ")} in "**When:**", in that order, separated by commas`,
		};
	}
	return { value: known };
}

function entry(
	heading: string,
	body: string,
	version: string | null,
	link: string,
): { listed: MigrationEntry; problems: string[] } {
	const title = HEADING.exec(heading)?.groups?.title?.trim() ?? "";
	const table = valuesTable(body);
	const when = whenOf(body);
	const action = field(body, "Action");
	// The page renders the line as raw links, which the docs build's link check does not read.
	const relative = [...(action.value ?? "").matchAll(/\]\((?<target>[^)]*)\)/gu)].some(
		({ groups }) => !(groups?.target ?? "").startsWith("https://"),
	);
	const linkProblem = relative ? 'use absolute https links in the "- **Action:**" line' : undefined;
	return {
		listed: { title, version, link, when: when.value, action: action.value, rows: table.rows },
		problems: [table.problem, when.problem, action.problem, linkProblem].filter(
			(problem) => problem !== undefined,
		),
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
	for (const { line, ...level } of headings(guide)) {
		if (level.hashes === undefined) {
			current?.body.push(line);
			continue;
		}
		const slug = anchor(level.text ?? "", seen);
		if (level.hashes.length <= 3) {
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
