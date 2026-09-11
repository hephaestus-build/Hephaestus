import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

void test("CDS reporting collapses only known archive exclusions and retains raw evidence", (t) => {
	const directory = mkdtempSync(path.join(tmpdir(), "buildpack-log-"));
	t.after(() => rmSync(directory, { recursive: true, force: true }));
	const log = path.join(directory, "raw.log");
	const prefix = "[builder] [29.202s][warning][cds] ";
	const input = `${[
		`${prefix}Skipping jdk/proxy1/$Proxy1: Unsupported location`,
		`${prefix}Skipping jdk/proxy1/$Proxy2: Unsupported location`,
		`${prefix}Skipping signed/Class: Signed JAR`,
		`${prefix}Skipping optional/Class: Failed verification`,
		`${prefix}Preload Warning: Verification failed for io.micrometer.core.instrument.binder.logging.Log4j2Metrics`,
		`${prefix}Skipping io/micrometer/core/instrument/binder/logging/Log4j2Metrics: Failed verification`,
		`${prefix}Preload Warning: Verification failed for optional.Class`,
		`${prefix}Skipping future/Class: Unknown exclusion`,
		"ERROR: archive creation failed",
	].join("\n")}\n`;
	const result = spawnSync(process.execPath, ["scripts/summarize-buildpack-log.ts", log], {
		input,
		encoding: "utf8",
	});
	assert.equal(result.status, 0, result.stderr);
	assert.equal(readFileSync(log, "utf8"), input);
	assert.match(result.stdout, /Proxy1/);
	assert.doesNotMatch(result.stdout, /Proxy2/);
	assert.match(result.stdout, /Unsupported location: 2/);
	assert.match(result.stdout, /Signed JAR: 1/);
	// Past linkage evidence is not a permanent exemption: the same adapter can fail differently.
	assert.ok(
		result.stdout.includes(
			`${prefix}Preload Warning: Verification failed for io.micrometer.core.instrument.binder.logging.Log4j2Metrics`,
		),
	);
	assert.ok(
		result.stdout.includes(
			`${prefix}Skipping io/micrometer/core/instrument/binder/logging/Log4j2Metrics: Failed verification`,
		),
	);
	for (const diagnostic of [
		"Failed verification",
		"Preload Warning",
		"Unknown exclusion",
		"ERROR:",
	])
		assert.ok(result.stdout.includes(diagnostic), diagnostic);
});
