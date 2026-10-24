import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
	chmodSync,
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	rmSync,
	symlinkSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { setTimeout } from "node:timers/promises";

import { isMap, isSeq, parseDocument } from "yaml";

const amd64 = `sha256:${"a".repeat(64)}`;
const arm64 = `sha256:${"b".repeat(64)}`;
const artifact = "c".repeat(64);
const index = `sha256:${artifact}`;
const repository = "ghcr.io/example/webapp";
const reference = `${repository}@${index}`;
const checker = path.join(import.meta.dirname, "resolve-image-scan-subject.ts");

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
		name: "selects arm64 without scanning the host platform or an attestation",
		platform: "linux/arm64",
		raw: {
			manifests: [
				{ digest: index, platform: { os: "unknown", architecture: "unknown" } },
				{ digest: amd64, platform: { os: "linux", architecture: "amd64" } },
				{ digest: arm64, platform: { os: "linux", architecture: "arm64" } },
			],
		},
		expected: arm64,
	},
	{
		name: "rejects a missing arm64 manifest instead of scanning amd64",
		platform: "linux/arm64",
		raw: { manifests: [{ digest: amd64, platform: { os: "linux", architecture: "amd64" } }] },
	},
	{
		name: "rejects an unsupported scan platform before inspecting the image",
		platform: "linux/386",
		raw: {},
		noInspect: true,
	},
	{
		name: "requires an explicit scan platform",
		platform: "",
		raw: {},
		noInspect: true,
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
		name: "rejects a repository that already names a digest before inspecting it",
		raw: {},
		imageRepository: `ghcr.io/example/webapp@${amd64}`,
		noInspect: true,
	},
	{
		name: "rejects output injection in the image name",
		raw: {},
		imageName: "webapp\ndigest=bad",
		noInspect: true,
	},
	{
		name: "rejects a trailing newline in the repository before inspection",
		raw: {},
		imageRepository: "ghcr.io/example/webapp\n",
		noInspect: true,
	},
	{
		name: "rejects a trailing newline in the image before inspection",
		raw: {},
		imageName: "webapp\n",
		noInspect: true,
	},
	{
		name: "resolves a Buildpacks leaf from its own platform artifact",
		raw: { config: {}, layers: [] },
		expected: index,
		formatLookup: true,
	},
	{ name: "rejects a missing build digest", artifactNames: [], raw: {}, noInspect: true },
	{
		name: "rejects multiple build digests",
		artifactNames: ["a".repeat(64), "b".repeat(64)],
		raw: {},
		noInspect: true,
	},
	{
		name: "rejects a malformed digest file name",
		artifactNames: ["bad"],
		raw: {},
		noInspect: true,
	},
	{
		name: "rejects a digest symlink",
		artifactNames: ["c".repeat(64)],
		artifactSymlink: true,
		raw: {},
		noInspect: true,
	},
	{ name: "requires the build digest artifact", noDirectory: true, raw: {}, noInspect: true },
] as const) {
	void test(scenario.name, { skip: process.platform === "win32" }, (t) => {
		const directory = mkdtempSync(path.join(tmpdir(), "image-scan-subject-"));
		t.after(() => rmSync(directory, { recursive: true, force: true }));
		const docker = path.join(directory, "docker");
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
		const output = path.join(directory, "output");
		const callLog = path.join(directory, "calls");
		writeFileSync(output, "");
		writeFileSync(callLog, "");
		const artifactDirectory = path.join(directory, "digest");
		const artifactNames = ("artifactNames" in scenario ? scenario.artifactNames : undefined) ?? [
			artifact,
		];
		if (!("noDirectory" in scenario)) {
			mkdirSync(artifactDirectory);
			for (const name of artifactNames) {
				if ("artifactSymlink" in scenario) {
					symlinkSync(output, path.join(artifactDirectory, name));
				} else {
					writeFileSync(path.join(artifactDirectory, name), "");
				}
			}
		}
		const platform = ("platform" in scenario ? scenario.platform : undefined) ?? "linux/amd64";
		const result = spawnSync(process.execPath, [checker], {
			encoding: "utf8",
			env: {
				...process.env,
				PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
				DIGEST_DIRECTORY: "noDirectory" in scenario ? "" : artifactDirectory,
				IMAGE_REPOSITORY: "imageRepository" in scenario ? scenario.imageRepository : repository,
				SCAN_PLATFORM: platform,
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
		if (scenario.expected === undefined) {
			assert.notEqual(result.status, 0);
			assert.equal(emitted, "", "failed resolution must publish no usable scan subject");
		} else {
			assert.equal(result.status, 0, result.stderr);
			assert.equal(
				emitted,
				`image=webapp\ndigest=${scenario.expected}\nreport-stem=webapp-${platform.replaceAll("/", "-")}\n`,
			);
		}
		if ("noInspect" in scenario) {
			assert.equal(calls, "");
		} else {
			const expectedCalls = [["buildx", "imagetools", "inspect", reference, "--raw"]];
			if ("formatLookup" in scenario) {
				expectedCalls.push([
					"buildx",
					"imagetools",
					"inspect",
					"--format",
					"{{.Manifest.Digest}}",
					reference,
				]);
			}
			assert.equal(calls, expectedCalls.map((args) => `${JSON.stringify(args)}\n`).join(""));
		}
	});
}

for (const stalledLookup of ["raw", "fallback"] as const) {
	void test(
		`aborts a stalled ${stalledLookup} lookup without publishing a subject`,
		{ skip: process.platform === "win32" },
		async (t) => {
			const directory = mkdtempSync(path.join(tmpdir(), "image-scan-deadline-"));
			const pidFile = path.join(directory, "pid");
			const terminated = path.join(directory, "terminated");
			t.after(() => {
				try {
					process.kill(Number(readFileSync(pidFile, "utf8")), "SIGKILL");
				} catch {
					// The fixture usually exits on the resolver's SIGTERM; clean up a baseline failure too.
				}
				rmSync(directory, { recursive: true, force: true });
			});
			const docker = path.join(directory, "docker");
			const calls = path.join(directory, "calls");
			const deadlineCalls = path.join(directory, "deadlines");
			const outputFile = path.join(directory, "output");
			const preload = path.join(directory, "deadline.mjs");
			const artifactDirectory = path.join(directory, "digest");
			mkdirSync(artifactDirectory);
			writeFileSync(path.join(artifactDirectory, artifact), "");
			writeFileSync(outputFile, "");
			writeFileSync(
				docker,
				String.raw`#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(process.env.CALL_LOG, JSON.stringify(args) + '\n');
if (process.env.STALLED_LOOKUP === 'fallback' && args.includes('--raw')) {
 process.stdout.write(JSON.stringify({ config: {}, layers: [] }));
 process.exit(0);
}
process.on('SIGTERM', () => {
 fs.writeFileSync(process.env.TERMINATED, 'SIGTERM');
 process.exit(0);
});
fs.writeFileSync(process.env.PID_FILE, String(process.pid));
setInterval(() => {}, 1000);
// The resolver can abort once this child can record and handle the signal.
process.kill(Number(process.env.RESOLVER_DEADLINE_PID), 'SIGUSR2');
`,
			);
			chmodSync(docker, 0o755);
			writeFileSync(
				preload,
				String.raw`import assert from 'node:assert/strict';
import { appendFileSync } from 'node:fs';
import { mock } from 'node:test';
const deadline = new AbortController();
process.env.RESOLVER_DEADLINE_PID = String(process.pid);
process.once('SIGUSR2', () => deadline.abort(new DOMException('deadline', 'TimeoutError')));
mock.method(AbortSignal, 'timeout', (milliseconds) => {
 assert.equal(milliseconds, 120000);
 appendFileSync(process.env.DEADLINE_LOG, 'deadline\n');
 return deadline.signal;
});
`,
			);
			const result = spawnSync(process.execPath, ["--import", preload, checker], {
				encoding: "utf8",
				timeout: 3000,
				killSignal: "SIGKILL",
				env: {
					...process.env,
					PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
					DIGEST_DIRECTORY: artifactDirectory,
					IMAGE_REPOSITORY: repository,
					SCAN_PLATFORM: "linux/amd64",
					INPUT_IMAGE_NAME: "example/webapp",
					GITHUB_OUTPUT: outputFile,
					CALL_LOG: calls,
					DEADLINE_LOG: deadlineCalls,
					STALLED_LOOKUP: stalledLookup,
					PID_FILE: pidFile,
					TERMINATED: terminated,
				},
			});
			assert.equal(
				result.error,
				undefined,
				"the resolver must abort before the outer test deadline",
			);
			assert.equal(result.status, 1, result.stderr);
			assert.match(result.stderr, /AbortError/u);
			assert.equal(readFileSync(outputFile, "utf8"), "");
			assert.equal(
				readFileSync(deadlineCalls, "utf8"),
				"deadline\n",
				"both lookups share one deadline",
			);
			const pid = Number(readFileSync(pidFile, "utf8"));
			let childExited = false;
			for (let attempt = 0; attempt < 20; attempt += 1) {
				try {
					process.kill(pid, 0);
				} catch {
					childExited = true;
					break;
				}
				await setTimeout(25);
			}
			assert.equal(childExited, true, "the actual Docker fixture must terminate");
			assert.equal(readFileSync(terminated, "utf8"), "SIGTERM");
			const expectedCalls = [["buildx", "imagetools", "inspect", reference, "--raw"]];
			if (stalledLookup === "fallback") {
				expectedCalls.push([
					"buildx",
					"imagetools",
					"inspect",
					"--format",
					"{{.Manifest.Digest}}",
					reference,
				]);
			}
			assert.equal(
				readFileSync(calls, "utf8"),
				expectedCalls.map((args) => `${JSON.stringify(args)}\n`).join(""),
			);
		},
	);
}

void test("the image workflow shares the resolver and retains its immutable subject and platform", () => {
	const workflow = parseDocument(
		readFileSync(".github/workflows/reusable-docker-build.yml", "utf8"),
	);
	const steps = workflow.getIn(["jobs", "scan", "steps"]);
	assert.ok(isSeq(steps));
	assert.equal(workflow.getIn(["jobs", "scan", "env", "SCAN_PLATFORM"]), `\${{ matrix.platform }}`);
	const subject = steps.items.find((item) => isMap(item) && item.get("id") === "subject");
	assert.ok(isMap(subject));
	assert.equal(subject.get("run"), "node scripts/resolve-image-scan-subject.ts");
	assert.equal(subject.getIn(["env", "INPUT_IMAGE_NAME"]), `\${{ inputs.image-name }}`);
	assert.equal(
		subject.getIn(["env", "DIGEST_DIRECTORY"]),
		`\${{ runner.temp }}/scan-digest/\${{ steps.prep.outputs.platform_pair }}`,
	);
	assert.equal(
		subject.getIn(["env", "IMAGE_REPOSITORY"]),
		`\${{ inputs.registry }}/\${{ inputs.image-name }}`,
	);
});

void test("every digest artifact carries its platform directory, however many a download matches", () => {
	const workflow = parseDocument(
		readFileSync(".github/workflows/reusable-docker-build.yml", "utf8"),
	);
	// A download extracts a single matched artifact straight into its path and several into one
	// subdirectory each, so a layout that depended on the count broke the one-platform merge.
	const step = (job: string, name: string) => {
		const steps = workflow.getIn(["jobs", job, "steps"]);
		assert.ok(isSeq(steps));
		const found: unknown = steps.items.find((item) => isMap(item) && item.get("name") === name);
		assert.ok(isMap(found), `${job}: ${name}`);
		return found;
	};
	const directory = mkdtempSync(path.join(tmpdir(), "digest-artifact-"));
	try {
		const exported = spawnSync(
			"bash",
			["-e", "-c", String(step("build", "Export digest").get("run"))],
			{
				encoding: "utf8",
				env: {
					...process.env,
					RUNNER_TEMP: directory,
					PLATFORM_PAIR: "linux-arm64",
					DOCKERFILE_DIGEST: `sha256:${artifact}`,
					BUILDPACKS_DIGEST: "",
				},
			},
		);
		assert.equal(exported.status, 0, exported.stderr);
		const entries = readdirSync(path.join(directory, "digests"), {
			encoding: "utf8",
			recursive: true,
		});
		assert.deepEqual(
			entries.toSorted((left, right) => left.localeCompare(right)),
			["linux-arm64", path.join("linux-arm64", artifact)],
		);
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
	// The upload's root is the digests directory, so the artifact holds `<platform>/<digest>`.
	assert.equal(
		step("build", "Upload digest").getIn(["with", "path"]),
		`\${{ runner.temp }}/digests`,
	);
	assert.equal(step("merge", "Download digests").getIn(["with", "merge-multiple"]), true);
	assert.match(
		String(step("merge", "Create manifest and push").get("run")),
		/files=\("linux-\$arch"\/\*\)/u,
	);
	assert.equal(workflow.getIn(["jobs", "scan", "needs"]), "build");
	const scan = step("scan", "Download platform digest");
	assert.equal(
		scan.getIn(["with", "name"]),
		`digests-\${{ steps.prep.outputs.image_name }}-\${{ steps.prep.outputs.platform_pair }}`,
	);
	assert.equal(scan.getIn(["with", "path"]), `\${{ runner.temp }}/scan-digest`);
});
