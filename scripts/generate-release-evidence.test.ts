import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";

import {
	buildManifest,
	evidenceStem,
	PLATFORMS,
	planEvidenceImages,
	planEvidenceSubjects,
} from "./generate-release-evidence.ts";
import { planSubjects } from "./scan-main-images.ts";
import { validateManifest } from "./verify-release-evidence.ts";

const NAMESPACE = "ghcr.io/hephaestus-build";
const INDEX = `sha256:${"a".repeat(64)}`;
const platformDigest = (image: { image: string }, platform: string): string =>
	`sha256:${Buffer.from(`${image.image}${platform}`).toString("hex").padEnd(64, "0").slice(0, 64)}`;

async function inventory(): Promise<unknown> {
	return JSON.parse(await readFile("security/release-images.json", "utf8"));
}

async function evidenceImages(): Promise<ReturnType<typeof planEvidenceImages>> {
	const document = await inventory();
	return planEvidenceImages(
		planSubjects(document, NAMESPACE, "run-1-1").map(({ image, repository }) => ({
			image,
			indexDigest: INDEX,
			repository,
		})),
		document,
	);
}

void describe("the release evidence generator", () => {
	void test("emits a manifest the release evidence gate accepts, for the committed inventory", async () => {
		// The parity that matters is not that two lists agree, but that the only generator the
		// release and the pre-merge preflight have produces exactly the subject set the gate demands.
		// validateManifest rejects a manifest whose subjects are not exactly the inventory, on exactly
		// the two platforms, in canonical order — so an image added to security/release-images.json
		// and not to the generator, or the reverse, fails here rather than mid-release.
		const document = await inventory();
		const manifest = buildManifest(planEvidenceSubjects(await evidenceImages(), platformDigest), {
			commit: "f".repeat(40),
			durationSeconds: 12,
			generatedAt: "2026-09-02T00:00:00Z",
			release: "v0.75.0",
		});
		assert.doesNotThrow(() => validateManifest(manifest, document, NAMESPACE));
		assert.equal(manifest.schemaVersion, 1);
	});

	void test("evidences both platforms of every image, first-party and upstream alike", async () => {
		const images = await evidenceImages();
		assert.deepEqual(images.map(({ image }) => image).toSorted(), [
			"agent-pi",
			"alpine",
			"application-server",
			"nats",
			"nginx",
			"postgres",
			"traefik",
			"webapp",
		]);
		// The upstream half keeps the digest the inventory pins rather than one resolved from a tag:
		// the release promotes that artefact, and a tag names whatever it points at today.
		const upstream = images.filter(({ provenance }) => provenance === "upstream");
		assert.equal(upstream.length, 4);
		for (const image of upstream) assert.match(image.indexDigest, /^sha256:[a-f0-9]{64}$/);

		const subjects = planEvidenceSubjects(images, platformDigest);
		assert.equal(subjects.length, images.length * 2);
		assert.deepEqual([...PLATFORMS], ["linux/amd64", "linux/arm64"]);
		for (const platform of PLATFORMS)
			assert.equal(
				subjects.filter((subject) => subject.platform === platform).length,
				images.length,
			);
	});

	void test("refuses a subject whose platform digest the registry did not answer for", async () => {
		const images = await evidenceImages();
		assert.throws(() => planEvidenceSubjects(images, () => ""), /digest is malformed: <empty>/);
		assert.throws(() => planEvidenceSubjects(images, () => "sha256:nope"), /digest is malformed/);
	});

	void test("names each subject's documents the way the verifier reads them back", () => {
		assert.equal(evidenceStem({ image: "webapp", platform: "linux/amd64" }), "webapp-linux-amd64");
		assert.equal(evidenceStem({ image: "nats", platform: "linux/arm64" }), "nats-linux-arm64");
	});
});

void test(
	"one combined scan supplies both evidence reports and scanner failures stay fatal",
	{ skip: process.platform === "win32" },
	async (context) => {
		const directory = await mkdtemp(path.join(tmpdir(), "combined-evidence-"));
		context.after(() => rm(directory, { recursive: true, force: true }));
		await writeFile(path.join(directory, "syft"), "#!/bin/sh\nexit 0\n", { mode: 0o755 });
		await writeFile(
			path.join(directory, "trivy"),
			`#!/bin/sh
[ "$1" = image ] && [ "$2" = --skip-db-update ] && [ "$3" = --scanners ] && [ "$4" = vuln,license ] || exit 90
[ "$5" = --format ] && [ "$6" = json ] && [ "$7" = --output ] || exit 91
printf 'scan\\n' >> "$SCAN_LOG"
[ "$SCAN_EXIT" = 0 ] || exit "$SCAN_EXIT"
printf '%s' '{"Results":[{"Vulnerabilities":[{"VulnerabilityID":"CVE-example"}],"Licenses":[{"Name":"MIT"}]}]}' > "$8"
`,
			{ mode: 0o755 },
		);
		const subjects = planEvidenceSubjects(await evidenceImages(), platformDigest);
		const subject = subjects[0];
		assert.ok(subject);
		const scanLog = path.join(directory, "scans");
		const invoke = (exit: string) =>
			spawnSync(
				process.execPath,
				[
					"--input-type=module",
					"-e",
					`
import { captureSubject } from ${JSON.stringify(new URL("./generate-release-evidence.ts", import.meta.url).href)};
await captureSubject(${JSON.stringify(subject)}, ${JSON.stringify(directory)});
`,
				],
				{
					env: {
						...process.env,
						PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
						SCAN_LOG: scanLog,
						SCAN_EXIT: exit,
					},
					encoding: "utf8",
				},
			);
		const success = invoke("0");
		assert.equal(success.status, 0, success.stderr);
		assert.equal(await readFile(scanLog, "utf8"), "scan\n");
		const prefix = path.join(directory, evidenceStem(subject));
		assert.equal(
			await readFile(`${prefix}.license.json`, "utf8"),
			await readFile(`${prefix}.trivy.json`, "utf8"),
		);
		assert.match(await readFile(`${prefix}.license.json`, "utf8"), /CVE-example/);
		assert.match(await readFile(`${prefix}.license.json`, "utf8"), /MIT/);
		const failed = invoke("17");
		assert.notEqual(failed.status, 0);
		assert.match(failed.stderr, /trivy exited with code 17/);
		await rm(`${prefix}.trivy.json`);
		await writeFile(path.join(directory, "trivy"), "#!/bin/sh\nexit 0\n", { mode: 0o755 });
		assert.notEqual(invoke("0").status, 0, "A successful scanner without a report must fail");
	},
);
