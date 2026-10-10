import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { evidenceTools } from "./evidence-tools.ts";

void test("the repository tools read no file outside the repository, also not through a link", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "evidence-tools-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	const repo = path.join(root, "repo");
	await mkdir(repo);
	await writeFile(path.join(root, "secret.txt"), "token\n");
	await writeFile(path.join(repo, "inside.txt"), "kept\n");
	await symlink(path.join(root, "secret.txt"), path.join(repo, "link.txt"));
	const { execute } = evidenceTools(repo).readFile;
	const read = async (file: string): Promise<unknown> =>
		execute({ path: file }, { toolCallId: "read", messages: [], context: {} });

	assert.equal(await read("inside.txt"), "1: kept\n2: ");
	await assert.rejects(read("../secret.txt"), /outside the repository/u);
	await assert.rejects(read("link.txt"), /outside the repository/u);
});
