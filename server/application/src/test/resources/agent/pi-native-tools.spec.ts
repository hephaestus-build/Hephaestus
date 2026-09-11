import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

void test("bash retrieves ignored large-file content beyond read output limits", async () => {
	const cwd = mkdtempSync(join(tmpdir(), "native-tools-"));
	try {
		mkdirSync(join(cwd, ".hidden"));
		writeFileSync(join(cwd, ".gitignore"), ".hidden/\n");
		writeFileSync(join(cwd, ".hidden", "large.txt"), `${"x".repeat(3 * 1024 * 1024)}SENTINEL\n`);
		const { createBashTool, createReadTool } = await import("@earendil-works/pi-coding-agent");
		const read = await createReadTool(cwd).execute("read", { path: ".hidden/large.txt" });
		assert.match(JSON.stringify(read.content), /bash/);
		const bash = await createBashTool(cwd).execute("bash", {
			command: "grep -o SENTINEL .hidden/large.txt",
			timeout: 10,
		});
		assert.deepEqual(bash.content, [{ type: "text", text: "SENTINEL\n" }]);
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});

void test("native write and edit can change scratch but not captured evidence", () => {
	const cwd = mkdtempSync(join(tmpdir(), "native-scratch-"));
	const scratch = join(cwd, "scratch");
	mkdirSync(scratch);
	writeFileSync(join(cwd, "evidence.txt"), "captured\n");
	try {
		execFileSync(
			process.execPath,
			[
				"--permission",
				"--allow-fs-read=*",
				`--allow-fs-write=${scratch}`,
				"--input-type=module",
				"--eval",
				`
			import assert from "node:assert/strict";
			import { createWriteTool, createEditTool } from "@earendil-works/pi-coding-agent";
			const cwd = process.argv[1];
			const write = createWriteTool(cwd);
			const edit = createEditTool(cwd);
			await write.execute("write", { path: "scratch/probe.txt", content: "before\\n" });
			await edit.execute("edit", { path: "scratch/probe.txt", edits: [{ oldText: "before", newText: "after" }] });
			await assert.rejects(write.execute("write-evidence", { path: "evidence.txt", content: "replaced" }));
			await assert.rejects(edit.execute("edit-evidence", { path: "evidence.txt", edits: [{ oldText: "captured", newText: "replaced" }] }));
			`,
				cwd,
			],
			{ timeout: 15_000 },
		);
		assert.equal(readFileSync(join(scratch, "probe.txt"), "utf8"), "after\n");
		assert.equal(readFileSync(join(cwd, "evidence.txt"), "utf8"), "captured\n");
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});
