import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";
import { isMap, isScalar, isSeq, visit } from "yaml";
import { evaluate as evaluateVulnerabilityPolicy } from "./check-release-vulnerabilities.ts";
import {
	assertNeeds,
	type Completion,
	exceptionFault,
	expandExpressions,
	importClosure,
	job,
	jobRuns,
	namedStep,
	needsClosure,
	needsOf,
	parseDocument,
	pathFilter,
	posixGlob,
	readSources,
	step,
	stepInputs,
	taskClosure,
	workflowSources,
} from "./ci-contract-support.ts";
import { ciToolPin } from "./lib/ci-tool-catalog.ts";
import { isSet } from "./lib/env.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { asArray, asRecord, asString, asStringArray, isRecord } from "./lib/json.ts";
import { loadTasks } from "./lib/task-graph.ts";
import { INSTALL_ARGUMENTS, INSTALL_ENVIRONMENT } from "./maintain-version-pr.ts";
import { planRelease, releaseOutputs } from "./plan-release.ts";
import { planSubjects } from "./scan-main-images.ts";
import { PLATFORMS, planUpstreamSubjects } from "./scan-upstream-images.ts";
import { validateManifest } from "./verify-release-evidence.ts";

void describe("ci-contract: release", () => {
	void test("every shipped-code quality leg admits owned source into the release generator", async () => {
		const tasks = await loadTasks();
		for (const leg of [
			"quality",
			"ci:server",
			"ci:tooling",
			"ci:webapp",
			"ci:extension",
			"ci:windows",
		]) {
			assert.ok(
				taskClosure(tasks, [leg]).has("gate:owned-code"),
				`${leg} must check shipped owned code`,
			);
		}
	});

	void test("a crash of the rescan of main opens or updates its own tracking issue", async (context) => {
		const rescan = parseDocument(await readFile(".github/workflows/rescan-images.yml", "utf8"));
		const report = namedStep(rescan, ["jobs", "main"], "Report a failed rescan");
		assert.equal(report.get("if"), "failure()");
		assert.equal(rescan.getIn(["jobs", "main", "permissions", "issues"]), "write");
		const script = asString(report.get("run"), "report script");
		const directory = await mkdtemp(path.join(tmpdir(), "rescan-report-"));
		context.after(async () => rm(directory, { recursive: true, force: true }));
		const log = path.join(directory, "calls.log");
		const answer = path.join(directory, "issues.json");
		await writeFile(
			path.join(directory, "gh"),
			`#!/bin/sh\necho "$*" >> "${log}"\n[ "$1 $2" = "issue list" ] && cat "${answer}"\nexit 0\n`,
		);
		await chmod(path.join(directory, "gh"), 0o755);
		const run = async (listed: string) => {
			await writeFile(log, "");
			await writeFile(answer, listed);
			const result = spawnSync("bash", ["--noprofile", "--norc", "-c", script], {
				encoding: "utf8",
				env: environmentForGitFixture({
					PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
					REPOSITORY: "owner/repository",
					RUN_URL: "https://example.invalid/run",
				}),
			});
			assert.equal(result.status, 0, result.stderr);
			const calls = await readFile(log, "utf8");
			return calls.trim().split("\n");
		};
		const title = "The rescan of main's images failed";
		const workflow = { login: "app/github-actions" };
		const first = await run("[]");
		assert.match(first[0] ?? "", /^issue list .*--json number,title,author/u);
		assert.match(first[1] ?? "", /^issue create /u);
		const again = await run(JSON.stringify([{ number: 7, title, author: workflow }]));
		assert.match(again[1] ?? "", /^issue comment 7 /u);
		// The same title from anyone else is not the tracking issue.
		const planted = await run(
			JSON.stringify([{ number: 8, title, author: { login: "stranger" } }]),
		);
		assert.match(planted[1] ?? "", /^issue create /u);
	});

	void test("GHCR-only rescans share login while the image builder keeps its registry input", async () => {
		const rescan = parseDocument(await readFile(".github/workflows/rescan-images.yml", "utf8"));
		const login = namedStep(rescan, ["jobs", "main"], "Log in to Container Registry");
		assert.equal(login.get("uses"), "./.github/actions/ghcr-login");
		assert.equal(login.getIn(["with", "username"]), `\${{ github.actor }}`);
		assert.equal(login.getIn(["with", "password"]), `\${{ secrets.GITHUB_TOKEN }}`);
		const reusable = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		for (const name of ["build", "merge"]) {
			const registry = step(reusable, ["jobs", name], "docker/login-action");
			assert.equal(registry.get("registry"), `\${{ inputs.registry }}`);
		}
	});

	void test("image scans are delegated only to required release preflight", async () => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		assert.equal(
			workflow.getIn(["jobs", "detect-changes", "outputs", "release-preflight"]),
			`\${{ (github.event_name == 'workflow_dispatch' && inputs.release-preflight) || steps.release_candidate.outputs.release-candidate == 'true' }}`,
		);
		assert.equal(
			workflow.getIn(["jobs", "Docker", "with", "scan-images"]),
			`\${{ needs.detect-changes.outputs.release-preflight != 'true' }}`,
		);
		for (const file of ["reusable-docker-build.yml", "reusable-docker-build.yml"]) {
			const reusable = parseDocument(await readFile(`.github/workflows/${file}`, "utf8"));
			assert.equal(
				reusable.getIn(["on", "workflow_call", "inputs", "scan-images", "default"]),
				true,
			);
			const jobs = reusable.get("jobs");
			assert.ok(isMap(jobs));
			for (const { value } of jobs.items) {
				if (isMap(value) && value.get("uses") === "./.github/workflows/reusable-docker-build.yml") {
					assert.equal(value.getIn(["with", "scan-images"]), `\${{ inputs.scan-images }}`);
				}
			}
		}
		const docker = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		assert.equal(
			namedStep(docker, ["jobs", "build"], "Enforce the release vulnerability policy").get("if"),
			`\${{ !cancelled() && steps.prep.outcome == 'success' && inputs.publish && inputs.scan-images }}`,
		);
	});

	void test("the security scan includes the locked plugin classpath and both Jackson API families", async () => {
		const lock = await readFile("server/buildscript-gradle.lockfile", "utf8");
		for (const family of ["com.fasterxml.jackson", "tools.jackson"]) {
			const bom = new RegExp(
				`${family.replaceAll(".", String.raw`\.`)}:jackson-bom:([^=\\n]+)=classpath`,
				"u",
			).exec(lock);
			const version = bom?.[1];
			assert.ok(isSet(version), `${family} BOM must be locked`);
			for (const module of ["jackson-core", "jackson-databind"]) {
				assert.ok(lock.includes(`${family}.core:${module}:${version}=classpath`));
			}
		}
		for (const plugin of [
			"org.springframework.boot:spring-boot-buildpack-platform:",
			"org.openapitools:openapi-generator:",
			"io.github.kobylynskyi:graphql-java-codegen:",
		]) {
			assert.ok(lock.includes(plugin), `${plugin} must be in the scanned lockfile`);
		}
		const workflow = parseDocument(
			await readFile(".github/workflows/ci-security-scan.yml", "utf8"),
		);
		for (const name of ["Trivy dependency scan", "Enforce dependency vulnerability policy"]) {
			const scan = stepInputs(namedStep(workflow, ["jobs", "security-scan"], name));
			assert.equal(scan.get("scan-type"), "fs");
			assert.equal(scan.get("scan-ref"), ".");
		}
	});

	void test("Chromatic retains structured evidence without credential-bearing debug files", async () => {
		const workflow = parseDocument(
			await readFile(".github/workflows/ci-quality-gates.yml", "utf8"),
		);
		const jobPath = ["jobs", "webapp-stories"];
		const chromatic = step(workflow, jobPath, "chromaui/action");
		assert.equal(chromatic.has("logFile"), false);
		assert.equal(chromatic.has("diagnosticsFile"), false);
		assert.equal(chromatic.get("logLevel"), "warn");
		assert.equal(chromatic.get("junitReport"), "chromatic-report.xml");
		const upload = namedStep(
			workflow,
			jobPath,
			"Retain full Storybook and visual test diagnostics",
		);
		const retained = stepInputs(upload);
		assert.equal(upload.get("if"), "always()");
		assert.equal(retained.get("retention-days"), 14);
		assert.deepEqual(asString(retained.get("path"), "report paths").trim().split("\n"), [
			"webapp/test-results",
			"webapp/chromatic-report.xml",
			"docs/images/readme",
		]);
	});

	void test("enforces the release vulnerability policy where images are built", async () => {
		const reusable = await readFile(".github/workflows/reusable-docker-build.yml", "utf8");
		const workflow = parseDocument(reusable);
		// Each platform is scanned in the job that built it, so an image adds no scan job and no
		// runner hop. The merge needs the build, so only scanned digests reach an index.
		assert.equal(workflow.hasIn(["jobs", "scan"]), false);
		// Other events collect both platform scans; merge groups use the isolated cancel helper.
		const images = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		assert.equal(images.hasIn(["jobs", "build", "strategy"]), false);
		assert.equal(images.hasIn(["jobs", "build-arm64", "strategy"]), false);
		const dependencies = images.getIn(["jobs", "merge", "needs"]);
		assert.ok(isSeq(dependencies));
		assert.deepEqual(dependencies.toJSON(), ["build", "build-arm64"]);
		const build = ["jobs", "build"];
		const steps = workflow.getIn([...build, "steps"]);
		assert.ok(isSeq(steps));
		const names = asArray(steps.toJSON(), "steps").map((item) => asRecord(item, "step").name);
		const at = (name: string) => names.indexOf(name);
		// Scanned after the digests are exported and before they are handed to the merge.
		assert.ok(at("Export digests") < at("Enforce the release vulnerability policy"));
		assert.ok(at("Enforce the release vulnerability policy") < at("Upload digests"));
		const enforce = namedStep(workflow, build, "Enforce the release vulnerability policy");
		assert.match(String(enforce.get("run")), /node scripts\/resolve-image-scan-subject\.ts/u);
		// The same script and the same policy file the release and the rescan call. A second
		// copy of the policy is the failure this gate exists to prevent.
		assert.match(
			String(enforce.get("run")),
			/node scripts\/check-release-vulnerabilities\.ts "\$image" "\$SCAN_PLATFORM"[\s\S]*security\/vulnerability-policy\.json/u,
		);
		assert.equal(enforce.getIn(["env", "SCAN_PLATFORM"]), `\${{ env.IMAGE_PLATFORM }}`);
		assert.doesNotMatch(String(enforce.get("run")), /github\.(?:run_id|run_attempt)/u);
		// The scans run on freed disk, with their temporary files on the roomiest volume.
		const disk = namedStep(workflow, build, "Free disk space for the scans");
		assert.equal(
			at("Free disk space for the scans"),
			at("Enforce the release vulnerability policy") - 1,
		);
		assert.equal(disk.get("run"), "node scripts/prepare-image-scan-runner.ts");
		assert.equal(disk.get("if"), enforce.get("if"));
		assert.equal(enforce.getIn(["env", "TMPDIR"]), `\${{ steps.scan-disk.outputs.directory }}`);
		// A gate that cannot say what it rejected is not finished.
		const report = namedStep(workflow, build, "Upload the vulnerability policy results");
		assert.equal(
			report.getIn(["with", "name"]),
			`vulnerability-policy-\${{ steps.prep.outputs.platform_pair }}-\${{ github.run_attempt }}`,
		);
		assert.equal(
			report.get("if"),
			`\${{ !cancelled() && (steps.scan.outcome == 'success' || steps.scan.outcome == 'failure') }}`,
		);
		// Every image is scanned before the step fails, so one refusal does not hide another's.
		assert.match(String(enforce.get("run")), /refused\+=\("\$name"\)/u);
		assert.match(job(reusable, "build"), /uses: \.\/\.github\/actions\/download-trivy-db/u);
		// The scanners pull through the registry login, not with the token in their environment.
		assert.equal(enforce.hasIn(["env", "TRIVY_PASSWORD"]), false);
		assert.equal(enforce.hasIn(["env", "TRIVY_USERNAME"]), false);
		// The scan sits in the build because the manifest job holds the signing token and runs no
		// repository code: no checkout, no local action, no repository script.
		const merge = job(reusable, "merge");
		assert.doesNotMatch(merge, /uses: actions\/checkout@/u);
		assert.doesNotMatch(merge, /uses: \.\//u);
		assert.doesNotMatch(merge, /node (?:\.\/)?scripts\//u);
		// The tools are installed for the runner's own CPU, since arm64 scans on an arm64 runner.
		const tools = await readFile(".github/actions/setup-release-security-tools/action.yml", "utf8");
		assert.match(tools, /node scripts\/setup-release-security-tools\.ts/u);
		const amd64 = await ciToolPin("trivy", "linux-x64");
		const arm64 = await ciToolPin("trivy", "linux-arm64");
		assert.match(amd64.name, /Linux-64bit/u);
		assert.match(arm64.name, /Linux-ARM64/u);

		let callSites = 0;
		for (const [file, source] of await workflowSources()) {
			for (const call of source.match(
				/node scripts\/check-release-vulnerabilities\.ts[\s\S]*?\.policy\.json"/gu,
			) ?? []) {
				callSites += 1;
				assert.match(
					call,
					/(?<![\w./-])security\/vulnerability-policy\.json/u,
					`${file} must evaluate the one release vulnerability policy`,
				);
			}
		}
		// The build gate invokes the evaluator directly. Release verification and both rescan owners
		// reach the same evaluator through TypeScript rather than another workflow call site.
		assert.equal(callSites, 1);

		// The evaluator runs Syft itself for a subject an exception binds by component inventory
		// (check-release-vulnerabilities.ts), so every job that reaches it installs the pinned Syft. Only
		// the advisory snapshot job does not.
		const cicd = await readFile(".github/workflows/cicd.yml", "utf8");
		const rescan = await readFile(".github/workflows/rescan-images.yml", "utf8");
		const evaluatorJobs: [string, string][] = [
			[".github/workflows/reusable-docker-build.yml", job(reusable, "build")],
			[".github/workflows/cicd.yml", job(cicd, "upstream-images")],
			[".github/workflows/rescan-images.yml main", job(rescan, "main")],
			[".github/workflows/rescan-images.yml release", job(rescan, "release")],
		];
		for (const [file, section] of evaluatorJobs) {
			assert.match(
				section,
				/setup-release-security-tools\n(?:\s+if: .*\n)?\s+with:\n\s+install-syft: "true"/u,
				file,
			);
		}
		assert.match(
			job(cicd, "vulnerability-database"),
			/install-syft: "false"/u,
			"the advisory snapshot job needs no Syft",
		);
		// Syft 1.51.1 reads GHCR through its native registry authentication.
		assert.match(
			job(rescan, "release"),
			/SYFT_REGISTRY_AUTH_AUTHORITY: ghcr\.io[\s\S]*SYFT_REGISTRY_AUTH_USERNAME:[\s\S]*SYFT_REGISTRY_AUTH_PASSWORD:/u,
		);
	});

	void test("keeps one release vulnerability policy behind every scan", async () => {
		// Every path that evaluates the policy — the build gate, the release, the release rescan and
		// the main rescan — must name this one file. A second copy is the failure the whole effort
		// removes, and it would be invisible: two policies both pass until they disagree.
		const sources = await readSources([
			...(await posixGlob(".github/workflows/*.{yml,yaml}")),
			...(await posixGlob("scripts/*.ts")),
		]);
		for (const [file, source] of sources) {
			if (file.endsWith(".test.ts")) {
				continue;
			}
			for (const reference of source.match(/[\w./-]*vulnerability-polic[\w-]*\.json/gu) ?? []) {
				assert.ok(
					// The evidence bundle carries a copy so a release can be re-audited against the
					// policy it was cut under; the generator is asserted below to copy, not author, it.
					["security/vulnerability-policy.json", "evidence/vulnerability-policy.json"].includes(
						reference,
					) || reference === "vulnerability-policy.json",
					`${file} must evaluate the one release vulnerability policy, not ${reference}`,
				);
			}
		}
		assert.match(
			await readFile("scripts/generate-release-evidence.ts", "utf8"),
			/copyFile\(\n?\s*"security\/vulnerability-policy\.json",/u,
		);
		// One committed policy, so "the same policy" is a fact rather than a convention.
		assert.deepEqual(await posixGlob("security/*vulnerability*.json"), [
			"security/vulnerability-policy.json",
		]);
	});

	void test("holds the committed exceptions to the policy every scan applies", async () => {
		// The gate reads this file only while scanning an image, so a mistyped platform, a malformed
		// digest or a lapsed expiry would surface as a red image scan instead of a failed check.
		// An empty report exercises the validation half of the evaluator on its own.
		const policy: unknown = JSON.parse(
			await readFile("security/vulnerability-policy.json", "utf8"),
		);
		assert.deepEqual(evaluateVulnerabilityPolicy("webapp", { Results: [] }, policy).errors, []);
	});

	void test("scans every release subject before the release, not only at the release gate", async () => {
		const inventory: unknown = JSON.parse(await readFile("security/release-images.json", "utf8"));
		const namespace = "ghcr.io/hephaestus-build";
		const digest = `sha256:${"c".repeat(64)}`;
		// The subject set the pre-release scans cover, derived from the inventory rather than listed:
		// the build gate and the scheduled rescan take the first-party half, scan-upstream-images.ts the
		// pinned upstream half.
		const scanned = [
			...planSubjects(inventory, namespace, "main").map((subject) => ({
				...subject,
				indexDigest: digest,
				provenance: "first-party" as const,
			})),
			...planUpstreamSubjects(inventory).map((subject) => ({
				...subject,
				provenance: "upstream" as const,
			})),
		];
		// Parity with the release gate, asserted by the release gate itself: this is the manifest that
		// would evidence exactly the pre-release subject set, and validateManifest rejects a manifest
		// whose subjects are not exactly the inventory. So an image the pre-release scans miss, or one
		// they cover that the release does not, fails here.
		const manifest = {
			schemaVersion: 1,
			subjects: scanned.flatMap((subject) =>
				(["linux/amd64", "linux/arm64"] as const).map((platform) => ({
					digest,
					image: subject.image,
					indexDigest: subject.indexDigest,
					platform,
					provenance: subject.provenance,
					repository: subject.repository,
				})),
			),
		};
		assert.doesNotThrow(() => validateManifest(manifest, inventory, namespace));

		// A planner nothing invokes covers nothing. The pinned digests need no build, so they are
		// scanned on the pull request that changes them — which is the pull request a Renovate digest
		// bump opens — and again in the scheduled rescan, where a finding routes to the tracking issue.
		assert.match(
			job(await readFile(".github/workflows/cicd.yml", "utf8"), "upstream-images"),
			/run: node scripts\/scan-upstream-images\.ts reports\n/u,
		);
		// The one-host installer ships the platform images, so the same job judges them.
		assert.match(
			job(await readFile(".github/workflows/cicd.yml", "utf8"), "upstream-images"),
			/run: node scripts\/scan-platform-images\.ts --policy\n/u,
		);
		assert.match(
			job(await readFile(".github/workflows/rescan-images.yml", "utf8"), "main"),
			/run: node scripts\/scan-upstream-images\.ts reports --report-only\n/u,
		);
		const detection = job(await readFile(".github/workflows/cicd.yml", "utf8"), "detect-changes");
		const filter = pathFilter(detection, "release-images");
		assert.match(
			filter,
			/- 'security\/release-images\.json'[\s\S]*- 'security\/vulnerability-policy\.json'/u,
		);
		// The Syft configuration is data the gate reads, not an import the closure below finds.
		assert.ok(
			filter.includes("- 'security/syft.yaml'"),
			"release-images must trigger on the Syft config",
		);
		// The trigger is derived, not trusted: a filter that lists the entry point but not the module
		// it parses JSON with skips the gate on the pull request that breaks the parser. Re-walk the
		// imports and require every file the gate actually loads to appear.
		for (const entry of ["scripts/scan-upstream-images.ts", "scripts/scan-platform-images.ts"]) {
			for (const file of await importClosure(entry)) {
				assert.ok(
					filter.includes(`- '${file}'`),
					`release-images must trigger on ${file}, which ${entry} loads`,
				);
			}
		}
	});

	void test("scans both released platforms before the release, not just linux/amd64", async () => {
		// The policy match key is `image | platform | vulnerability | package | installedVersion`, so
		// a single-platform pre-release scan leaves an arm64-only finding — or an arm64 exception
		// nobody wrote — to be discovered by the release gate, which is the failure this PR removes.
		assert.deepEqual([...PLATFORMS], ["linux/amd64", "linux/arm64"]);
		const source = await readFile("scripts/scan-upstream-images.ts", "utf8");
		assert.match(source, /for \(const platform of PLATFORMS\)/u);
	});

	void test("performs every release evidence check that does not need a release before the release", async () => {
		// The test above pins *subject* parity: the pre-release scans cover the images the release covers.
		// This is *check* parity, which subject parity does not imply — the vulnerability policy was
		// only ever one of the things the release gate evaluates. The bundle it judges is produced by
		// one generator and judged by one verifier, and both run before a release exists, so the only
		// checks a release can be the first to perform are the ones this asserts are release-only.
		const release = await readFile(".github/workflows/release.yml", "utf8");
		const cicd = await readFile(".github/workflows/cicd.yml", "utf8");
		const preflight = job(cicd, "Release-preflight");
		assert.match(preflight, /id: release-evidence/u);
		assert.match(
			preflight,
			/IMAGE: ghcr\.io\/hephaestus-build\/agent-pi@\$\{\{ steps\.release-evidence\.outputs\.agent-pi-digest \}\}/u,
		);
		for (const source of [job(release, "tag-images"), preflight]) {
			assert.match(source, /node scripts\/resolve-release-images\.ts /u);
			assert.match(
				source,
				/node scripts\/sandbox-security\.ts --image "\$IMAGE" --application-image "\$APPLICATION_IMAGE"/u,
			);
			assert.match(source, /docker pull "\$APPLICATION_IMAGE"/u);
			assert.match(
				source,
				/APPLICATION_IMAGE: ghcr\.io\/hephaestus-build\/application-server@\$\{\{ steps\.(?:retag|release-evidence)\.outputs\.application-server-digest \}\}/u,
			);
			assert.match(source, /node scripts\/generate-release-evidence\.ts evidence \\/u);
			assert.match(
				source,
				/node scripts\/verify-release-evidence\.ts evidence --write-validation/u,
			);
		}
		// The preflight verifies twice, the second time in trusted capture mode, so the validation
		// documents are re-derived and compared exactly as the release re-derives them.
		assert.match(
			preflight,
			/node scripts\/verify-release-evidence\.ts evidence --verify-capture\n/u,
		);
		assert.match(preflight, /max-age-hours: "48"/u);
		assert.match(preflight, /if: .*needs\.detect-changes\.outputs\.release-preflight == 'true'/u);
		assert.match(cicd, /^ {6}release-preflight:$/mu);
		assertNeeds(cicd, "all-ci-passed", "Release-preflight");

		// What "everything except signatures" rests on: the verifier's checks are unconditional, and
		// the only thing any mode decides is whether signature checks run and whether a
		// validation document is written or compared. A new check gated on anything else is a check a
		// release could be the first to perform, and lands here rather than in a release.
		const capture = job(release, "tag-images");
		const checksumCreation = capture.indexOf("sha256sum -- * > SHA256SUMS");
		const checksumSigning = capture.indexOf(
			"node scripts/keyless-sign.ts sign-blob --yes --bundle evidence/SHA256SUMS.sigstore.json evidence/SHA256SUMS",
		);
		assert.ok(checksumCreation !== -1 && checksumSigning > checksumCreation);
		assert.ok(
			capture.indexOf("node scripts/verify-release-evidence.ts evidence --verify-signatures") >
				checksumSigning,
		);
		// The SBOM attestations, the evidence checksums, the two locks and the chart digest.
		assert.equal((capture.match(/node scripts\/keyless-sign\.ts /gu) ?? []).length, 4);
		assert.match(capture, /node scripts\/keyless-sign\.ts sign --yes "\$chart"/u);
		assert.doesNotMatch(capture, /(?:^|\n)\s*cosign (?:attest|sign|sign-blob) /u);
		const verifier = await readFile("scripts/verify-release-evidence.ts", "utf8");
		// Template literals are elided so this test can quote the source lines it expects without
		// carrying interpolations of its own.
		const conditioned = verifier
			.split("\n")
			.map((line) => line.trim().replaceAll(/`[^`]*`/gu, "<path>"))
			.filter((line) => line.includes("mode ==="));
		assert.deepEqual(conditioned, [
			'mode === "write-validation" || mode === "verify-capture"',
			'const historical = mode === "verify" || mode === "verify-signatures";',
			'persistOrVerify(<path>, sbom, mode === "write-validation");',
			'persistOrVerify(<path>, policyResult, mode === "write-validation");',
			'if (mode === "verify-signatures" && subject.provenance === "first-party") {',
			'if (mode === "verify-signatures") {',
		]);
	});

	void test("leaves the Version PR's CI to its own pull_request event and a deliberate dispatch", async () => {
		const versioning = parseDocument(await readFile(".github/workflows/version-pr.yml", "utf8"));
		const maintainer = ["jobs", "version-pr"];
		// `version-pr-workflow.test.ts` owns the trigger; `maintain-version-pr.test.ts` the selection.
		const steps = versioning.getIn([...maintainer, "steps"]);
		assert.ok(isSeq(steps));
		// The pull request's own run validates it; maintenance cannot start a second, competing one.
		const permissions = versioning.getIn([...maintainer, "permissions"]);
		assert.ok(isMap(permissions));
		assert.deepEqual(permissions.toJSON(), {
			actions: "read",
			contents: "write",
			"pull-requests": "write",
		});
		for (const maintenanceStep of steps.items) {
			assert.ok(isMap(maintenanceStep));
			const run = maintenanceStep.get("run");
			if (run !== undefined) {
				assert.ok(typeof run === "string");
				assert.doesNotMatch(run, /workflow run|dispatch/u);
			}
		}
		// The base's dependencies install in the job that holds the write token, so they install
		// exactly as every other CI job installs them: no lifecycle script, every entry verified.
		const toolchain = parseDocument(
			await readFile(".github/actions/setup-toolchain/action.yml", "utf8"),
		);
		const toolchainSteps = toolchain.getIn(["runs", "steps"]);
		assert.ok(isSeq(toolchainSteps));
		const install = toolchainSteps.items.find(
			(item) => isMap(item) && item.get("id") === "pnpm-install",
		);
		assert.ok(isMap(install));
		assert.equal(install.get("run"), `pnpm ${INSTALL_ARGUMENTS.join(" ")}`);
		const installEnvironment = install.get("env");
		assert.ok(isMap(installEnvironment));
		assert.deepEqual(installEnvironment.toJSON(), INSTALL_ENVIRONMENT);
		const maintainerSource = await readFile("scripts/maintain-version-pr.ts", "utf8");
		assert.doesNotMatch(maintainerSource, /"workflow",\s*"run"|\/dispatches/u);

		// A failed run reopens nothing by itself, so it reports on one tracking issue, which the
		// next successful run closes. `version-pr-workflow.test.ts` runs the script for each result.
		const report = ["jobs", "report"];
		assert.equal(versioning.getIn([...report, "needs"]), "version-pr");
		assert.equal(
			versioning.getIn([...report, "if"]),
			`\${{ always() && contains(fromJSON('["success", "failure", "cancelled"]'), needs.version-pr.result) }}`,
		);
		const reportPermissions = versioning.getIn([...report, "permissions"]);
		assert.ok(isMap(reportPermissions));
		assert.deepEqual(reportPermissions.toJSON(), { actions: "read", issues: "write" });
		const tracking = asString(
			namedStep(versioning, report, "Open or close the tracking issue").get("run"),
			"tracking script",
		);
		for (const command of ["gh issue create", "gh issue comment", "gh issue close"]) {
			assert.ok(tracking.includes(command), command);
		}

		// What remains: the pull_request event, and the manual dispatch an operator runs to recover.
		const cicd = await readFile(".github/workflows/cicd.yml", "utf8");
		const ci = parseDocument(cicd);
		assert.ok(ci.hasIn(["on", "pull_request"]));
		assert.equal(
			ci.getIn(["on", "workflow_dispatch", "inputs", "release-preflight", "type"]),
			"boolean",
		);
		// A dispatched run carries no pull_request payload, so the gate's status has to fall back to
		// the dispatched ref's head — which is exactly the Version PR's head commit.
		assert.match(
			job(cicd, "all-ci-passed"),
			/const sha = context\.payload\.pull_request\?\.head\?\.sha \|\| context\.sha;/u,
		);
	});

	void test("rescans main's images daily and reports drift to an issue, not a status", async () => {
		const workflow = await readFile(".github/workflows/rescan-images.yml", "utf8");
		const source = job(workflow, "main");
		// Repeated findings do not create duplicate notifications; the report fingerprints them.
		assert.match(workflow, /^ {4}- cron: "\d+ \d+ \* \* \*"$/mu);
		assert.match(workflow, /workflow_dispatch:/u);
		assert.match(workflow, /group: rescan-images/u);
		// Writing an issue is the whole point; nothing else here needs a write.
		assert.match(source, /issues: write/u);
		assert.doesNotMatch(source, /contents: write|packages: write|id-token: write/u);
		// The scan and the reporting are tested TypeScript, not inline bash: the upsert in
		// particular has to not open a duplicate each day, which no eyeball review establishes.
		assert.match(source, /run: node scripts\/scan-main-images\.ts reports/u);
		assert.match(source, /run: node scripts\/report-vulnerability-drift\.ts reports/u);
		assert.match(source, /uses: \.\/\.github\/actions\/download-trivy-db/u);
		assert.match(source, /uses: actions\/upload-artifact@/u);
		// Nothing may turn a finding into a red status: no `continue-on-error` fig leaf, and no
		// second evaluator inline.
		assert.doesNotMatch(source, /continue-on-error/u);
		assert.doesNotMatch(source, /trivy image/u);
	});

	void test("fetches the Trivy database through one action", async () => {
		const action = await readFile(".github/actions/download-trivy-db/action.yml", "utf8");
		// GHCR answers TOOMANYREQUESTS often enough that a gate needs the mirror; stating it once
		// is why this action exists at all.
		assert.match(action, /public\.ecr\.aws\/aquasecurity\/trivy-db/u);
		assert.match(action, /--download-db-only/u);
		assert.match(action, /--download-java-db-only/u);
		for (const file of [".github/workflows/cicd.yml", ".github/workflows/release.yml"]) {
			const workflow = parseDocument(await readFile(file, "utf8"));
			const evidenceJob = file.endsWith("cicd.yml") ? "Release-preflight" : "tag-images";
			const steps = workflow.getIn(["jobs", evidenceJob, "steps"]);
			assert.ok(isSeq(steps));
			const prepare = steps.items.find(
				(entry) => isMap(entry) && entry.get("uses") === "./.github/actions/download-trivy-db",
			);
			assert.ok(isMap(prepare));
			assert.equal(prepare.getIn(["with", "java-db"]), "true");
		}

		for (const [file, source] of await workflowSources()) {
			assert.doesNotMatch(
				source,
				/--download-db-only/u,
				`${file} must download the Trivy database through .github/actions/download-trivy-db`,
			);
			// trivy-action downloads from one mirror with no retry unless told not to; a 404 there
			// failed a merge group's required Security job.
			const jobs = parseDocument(source).get("jobs");
			assert.ok(isMap(jobs));
			for (const entry of jobs.items) {
				const steps = isMap(entry.value) ? entry.value.get("steps") : undefined;
				if (!isSeq(steps)) {
					continue;
				}
				const uses = steps.items.map((item) => (isMap(item) ? String(item.get("uses")) : ""));
				const download = uses.indexOf("./.github/actions/download-trivy-db");
				for (const [index, used] of uses.entries()) {
					if (!used.startsWith("aquasecurity/trivy-action@")) {
						continue;
					}
					const trivy: unknown = steps.items[index];
					assert.ok(isMap(trivy));
					const where = `${file} ${String(entry.key)} step ${index}`;
					assert.ok(download !== -1 && download < index, `${where} must follow download-trivy-db`);
					assert.equal(trivy.getIn(["env", "TRIVY_SKIP_DB_UPDATE"]), "true", where);
					assert.equal(trivy.getIn(["with", "cache"]), "false", where);
					assert.equal(trivy.getIn(["with", "skip-setup-trivy"]), true, where);
				}
			}
		}
		// Everything that signs or publishes a scan result asserts the database is not stale.
		const rescan = await readFile(".github/workflows/rescan-images.yml", "utf8");
		const scanners: [string, string][] = [
			[".github/workflows/release.yml", await readFile(".github/workflows/release.yml", "utf8")],
			[".github/workflows/rescan-images.yml release", job(rescan, "release")],
			[".github/workflows/rescan-images.yml main", job(rescan, "main")],
		];
		for (const [file, source] of scanners) {
			assert.match(
				source,
				/uses: \.\/\.github\/actions\/download-trivy-db\n\s+with:\n\s+max-age-hours: "48"/u,
				`${file} must refuse a stale Trivy database`,
			);
		}
	});

	void test("informational dependency reporting cannot delay required CI or release", async () => {
		const security = parseDocument(
			await readFile(".github/workflows/ci-security-scan.yml", "utf8"),
		);
		const securityJobs = security.get("jobs");
		assert.ok(isMap(securityJobs));
		assert.deepEqual(
			securityJobs.items.map((entry) => String(entry.key)),
			["security-scan"],
			"The required Security workflow must not wait for the informational dependency report",
		);
		assert.equal(security.getIn(["jobs", "security-scan", "if"]), "inputs.should_skip != 'true'");
		const orchestratorSource = await readFile(".github/workflows/cicd.yml", "utf8");
		const orchestrator = parseDocument(orchestratorSource);
		assert.equal(
			orchestrator.getIn(["jobs", "Security", "uses"]),
			"./.github/workflows/ci-security-scan.yml",
		);
		assertNeeds(orchestratorSource, "all-ci-passed", "Security", "Release-preflight");
		assert.doesNotMatch(
			orchestratorSource,
			/outputs\.dependency-review|dependency_review_changed|^ {12}dependency-review:/mu,
		);
		assert.equal(
			security.hasIn(["on", "workflow_call", "inputs", "dependency_review_changed"]),
			false,
		);
		const report = parseDocument(await readFile(".github/workflows/dependency-review.yml", "utf8"));
		const triggers = report.get("on");
		assert.ok(isMap(triggers));
		assert.deepEqual(
			triggers.items.map((entry) => String(entry.key)),
			["pull_request"],
		);
		const workflowPermissions = report.get("permissions");
		assert.ok(isMap(workflowPermissions));
		assert.deepEqual(workflowPermissions.toJSON(), {});
		assert.equal(report.getIn(["jobs", "dependency-review", "permissions", "contents"]), "read");
		const permissions = report.getIn(["jobs", "dependency-review", "permissions"]);
		assert.ok(isMap(permissions));
		assert.deepEqual(permissions.toJSON(), { contents: "read" });
		for (const [file, source] of await workflowSources()) {
			assert.doesNotMatch(
				source,
				/uses: \.\/\.github\/workflows\/dependency-review\.yml/u,
				`${file} must not wait for the standalone report`,
			);
		}
		const release = parseDocument(await readFile(".github/workflows/release.yml", "utf8"));
		const upstream = release.getIn(["on", "workflow_run", "workflows"]);
		assert.ok(isSeq(upstream));
		assert.deepEqual(upstream.toJSON(), ["CI/CD"]);
	});

	void test("blocks dependency regressions across the release trust boundary", async () => {
		const orchestrator = await readFile(".github/workflows/cicd.yml", "utf8");
		const securityConfig = pathFilter(orchestrator, "security-config");
		assert.match(
			securityConfig,
			/- 'security\/trivy-dependency-ignore\.yaml'/u,
			"a pull request that suppresses a finding has to select the job that would have reported it",
		);
		const workflow = parseDocument(
			await readFile(".github/workflows/ci-security-scan.yml", "utf8"),
		);
		const reportWorkflow = parseDocument(
			await readFile(".github/workflows/dependency-review.yml", "utf8"),
		);
		const reviewPath = ["jobs", "dependency-review"];
		assert.equal(reportWorkflow.getIn([...reviewPath, "timeout-minutes"]), 15);
		const checkout = step(reportWorkflow, reviewPath, "actions/checkout");
		assert.equal(checkout.get("fetch-depth"), 1);
		assert.equal(checkout.get("persist-credentials"), false);
		assert.equal(
			namedStep(reportWorkflow, reviewPath, "Review dependency changes").get("uses"),
			"actions/dependency-review-action@a1d282b36b6f3519aa1f3fc636f609c47dddb294",
		);
		const review = step(reportWorkflow, reviewPath, "actions/dependency-review-action");
		assert.equal(review.get("config-file"), "./.github/dependency-review-config.yml");
		assert.equal(review.get("retry-on-snapshot-warnings"), true);
		assert.equal(review.get("retry-on-snapshot-warnings-timeout"), 600);

		const config = parseDocument(await readFile(".github/dependency-review-config.yml", "utf8"));
		assert.equal(config.get("warn-only"), true);
		assert.ok(
			!config.has("fail-on-severity"),
			"warn-only reports every severity, so a threshold here names a policy the action does not apply",
		);
		const scopes = config.get("fail-on-scopes");
		assert.ok(isSeq(scopes));
		assert.deepEqual(
			new Set(scopes.items.map((scope) => (isScalar(scope) ? scope.value : undefined))),
			new Set(["runtime", "development", "unknown"]),
		);

		const scanPath = ["jobs", "security-scan"];
		assert.equal(workflow.getIn([...scanPath, "env", "TRIVY_INCLUDE_DEV_DEPS"]), "true");
		const secretScan = stepInputs(namedStep(workflow, scanPath, "Secret detection"));
		assert.equal(secretScan.get("image"), "ghcr.io/trufflesecurity/trufflehog");
		assert.match(String(secretScan.get("version")), /^\d+\.\d+\.\d+@sha256:[a-f0-9]{64}$/u);
		assert.equal(
			workflow.getIn([...scanPath, "env", "EVENT_BASE"]),
			`\${{ github.event.pull_request.base.sha || github.event.merge_group.base_sha || github.event.before || '' }}`,
		);
		assert.equal(secretScan.get("base"), `\${{ env.EVENT_BASE }}`);
		assert.equal(
			secretScan.get("head"),
			`\${{ github.event.pull_request.head.sha || github.event.merge_group.head_sha || github.sha }}`,
		);
		assert.ok(String(secretScan.get("extra_args")).split(" ").includes("--fail-on-scan-errors"));
		assert.ok(String(secretScan.get("extra_args")).split(" ").includes("--only-verified"));
		const report = stepInputs(namedStep(workflow, scanPath, "Trivy dependency scan"));
		assert.equal(report.get("format"), "sarif");
		assert.ok(
			!report.has("severity"),
			"SARIF output reports every severity unless limit-severities-for-sarif is true, so a filter here states a policy Trivy does not apply",
		);
		assert.ok(
			!report.has("exit-code"),
			"the SARIF pass is the code-scanning feed; the pass below is the verdict",
		);
		assert.ok(
			!report.has("scanners"),
			"trivy fs defaults to vuln,secret, and narrowing this pass drops tree-wide secret findings from code scanning",
		);

		const gateStep = namedStep(workflow, scanPath, "Enforce dependency vulnerability policy");
		const gate = stepInputs(gateStep);
		assert.equal(gate.get("format"), "table");
		assert.equal(gate.get("scanners"), "vuln");
		assert.equal(gate.get("severity"), "HIGH,CRITICAL");
		assert.equal(gate.get("ignore-unfixed"), true);
		assert.equal(gate.get("exit-code"), "1");
		assert.equal(gate.get("trivyignores"), "security/trivy-dependency-ignore.yaml");
		assert.equal(gate.get("skip-setup-trivy"), true);

		// The step is continue-on-error so the table reaches the log; the aggregator is what turns
		// its outcome into the job's verdict.
		assert.equal(gateStep.get("id"), "dependency-policy");
		const aggregator = namedStep(workflow, scanPath, "Evaluate security checks");
		const env = aggregator.get("env");
		assert.ok(isMap(env));
		assert.match(
			String(env.get("DEPENDENCY_POLICY")),
			/^\$\{\{ steps\.dependency-policy\.outcome \}\}$/u,
		);
		assert.match(String(aggregator.get("run")), /for result in [^\n]*"\$DEPENDENCY_POLICY"/u);

		const ignore: unknown = parseDocument(
			await readFile("security/trivy-dependency-ignore.yaml", "utf8"),
		).toJS();
		assert.ok(isRecord(ignore) && Array.isArray(ignore.vulnerabilities));
		const now = Date.now();
		const faults = ignore.vulnerabilities
			.map((entry: unknown) => exceptionFault(entry, now))
			.filter((fault) => fault !== undefined);
		assert.deepEqual(faults, [], "security/trivy-dependency-ignore.yaml entries");
	});

	void test("judges released migrations against the base of every pre-merge event", async (context) => {
		const workflow = parseDocument(
			await readFile(".github/workflows/ci-security-scan.yml", "utf8"),
		);
		const scanPath = ["jobs", "security-scan"];
		const baseline = namedStep(workflow, scanPath, "Resolve released-chain baseline");
		assert.equal(baseline.get("env"), undefined, "the step reads the job's one EVENT_BASE");
		assert.equal(
			namedStep(workflow, scanPath, "Changelog immutability guard").get("if"),
			"steps.baseline.outputs.base != ''",
		);
		const directory = await mkdtemp(path.join(tmpdir(), "released-chain-"));
		context.after(async () => rm(directory, { recursive: true, force: true }));
		const env = environmentForGitFixture();
		const git = (...args: string[]) => {
			const result = spawnSync("git", args, { cwd: directory, env, encoding: "utf8" });
			assert.equal(result.status, 0, result.stderr);
			return result.stdout.trim();
		};
		git("init", "-q");
		git(
			"-c",
			"user.name=Test",
			"-c",
			"user.email=test@example.com",
			"commit",
			"-q",
			"--allow-empty",
			"-m",
			"base",
		);
		const base = git("rev-parse", "HEAD");
		const output = path.join(directory, "output");
		const resolve = async (event: string, eventBase: string) => {
			await writeFile(output, "");
			const result = spawnSync(
				"bash",
				["--noprofile", "--norc", "-e", "-c", String(baseline.get("run"))],
				{
					cwd: directory,
					encoding: "utf8",
					env: { ...env, GITHUB_EVENT_NAME: event, EVENT_BASE: eventBase, GITHUB_OUTPUT: output },
				},
			);
			return { failed: result.status !== 0, output: await readFile(output, "utf8") };
		};
		const unknown = "0".repeat(40);
		// A merge group used to resolve no base and skip the guard; it is the last check before main.
		for (const event of ["pull_request", "merge_group"]) {
			assert.deepEqual(
				await resolve(event, base),
				{ failed: false, output: `base=${base}\n` },
				event,
			);
			const unknownBase = await resolve(event, unknown);
			assert.equal(unknownBase.failed, true, `${event} with an unknown base`);
			const noBase = await resolve(event, "");
			assert.equal(noBase.failed, true, `${event} without a base`);
		}
		assert.deepEqual(await resolve("push", base), { failed: false, output: `base=${base}\n` });
		for (const before of [unknown, ""]) {
			assert.deepEqual(await resolve("push", before), { failed: false, output: "base=\n" });
		}
	});

	void test("all signers use one immutable Cosign installer without caller-selected versions", async () => {
		const installers = new Set<string>();
		for (const [file, source] of await readSources(
			await posixGlob(".github/{actions,workflows}/**/*.{yml,yaml}"),
		)) {
			assert.doesNotMatch(source, /sigstore\/cosign-installer@/u, file);
			visit(parseDocument(source), {
				Map(_key, declaration) {
					const reference = String(declaration.get("uses"));
					if (!reference.startsWith("hephaestus-build/Hephaestus/.github/actions/setup-cosign@")) {
						return;
					}
					installers.add(reference);
					assert.match(reference, /@[a-f0-9]{40}$/u, file);
					assert.equal(declaration.has("with"), false, file);
				},
			});
		}
		assert.equal(installers.size, 1);
	});

	void test("runs release evidence verification through tested TypeScript", async () => {
		const release = await readFile(".github/workflows/release.yml", "utf8");
		const rescan = job(await readFile(".github/workflows/rescan-images.yml", "utf8"), "release");
		assert.match(release, /SOURCE_TAG: \$\{\{ needs\.release\.outputs\.sha \}\}/u);
		assert.match(release, /node scripts\/resolve-release-images\.ts "\$SOURCE_TAG"/u);
		assert.doesNotMatch(job(release, "tag-images"), /imagetools/u);
		assert.match(rescan, /node scripts\/rescan-release-images\.ts release-evidence reports/u);
		assert.doesNotMatch(rescan, /while IFS|jq -er|verify-release-evidence\.ts/u);
		assert.doesNotMatch(rescan, /node scripts\/check-release-sbom\.ts/u);
		assert.equal((release.match(/node scripts\/verify-release-evidence\.ts/gu) ?? []).length, 3);
		assert.doesNotMatch(release, /node scripts\/check-release-(?:sbom|vulnerabilities)\.ts/u);
	});

	void test("creates the draft release only once the evidence gate has passed", async () => {
		const source = await readFile(".github/workflows/release.yml", "utf8");
		const decide = job(source, "release");
		const gate = job(source, "tag-images");
		// Release images are promoted by digest and never rebuilt, so a draft cut before the gate
		// can never pass at that commit.
		assert.doesNotMatch(decide, /gh release create/u);
		const created = gate.indexOf('gh release create "$TAG_NAME"');
		const gated = gate.lastIndexOf("node scripts/verify-release-evidence.ts");
		const uploaded = gate.indexOf('gh release upload "$TAG_NAME"');
		assert.ok(gated !== -1, "tag-images must run the evidence verifier");
		assert.ok(created > gated, "the draft must be created after the evidence gate");
		assert.ok(uploaded > created, "release assets need a draft to upload to");
		// The notes start at the release the upgrade test seeds from, in the released tree, and the
		// signing job installs no dependency to render them.
		const notes = job(source, "release-notes");
		assert.match(notes, /ref: \$\{\{ needs\.release\.outputs\.sha \}\}/u);
		assert.match(notes, /PREVIOUS_VERSION: \$\{\{ needs\.release\.outputs\.previous_version \}\}/u);
		assert.match(notes, /--notes "\$PREVIOUS_VERSION" "\$NOTES_VERSION" CHANGELOG\.md/u);
		assert.doesNotMatch(notes, /contents: write|id-token|packages: write/u);
		assert.doesNotMatch(gate, /RELEASE_NOTES:/u);
		assert.match(notes, /node scripts\/github-release-notes\.ts notes\.md release-body\.md/u);
		assert.match(notes, /name: release-notes\n/u);
		assert.match(gate, /--notes-file release-notes\/release-body\.md/u);
		const completeNotes = gate.indexOf("cp release-notes/notes.md evidence/release-notes.md");
		assert.ok(completeNotes !== -1 && completeNotes < gate.indexOf("sha256sum -- * > SHA256SUMS"));
		assert.doesNotMatch(gate, /awk|CHANGELOG\.md|install: "frozen"/u);
		const publication = job(source, "publish-release");
		const published = publication.indexOf('gh release edit "$TAG_NAME"');
		const immutable = publication.indexOf("--json isImmutable");
		const promoted = publication.indexOf("docker buildx imagetools create");
		assert.ok(published !== -1 && immutable > published, "publication must verify immutable state");
		assert.ok(promoted > immutable, "image aliases must only move after immutable publication");

		// A re-run resumes the draft, so uploads must replace assets instead of failing on names.
		for (const upload of source.matchAll(/gh release upload[\s\S]*?\n\n/gu)) {
			assert.match(upload[0], /--clobber/u);
		}
	});

	void test("decides what to release in tested TypeScript, on outputs the workflow reads", async () => {
		const source = await readFile(".github/workflows/release.yml", "utf8");
		const decide = job(source, "release");
		// The planner owns the release decision. The workflow consumes only its tested outputs.
		assert.match(decide, /node scripts\/plan-release\.ts "\$SHA"/u);
		assert.doesNotMatch(decide, /PARENT_VERSION|gh release view/u);
		// Every output the workflow reads is one the planner writes, and nothing it writes is dead.
		const plan = planRelease(
			"cafe",
			"0.75.0",
			[{ isDraft: false, isPrerelease: false, tag: "v0.74.0", targetCommitish: "main" }],
			false,
		);
		const read = [...source.matchAll(/steps\.cut\.outputs\.(?<name>[\w-]+)/gu)].map(
			({ groups }) => {
				const name = groups?.name;
				assert.ok(name !== undefined);
				return name;
			},
		);
		assert.deepEqual(
			[...new Set(read)].toSorted(),
			Object.keys(releaseOutputs(plan, true)).toSorted(),
		);
	});

	void test("exempts a verified revert from the changeset freeze rules", async () => {
		const source = await readFile(".github/workflows/verify-changesets.yml", "utf8");
		const detect = source.indexOf("- name: Detect a verified revert");
		const guard = source.indexOf("- name: Check release-note presence");
		assert.ok(detect !== -1 && guard > detect, "the revert check must precede the freeze guard");
		assert.match(source, /run: node scripts\/verify-revert\.ts "\$BASE_SHA" HEAD/u);
		assert.match(source, /steps\.revert\.outputs\.verified-revert != 'true'/u);
		// The exemption is structural: a title or branch name is attacker-chosen and never read.
		assert.doesNotMatch(source, /pull_request\.title|github\.head_ref/u);
	});
});

void test("Semgrep scans PRs and main without a privileged trigger or mutable rules", async () => {
	const source = await readFile(".github/workflows/semgrep.yml", "utf8");
	const workflow = parseDocument(source);
	for (const trigger of ["pull_request", "push"]) {
		assert.ok(workflow.hasIn(["on", trigger]), `semgrep.yml must run on ${trigger}`);
	}
	// The push to main scans the merge group's own commit; a queue run would scan it twice.
	assert.equal(workflow.hasIn(["on", "merge_group"]), false);
	assert.equal(workflow.hasIn(["on", "pull_request_target"]), false);
	assert.match(
		String(workflow.getIn(["jobs", "scan", "env", "SEMGREP_IMAGE"])),
		/^semgrep\/semgrep:[\d.]+@sha256:[a-f0-9]{64}$/u,
	);
	for (const [command] of source.matchAll(/docker run.*/gu)) {
		assert.match(command, /--network none/u, "the scanner runs without a network");
	}
	assert.match(source, /--test --config security\/semgrep/u);
	assert.match(source, /--disable-nosem\b/u);
	assert.match(source, /--strict\b/u);
	assert.match(source, /--error\b/u);
	assert.match(source, /--sarif-output\b/u);
	assert.doesNotMatch(source, /continue-on-error|secrets\.|--config (?:auto|p\/)/u);
	const upload = namedStep(workflow, ["jobs", "scan"], "Upload code scanning results");
	assert.equal(stepInputs(upload).get("category"), "semgrep-tls");
	assert.match(
		String(upload.get("if")),
		/github\.event\.pull_request\.head\.repo\.full_name == github\.repository/u,
		"a fork pull request cannot upload SARIF and must skip the step",
	);
	assert.match(
		pathFilter(await readFile(".github/workflows/cicd.yml", "utf8"), "tooling"),
		/security\/semgrep\/\*\*/u,
	);
});

void test("Stories enforces visual evidence independently of preview publication", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/ci-quality-gates.yml", "utf8"));
	const jobPath = ["jobs", "webapp-stories"];
	assert.equal(
		workflow.getIn([...jobPath, "env", "CHROMATIC_POLICY_SKIP"]),
		`\${{ (github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name != github.repository) || startsWith(github.head_ref || github.ref_name, 'dependabot/') || startsWith(github.head_ref || github.ref_name, 'renovate/') }}`,
	);
	const chromatic = namedStep(workflow, jobPath, "Chromatic visual testing");
	assert.equal(chromatic.get("if"), "success() && env.CHROMATIC_POLICY_SKIP != 'true'");
	// The preview is the Storybook that Chromatic published, so every link and the status need its URL.
	for (const name of ["Prepare Storybook preview links", "Publish Storybook preview links"]) {
		assert.equal(
			namedStep(workflow, jobPath, name).get("if"),
			"github.event_name == 'pull_request' && startsWith(steps.chromatic.outputs.storybookUrl, 'http')",
		);
	}
	assert.equal(
		namedStep(workflow, jobPath, "Create Storybook status check").get("if"),
		"success() && startsWith(steps.chromatic.outputs.storybookUrl, 'http')",
	);
	assert.equal(chromatic.getIn(["with", "autoAcceptChanges"]), false);
	assert.equal(chromatic.getIn(["with", "exitZeroOnChanges"]), false);
	assert.equal(chromatic.getIn(["with", "exitOnceUploaded"]), false);
	assert.equal(chromatic.getIn(["with", "skip"]), false);
	const report = namedStep(workflow, jobPath, "Report Chromatic visual coverage");
	assert.equal(report.get("if"), "always()");
	assert.equal(report.get("run"), "node scripts/report-chromatic.ts");
	assert.equal(report.getIn(["env", "CHROMATIC_OUTCOME"]), `\${{ steps.chromatic.outcome }}`);
	for (const [name, output] of Object.entries({
		CODE: "code",
		CAPTURED: "actualCaptureCount",
		INHERITED: "inheritedCaptureCount",
		TESTS: "testCount",
		ERRORS: "errorCount",
		CHANGES: "changeCount",
		INTERACTIONS: "interactionTestFailuresCount",
		BUILD_URL: "buildUrl",
	})) {
		assert.equal(
			report.getIn(["env", `CHROMATIC_${name}`]),
			`\${{ steps.chromatic.outputs.${output} }}`,
		);
	}
	const gate = namedStep(workflow, jobPath, "Evaluate stories checks");
	assert.equal(gate.get("if"), "always()");
	// Visual coverage is reported, never gating.
	assert.equal(gate.getIn(["env", "CHROMATIC"]), undefined);
});

void test("supported-release failures use the shared tracking issue reporter", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/rescan-images.yml", "utf8"));
	const notification = namedStep(
		workflow,
		["jobs", "release"],
		"Notify vulnerability response tracking",
	);
	assert.equal(notification.get("if"), "failure()");
	assert.equal(
		notification.get("run"),
		"node scripts/report-vulnerability-drift.ts reports --release-failure",
	);
	assert.equal(notification.getIn(["env", "GH_TOKEN"]), `\${{ secrets.GITHUB_TOKEN }}`);
	assert.equal(workflow.getIn(["jobs", "release", "permissions", "issues"]), "write");
	assert.equal(workflow.getIn(["concurrency", "group"]), "rescan-images");
	assert.equal(workflow.getIn(["concurrency", "cancel-in-progress"]), false);
});

void test("the staging channel is signed by the release's keyless signer, over no old bundle", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/promote.yml", "utf8"));
	const steps = workflow.getIn(["jobs", "promote", "steps"]);
	assert.ok(isSeq(steps));
	const sign = steps.items.find((item) => isMap(item) && item.get("name") === "Sign the channel");
	assert.ok(isMap(sign));
	const run = String(sign.get("run"));
	// deploy-state holds the previous bundle, and keyless-sign.ts refuses to write over one.
	const removal = run.search(/rm -f "\$\{CHANNEL_FILE\}\.sigstore\.json"/u);
	const signing = run.indexOf("node ../scripts/keyless-sign.ts sign-blob --yes --bundle");
	assert.ok(removal !== -1 && signing > removal);
	assert.doesNotMatch(run, /^\s*cosign /mu);
});

void test("manual publication requires a nonempty candidate on main and cannot bypass successful trusted CI", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/release.yml", "utf8"));
	const condition = workflow.getIn(["jobs", "release", "if"]);
	const base = {
		repository: "owner/repo",
		ref: "refs/heads/main",
		event_name: "workflow_dispatch",
		event: {
			workflow_run: {
				conclusion: "success",
				event: "push",
				head_repository: { full_name: "owner/repo" },
			},
		},
	};
	const manual = { ...base, event: {} };
	assert.equal(
		expandExpressions(condition, {
			github: manual,
			inputs: { "promote-candidate": "v1.0.0-rc.2" },
		}),
		"true",
	);
	assert.equal(
		expandExpressions(condition, { github: manual, inputs: { "promote-candidate": "" } }),
		"false",
	);
	assert.equal(
		expandExpressions(condition, {
			github: { ...manual, ref: "refs/heads/topic" },
			inputs: { "promote-candidate": "v1.0.0-rc.2" },
		}),
		"false",
	);
	assert.equal(
		expandExpressions(condition, { github: { ...base, event_name: "workflow_run" }, inputs: {} }),
		"true",
	);
	for (const run of [
		{ conclusion: "failure", event: "push", head_repository: { full_name: "owner/repo" } },
		{ conclusion: "success", event: "pull_request", head_repository: { full_name: "owner/repo" } },
		{ conclusion: "success", event: "push", head_repository: { full_name: "fork/repo" } },
	]) {
		assert.equal(
			expandExpressions(condition, {
				github: { ...base, event_name: "workflow_run", event: { workflow_run: run } },
				inputs: {},
			}),
			"false",
		);
	}
});

void test("the flattened signer preserves its published digest output contract", async () => {
	const root = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	const producer = parseDocument(
		await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
	);
	assert.equal(
		root.getIn(["jobs", "Docker", "uses"]),
		"./.github/workflows/reusable-docker-build.yml",
	);
	for (const image of ["application-server", "webapp", "agent-pi", "postgres"]) {
		assert.equal(
			producer.getIn(["on", "workflow_call", "outputs", `${image}-digest`, "value"]),
			`\${{ jobs.merge.outputs.${image}-digest }}`,
		);
		assert.equal(
			producer.getIn(["jobs", "merge", "outputs", `${image}-digest`]),
			`\${{ steps.digest.outputs.${image} }}`,
		);
	}
});

void test("stable publication requires ratchets for the planned version on the candidate source", async () => {
	const release = parseDocument(await readFile(".github/workflows/release.yml", "utf8"));
	const ratchets = release.getIn(["jobs", "stable-ratchets"]);
	assert.ok(isMap(ratchets));
	const permissions = ratchets.get("permissions");
	assert.ok(isMap(permissions));
	assert.deepEqual(permissions.toJSON(), { contents: "read" });
	assert.equal(ratchets.get("needs"), "release");
	const selection = asString(ratchets.get("if"), "stable ratchet condition");
	for (const released of ["true", "false", ""]) {
		for (const prerelease of ["true", "false", ""]) {
			assert.equal(
				expandExpressions(`\${{ ${selection} }}`, {
					needs: { release: { outputs: { released, prerelease } } },
				}),
				String(released === "true"),
			);
		}
	}
	assert.equal(
		step(release, ["jobs", "stable-ratchets"], "actions/checkout").get("ref"),
		`\${{ needs.release.outputs.sha }}`,
	);
	const workerSteps = ratchets.get("steps");
	assert.ok(isSeq(workerSteps));
	for (const item of workerSteps.items) {
		assert.ok(isMap(item));
		if (item.get("name") !== "Keep the ratchet results") {
			assert.equal(item.get("if"), "needs.release.outputs.prerelease != 'true'");
		}
	}
	const toolchain = workerSteps.items.find(
		(item) => isMap(item) && item.get("uses") === "./.github/actions/setup-toolchain",
	);
	assert.ok(isMap(toolchain));
	assert.equal(toolchain.getIn(["with", "install"]), "frozen");
	const check = workerSteps.items.find(
		(item) => isMap(item) && item.get("name") === "Check the planned stable version",
	);
	assert.ok(isMap(check));
	assert.equal(check.getIn(["env", "RELEASE_VERSION"]), `\${{ needs.release.outputs.version }}`);
	assert.equal(check.get("working-directory"), "server");
	assert.equal(
		check.get("run"),
		'./gradlew :application:releaseRatchetTest "-Phephaestus.releaseVersion=$RELEASE_VERSION"',
	);
	assert.equal(check.has("continue-on-error"), false);
	assert.equal(ratchets.has("continue-on-error"), false);

	const publisher = release.getIn(["jobs", "tag-images"]);
	assert.ok(isMap(publisher));
	const dependencies = publisher.get("needs");
	assert.ok(isSeq(dependencies));
	assert.ok(dependencies.toJSON().includes("stable-ratchets"));
	assert.equal(publisher.get("if"), "needs.release.outputs.released == 'true'");
	// No status function bypasses the dependency results: failed, cancelled or skipped ratchets block publication.
	for (const prerelease of ["true", "false", ""]) {
		assert.equal(
			expandExpressions(`\${{ ${asString(check.get("if"), "stable version condition")} }}`, {
				needs: { release: { outputs: { prerelease } } },
			}),
			String(prerelease !== "true"),
		);
	}
	const jobs = release.get("jobs");
	assert.ok(isMap(jobs));
	const dependsOnRatchets = (name: string): boolean => {
		if (name === "stable-ratchets") {
			return true;
		}
		const needs = jobs.getIn([name, "needs"]);
		if (typeof needs === "string") {
			return dependsOnRatchets(needs);
		}
		if (!isSeq(needs)) {
			return false;
		}
		return asStringArray(needs.toJSON(), `${name} dependencies`).some(dependsOnRatchets);
	};
	for (const name of ["tag-images", "publish-release"]) {
		assert.equal(dependsOnRatchets(name), true, `${name} must wait for the stable preflight`);
		assert.equal(jobs.getIn([name, "if"]), "needs.release.outputs.released == 'true'");
	}
});

void test("release eviction uses only its drill cluster and captured images and blocks publication", async () => {
	const release = parseDocument(await readFile(".github/workflows/release.yml", "utf8"));
	const kubernetes = parseDocument(await readFile(".github/workflows/ci-kubernetes.yml", "utf8"));
	const worker = release.getIn(["jobs", "kubernetes-eviction"]);
	assert.ok(isMap(worker));
	assert.equal(worker.get("uses"), "./.github/workflows/ci-kubernetes.yml");
	assert.equal(worker.getIn(["with", "commit"]), `\${{ needs.release.outputs.sha }}`);
	assert.equal(worker.getIn(["with", "ref"]), `\${{ needs.release.outputs.sha }}`);
	assert.equal(worker.getIn(["with", "queue"]), true);
	assert.equal(worker.getIn(["with", "eviction-drill"]), true);
	assert.equal(worker.getIn(["with", "drills-only"]), true);
	for (const name of ["application-server", "webapp", "agent-pi", "postgres"]) {
		assert.ok(
			String(worker.getIn(["with", "image-digests"])).includes(
				`"${name}":"\${{ needs.tag-images.outputs.${name}-digest }}"`,
			),
		);
		assert.equal(
			release.getIn(["jobs", "tag-images", "outputs", `${name}-digest`]),
			`\${{ steps.retag.outputs.${name}-digest }}`,
		);
	}
	for (const event of ["workflow_run", "workflow_dispatch"]) {
		for (const name of ["k3d", "k3d-drills", "k3d-1-35-amd64", "k3d-1-35-arm64", "calico"]) {
			assert.equal(
				jobRuns(kubernetes, name, event, {}, false, {
					queue: true,
					arm64: true,
					"drills-only": true,
				}),
				name === "k3d-drills",
				name,
			);
		}
	}
	assert.ok(needsOf(release, "publish-release").includes("kubernetes-eviction"));
	const completions: Record<string, Completion> = Object.fromEntries(
		[...needsClosure(release, "publish-release")].map((name) => [name, { result: "success" }]),
	);
	completions.release = { result: "success", outputs: { released: "true" } };
	for (const result of ["success", "failure", "cancelled", "skipped"]) {
		completions["kubernetes-eviction"] = { result };
		assert.equal(
			jobRuns(release, "publish-release", "workflow_run", completions),
			result === "success",
		);
	}
	const nightly = parseDocument(await readFile(".github/workflows/kubernetes-nightly.yml", "utf8"));
	assert.equal(nightly.getIn(["jobs", "Kubernetes", "with", "eviction-drill"]), true);
	const cicd = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	assert.equal(
		cicd.getIn(["jobs", "Kubernetes", "with", "eviction-drill"]),
		`\${{ github.event_name == 'workflow_dispatch' && inputs.eviction-drill }}`,
	);
});

void test("an old candidate lacks the release drill verifier and stops before any release write", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/release.yml", "utf8"));
	const steps = workflow.getIn(["jobs", "tag-images", "steps"]);
	assert.ok(isSeq(steps));
	const verifier = namedStep(
		workflow,
		["jobs", "tag-images"],
		"Require the release drill verifier",
	);
	assert.equal(verifier.get("run"), "test -f scripts/resolve-captured-release-images.ts");
	assert.equal(verifier.has("continue-on-error"), false);
	const checkout = steps.items[0];
	assert.ok(isMap(checkout));
	assert.match(String(checkout.get("uses")), /^actions\/checkout@/u);
	assert.equal(steps.items[1], verifier);
});
