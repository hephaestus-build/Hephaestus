import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";

import { staleInstall } from "./install.ts";

const root = mkdtempSync(path.join(tmpdir(), "stale-install-"));
after(() => rmSync(root, { recursive: true, force: true }));

const lockfile = (...versions: string[]) =>
	`lockfileVersion: '9.0'\n\nsnapshots:\n\n${versions.map((version) => `  ${version}: {}\n`).join("\n")}`;

void test("an install from an older lockfile is stale by each locked version it lacks", () => {
	mkdirSync(path.join(root, "node_modules", ".pnpm"), { recursive: true });
	// pnpm 12 writes its own pin as a first document; the installed copy lacks it.
	writeFileSync(
		path.join(root, "pnpm-lock.yaml"),
		`---\nlockfileVersion: '9.0'\nimporters: {}\n---\n${lockfile("terser@5.51.2", "react@19.2.8")}`,
	);
	writeFileSync(
		path.join(root, "node_modules", ".pnpm", "lock.yaml"),
		lockfile("terser@5.51.0", "react@19.2.8"),
	);
	assert.equal(staleInstall(root), 1);
	writeFileSync(
		path.join(root, "node_modules", ".pnpm", "lock.yaml"),
		lockfile("terser@5.51.2", "react@19.2.8"),
	);
	assert.equal(staleInstall(root), 0);
});
