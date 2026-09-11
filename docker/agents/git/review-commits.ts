import type { ChildProcessByStdio } from "node:child_process";
import { once } from "node:events";
import { createInterface } from "node:readline";
import type { Readable } from "node:stream";

type StartGit = (args: string[]) => ChildProcessByStdio<null, Readable, null>;

function completion(child: ChildProcessByStdio<null, Readable, null>) {
	return new Promise<void>((resolve, reject) => {
		child.once("error", reject);
		child.once("close", (code) => code === 0 ? resolve() : reject(new Error("Commit context extraction failed")));
	});
}

async function write(value: string) {
	if (!process.stdout.write(value)) await once(process.stdout, "drain");
}

export async function reviewCommits(revisions: string[], startGit: StartGit) {
	const history = startGit(["rev-list", "--topo-order", "--reverse", `${revisions[0]}..${revisions[1]}`, "--"]);
	const finished = completion(history);
	await write('{\n"commits": [\n');
	let first = true;
	for await (const sha of createInterface({ input: history.stdout })) {
		const metadata = startGit(["show", "--no-patch", "--format=%s%x00%b%x00%aI%x00%cI%x00%P", sha, "--"]);
		const chunks: Buffer[] = [];
		await Promise.all([completion(metadata), (async () => {
			for await (const chunk of metadata.stdout) {
                if (!Buffer.isBuffer(chunk)) throw new Error("Git output must be bytes");
                chunks.push(chunk);
            }
		})()]);
		const fields = new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks)).trimEnd().split("\0");
		const [subject, body, authoredAt, committedAt, parents] = fields;
		if (fields.length !== 5 || subject === undefined || body === undefined || authoredAt === undefined || committedAt === undefined || parents === undefined) throw new Error("Invalid commit metadata");
		const parentCount = parents === "" ? 0 : parents.split(" ").length;
		let changedFiles: number | undefined;
		if (parentCount === 1) {
			const changes = startGit(["diff-tree", "--no-commit-id", "--no-ext-diff", "--no-textconv", "-r", "-M50%", "--name-only", "-z", sha, "--"]);
			let count = 0;
			await Promise.all([completion(changes), (async () => {
				for await (const chunk of changes.stdout) {
					if (!Buffer.isBuffer(chunk)) throw new Error("Git output must be bytes");
                    for (const byte of chunk) if (byte === 0) count++;
				}
			})()]);
			changedFiles = count;
		}
		await write(`${first ? "" : ",\n"}${JSON.stringify({ sha, subject, body: body.trim() || undefined, authored_at: authoredAt, committed_at: committedAt, parent_count: parentCount, changed_files: changedFiles }, null, 2)}`);
		first = false;
	}
	await finished;
	await write('\n],\n"truncated": false\n}\n');
}
