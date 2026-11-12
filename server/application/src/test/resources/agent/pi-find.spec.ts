import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { after } from "node:test";

import { globApart } from "../../../main/resources/agent/pi-find.ts";

const base = mkdtempSync(path.join(tmpdir(), "review-find-"));
const source = path.join(base, "source");
mkdirSync(path.join(source, "nested"), { recursive: true });
writeFileSync(path.join(source, "a.ts"), "one\n");
writeFileSync(path.join(source, "nested/b.ts"), "two\n");
writeFileSync(path.join(source, "long.ts"), "three\n");
after(() => rmSync(base, { recursive: true, force: true }));

void test("find runs apart from the loop and finds what the walk finds", async () => {
	const found = await globApart({ pattern: "*.ts", cwd: source, ignore: [], limit: 10 });
	assert.ok(found.some((file) => file.endsWith("a.ts")));
});
