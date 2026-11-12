import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

void test("the live voice eval cannot execute a model's command or read a host file", () => {
	const root = mkdtempSync(path.join(tmpdir(), "heph-voice-tools-"));
	const marker = path.join(root, "command-ran");
	const outside = path.join(root, "host-only.txt");
	writeFileSync(outside, "This file is outside the supplied voice fixture.");
	const harness = new URL("heph-voice/harness.ts", import.meta.url).href;
	const command = `${JSON.stringify(process.execPath)} -e ${JSON.stringify(`require('node:fs').writeFileSync(${JSON.stringify(marker)}, 'ran')`)}`;
	try {
		const child = spawnSync(
			process.execPath,
			[
				"--experimental-test-module-mocks",
				"--input-type=module",
				"-e",
				`import assert from 'node:assert/strict';
await import(${JSON.stringify(harness)});
const sdk = await import('@earendil-works/pi-coding-agent');
const bash = sdk.createBashToolDefinition(${JSON.stringify(root)}, {exposeSessionEnvironment: false});
await assert.rejects(bash.execute('eval', {command: ${JSON.stringify(command)}}), /the eval owns this context folder/);
const read = sdk.createReadToolDefinition(${JSON.stringify(root)});
await assert.rejects(read.execute('eval', {path: ${JSON.stringify(outside)}}), /the eval owns this context folder/);`,
			],
			{ encoding: "utf8" },
		);
		assert.equal(child.error, undefined);
		assert.equal(child.status, 0, child.stderr);
		assert.equal(existsSync(marker), false, "the model's command did not run on the host");
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
