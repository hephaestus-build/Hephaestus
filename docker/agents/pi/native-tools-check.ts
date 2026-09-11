import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

import {
	createBashTool,
	createReadTool,
	createFindTool,
	createGrepTool,
	createWriteTool,
	createEditTool,
} from "@earendil-works/pi-coding-agent";

async function checkNativeTools() {
	const cwd = "/workspace/inputs/sources/scm/repo";
	const bash = await createBashTool(cwd).execute("bash", {
		command:
			"git log -1 --format=%s && rg --hidden --no-ignore --only-matching SENTINEL .hidden/large.txt && find .hidden -type f && ! git -c user.name=fixture -c user.email=fixture@example.invalid commit --allow-empty -m forbidden && ! touch tracked.txt && ! mv /workspace/inputs /workspace/replaced-inputs && ! touch /opt/pi-sdk/package.json",
		timeout: 10,
	});
	assert.match(JSON.stringify(bash.content), /captured/);
	assert.match(JSON.stringify(bash.content), /SENTINEL/);
	assert.doesNotMatch(JSON.stringify(bash.content), /Command exited with code/);
	const read = await createReadTool(cwd).execute("read", { path: "tracked.txt" });
	assert.match(JSON.stringify(read.content), /searchable/);
	const scratch = "/workspace/work/analysis.txt";
	await createWriteTool(cwd).execute("write", { path: scratch, content: "before\n" });
	await createEditTool(cwd).execute("edit", {
		path: scratch,
		edits: [{ oldText: "before", newText: "after" }],
	});
	assert.equal(await readFile(scratch, "utf8"), "after\n");
	await assert.rejects(
		createWriteTool(cwd).execute("write", { path: "tracked.txt", content: "forbidden\n" }),
	);
	await assert.rejects(
		createEditTool(cwd).execute("edit", {
			path: "tracked.txt",
			edits: [{ oldText: "searchable", newText: "forbidden" }],
		}),
	);
	assert.equal(await readFile(`${cwd}/tracked.txt`, "utf8"), "searchable\n");
	const grep = await createGrepTool(cwd).execute("grep", { pattern: "searchable" });
	assert.match(JSON.stringify(grep.content), /tracked.txt/);
	const find = await createFindTool(cwd).execute("find", { pattern: "*.txt" });
	assert.match(JSON.stringify(find.content), /tracked.txt/);
}

void checkNativeTools();
