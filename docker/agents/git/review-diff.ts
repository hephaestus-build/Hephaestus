import { spawn, type ChildProcessByStdio } from "node:child_process";
import { createWriteStream } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import type { Readable } from "node:stream";
import { join } from "node:path";
import { pipeline } from "node:stream/promises";

function exit(child: ChildProcessByStdio<null, Readable, null>) {
	return new Promise<void>((resolve, reject) => {
		child.once("error", reject);
		child.once("close", (code) => code === 0 ? resolve() : reject(new Error("Diff preparation failed")));
	});
}

// readline treats CR as a delimiter; a diff's source bytes must remain unchanged.
export async function* annotateDiff(source: AsyncIterable<unknown> | Iterable<unknown>) {
	let fragments: Buffer[] = [];
	let oldLine: number | undefined;
	let newLine: number | undefined;
	function annotate(line: Buffer) {
		const header = line.subarray(0, 256).toString("utf8");
		if (header.startsWith("diff --git ")) {
			oldLine = undefined;
			newLine = undefined;
		}
		const hunk = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(header);
		if (hunk) {
			oldLine = Number(hunk[1]);
			newLine = Number(hunk[2]);
			if (!Number.isSafeInteger(oldLine) || !Number.isSafeInteger(newLine)) throw new Error("Invalid diff line number");
			return line;
		}
		if (newLine === undefined || oldLine === undefined || line[0] === 92 || line[0] === 10) return line;
		let number: number;
		if (line[0] === 43) number = newLine++;
		else if (line[0] === 45) number = oldLine++;
		else if (line[0] === 32) { number = newLine++; oldLine++; }
		else throw new Error("Invalid diff hunk content");
		return Buffer.concat([Buffer.from(`[L${number}] `), line]);
	}
	for await (const chunk of source) {
		if (!Buffer.isBuffer(chunk)) throw new Error("Git output must be bytes");
		let start = 0;
		let end: number;
		while ((end = chunk.indexOf(10, start)) !== -1) {
			const part = chunk.subarray(start, end + 1);
			const line = fragments.length === 0 ? part : Buffer.concat([...fragments, part]);
			fragments = [];
			yield annotate(line);
			start = end + 1;
		}
		if (start < chunk.length) fragments.push(chunk.subarray(start));
	}
	if (fragments.length !== 0) yield annotate(Buffer.concat(fragments));
}

export async function reviewDiff(
	revisions: string[],
	startGit: (args: string[]) => ChildProcessByStdio<null, Readable, null>,
) {
	const directory = join(process.env.GIT_SNAPSHOT_DIRECTORY ?? "/snapshot", "review");
	await mkdir(directory, { recursive: true });
	const options = ["-c", "diff.algorithm=histogram", "diff", "--no-ext-diff", "--no-textconv", "--no-color", "--no-relative", "-M50%"];
	const diff = startGit([...options, ...revisions, "--"]);
	await Promise.all([exit(diff), pipeline(diff.stdout, annotateDiff, createWriteStream(join(directory, "diff.patch")))]);
	const paths = startGit([...options, "--name-only", "-z", ...revisions, "--"]);
	await Promise.all([exit(paths), pipeline(paths.stdout, createWriteStream(join(directory, "diff_paths.nul")))]);
	const stat = startGit([...options, "--stat", ...revisions, "--"]);
	await Promise.all([exit(stat), pipeline(stat.stdout, createWriteStream(join(directory, "diff_stat.txt")))]);
	await writeFile(join(directory, "diff_summary.md"), "# Diff summary\n\nSee `diff_stat.txt` for the file overview and `diff.patch` for the change, annotated with source line numbers.\n");
	const archive = spawn("tar", ["-C", directory, "-cf", "-", "diff.patch", "diff_stat.txt", "diff_summary.md", "diff_paths.nul"], { stdio: ["ignore", "pipe", "inherit"] });
	await Promise.all([exit(archive), pipeline(archive.stdout, process.stdout, { end: false })]);
}
