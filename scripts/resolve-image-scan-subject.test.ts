import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, join } from "node:path";
import { test } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

const amd64 = `sha256:${"a".repeat(64)}`;
const arm64 = `sha256:${"b".repeat(64)}`;
const index = `sha256:${"c".repeat(64)}`;
const reference = `ghcr.io/example/webapp@${index}`;
const checker = join(import.meta.dirname, "resolve-image-scan-subject.ts");

// The CLI is used only by the Linux image-scan job. Its Docker process boundary uses a POSIX
// executable fixture; pure manifest-selection tests also run on Windows in scan-main-images.test.ts.
for (const scenario of [
	{
		name: "selects amd64 after arm64 and attestation entries",
		raw: {
			manifests: [
				{ digest: index, platform: { os: "unknown", architecture: "unknown" } },
				{ digest: arm64, platform: { os: "linux", architecture: "arm64" } },
				{ digest: amd64, platform: { os: "linux", architecture: "amd64" } },
			],
		},
		expected: amd64,
	},
	{
		name: "resolves a single manifest through the original immutable reference",
		raw: { config: {}, layers: [] },
		expected: index,
		formatLookup: true,
	},
	{
		name: "rejects a missing platform instead of scanning the index",
		raw: { manifests: [{ digest: arm64, platform: { os: "linux", architecture: "arm64" } }] },
	},
	{
		name: "rejects an invalid selected digest",
		raw: { manifests: [{ digest: "bad", platform: { os: "linux", architecture: "amd64" } }] },
	},
	{
		name: "rejects an invalid single-manifest digest",
		raw: { config: {}, layers: [] },
		singleDigest: "bad",
		formatLookup: true,
	},
	{ name: "rejects a malformed index declaration", raw: { manifests: null } },
	{ name: "rejects malformed registry JSON", rawText: "{" },
	{ name: "propagates registry failure", raw: { config: {}, layers: [] }, dockerExit: "7" },
	{
		name: "rejects mutable references before inspecting them",
		raw: {},
		imageRef: "ghcr.io/example/webapp:main",
		noInspect: true,
	},
	{
		name: "rejects output injection in the image name",
		raw: {},
		imageName: "webapp\ndigest=bad",
		noInspect: true,
	},
	{
		name: "rejects a trailing newline in the reference before inspection",
		raw: {},
		imageRef: `${reference}\n`,
		noInspect: true,
	},
	{
		name: "rejects a trailing newline in the image before inspection",
		raw: {},
		imageName: "webapp\n",
		noInspect: true,
	},
] as const) {
	void test(scenario.name, { skip: process.platform === "win32" }, (t) => {
		const directory = mkdtempSync(join(tmpdir(), "image-scan-subject-"));
		t.after(() => rmSync(directory, { recursive: true, force: true }));
		const docker = join(directory, "docker");
		writeFileSync(
			docker,
			`#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(process.env.CALL_LOG, JSON.stringify(args) + '\\n');
if (process.env.DOCKER_EXIT) process.exit(Number(process.env.DOCKER_EXIT));
process.stdout.write(args.includes('--raw') ? process.env.RAW : process.env.SINGLE_DIGEST);
`,
		);
		chmodSync(docker, 0o755);
		const output = join(directory, "output");
		const callLog = join(directory, "calls");
		writeFileSync(output, "");
		writeFileSync(callLog, "");
		const imageRef = "imageRef" in scenario ? scenario.imageRef : reference;
		const result = spawnSync(process.execPath, [checker], {
			encoding: "utf8",
			env: {
				...process.env,
				PATH: `${directory}${delimiter}${process.env.PATH ?? ""}`,
				IMAGE_REF: imageRef,
				INPUT_IMAGE_NAME: "imageName" in scenario ? scenario.imageName : "example/webapp",
				GITHUB_OUTPUT: output,
				RAW: "rawText" in scenario ? scenario.rawText : JSON.stringify(scenario.raw),
				SINGLE_DIGEST: "singleDigest" in scenario ? scenario.singleDigest : index,
				DOCKER_EXIT: "dockerExit" in scenario ? scenario.dockerExit : "",
				CALL_LOG: callLog,
			},
		});
		const emitted = readFileSync(output, "utf8");
		const calls = readFileSync(callLog, "utf8");
		if ("expected" in scenario) {
			assert.equal(result.status, 0, result.stderr);
			assert.equal(emitted, `image=webapp\ndigest=${scenario.expected}\n`);
		} else {
			assert.notEqual(result.status, 0);
			assert.equal(emitted, "", "failed resolution must publish no usable scan subject");
		}
		if ("noInspect" in scenario) assert.equal(calls, "");
		else {
			const expectedCalls = [["buildx", "imagetools", "inspect", imageRef, "--raw"]];
			if ("formatLookup" in scenario)
				expectedCalls.push([
					"buildx",
					"imagetools",
					"inspect",
					"--format",
					"{{.Manifest.Digest}}",
					imageRef,
				]);
			assert.equal(calls, expectedCalls.map((args) => `${JSON.stringify(args)}\n`).join(""));
		}
	});
}

void test("the image workflow shares the resolver and retains its immutable subject and platform", () => {
	const workflow = parseDocument(
		readFileSync(".github/workflows/reusable-docker-build.yml", "utf8"),
	);
	const steps = workflow.getIn(["jobs", "scan", "steps"]);
	assert.ok(isSeq(steps));
	const subject = steps.items.find((item) => isMap(item) && item.get("id") === "subject");
	assert.ok(isMap(subject));
	assert.equal(subject.get("run"), "node scripts/resolve-image-scan-subject.ts");
	assert.equal(subject.getIn(["env", "INPUT_IMAGE_NAME"]), `\${{ inputs.image-name }}`);
	assert.equal(
		subject.getIn(["env", "IMAGE_REF"]),
		`\${{ inputs.registry }}/\${{ inputs.image-name }}@\${{ inputs.single-arch && needs.build.outputs.manifest-digest || needs.merge.outputs.manifest-digest }}`,
	);
});
