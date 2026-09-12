import type { ChildProcessByStdio } from "node:child_process";
import { once } from "node:events";
import type { Readable } from "node:stream";
import { records } from "./lines.ts";
import { succeeded } from "./process.ts";

type StartGit = (args: string[]) => ChildProcessByStdio<null, Readable, null>;

const FIELD_COUNT = 6;

async function write(value: string) {
	if (!process.stdout.write(value)) await once(process.stdout, "drain");
}

interface Commit {
	fields: string[];
	files: number;
}

function render(commit: Commit): string {
	const [sha, subject, body, authoredAt, committedAt, parents] = commit.fields;
	if (
		commit.fields.length !== FIELD_COUNT ||
		sha === undefined ||
		subject === undefined ||
		body === undefined ||
		authoredAt === undefined ||
		committedAt === undefined ||
		parents === undefined ||
		!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(sha)
	)
		throw new Error("Invalid commit metadata");
	const parentCount = parents === "" ? 0 : parents.split(" ").length;
	return JSON.stringify(
		{
			sha,
			subject,
			body: body.trim() || undefined,
			authored_at: authoredAt,
			committed_at: committedAt,
			parent_count: parentCount,
			changed_files: parentCount === 1 ? commit.files : undefined,
		},
		null,
		2,
	);
}

/**
 * One `git log` over the range. With `-z` every field, the commit itself and every changed path is
 * one NUL record: a commit opens with the 0x01 the format puts first, its file list opens with the
 * newline Git prints before a diff, and a merge lists no files because merge diffs are off. A path
 * beginning with 0x01 is read as a commit header and fails the operation rather than being counted
 * against the wrong commit.
 */
export async function reviewCommits(revisions: string[], startGit: StartGit) {
	const log = startGit([
		"log",
		"--topo-order",
		"--reverse",
		"--format=%x01%H%x00%s%x00%b%x00%aI%x00%cI%x00%P",
		"--name-only",
		"-z",
		"-M50%",
		`${revisions[0]}..${revisions[1]}`,
		"--",
	]);
	const finished = succeeded(log, "Commit context extraction failed");
	const decoder = new TextDecoder("utf-8", { fatal: true });
	await write('{\n"commits": [\n');
	let first = true;
	let commit: Commit | null = null;
	const flush = async () => {
		if (commit === null) return;
		await write(`${first ? "" : ",\n"}${render(commit)}`);
		first = false;
		commit = null;
	};
	for await (const { bytes } of records(log.stdout, 0)) {
		if (commit !== null && commit.fields.length < FIELD_COUNT) {
			commit.fields.push(decoder.decode(bytes));
		} else if (bytes[0] === 1) {
			await flush();
			commit = { fields: [decoder.decode(bytes.subarray(1))], files: 0 };
		} else if (commit === null || (commit.files === 0 && bytes[0] !== 10)) {
			throw new Error("Invalid commit file list");
		} else commit.files++;
	}
	await flush();
	await finished;
	await write('\n],\n"truncated": false\n}\n');
}
