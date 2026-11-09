import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";

await test("the trusted runtime build emits JavaScript and removes obsolete files", async () => {
	const repository = path.resolve(import.meta.dirname, "..");
	await mkdir(path.join(repository, "tmp"), { recursive: true });
	const root = await mkdtemp(path.join(repository, "tmp/compiled-agent-"));
	const output = path.join(root, "runtime");
	await mkdir(output);
	await writeFile(path.join(output, "obsolete.js"), "obsolete");
	try {
		const built = spawnSync(
			process.execPath,
			[path.join(repository, "scripts/compile-agent-runtime.ts"), output],
			{
				cwd: root,
				encoding: "utf8",
				timeout: 30_000,
			},
		);
		assert.ifError(built.error);
		assert.equal(built.status, 0, built.stderr);
		const files = await readdir(output);
		assert.ok(files.includes("pi-runner.js"));
		assert.ok(files.includes("heph-host.js"));
		assert.ok(!files.some((file) => file.endsWith(".ts")));
		await assert.rejects(readFile(path.join(output, "obsolete.js")), { code: "ENOENT" });
		const refused = spawnSync(
			process.execPath,
			[path.join(repository, "scripts/compile-agent-runtime.ts"), "."],
			{ cwd: root, encoding: "utf8", timeout: 30_000 },
		);
		assert.ifError(refused.error);
		assert.notEqual(refused.status, 0);
		assert.match(refused.stderr, /absolute runtime output directory/u);
		const retained = await readFile(path.join(output, "pi-runner.js"), "utf8");
		assert.ok(retained.length > 0);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});
