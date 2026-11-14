import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";
import { parse as parseJsonc } from "jsonc-parser";
import { minimatch } from "minimatch";
import { isMap, isSeq } from "yaml";
import { commandsFor, scopesFor } from "./check-affected.ts";
import {
	assertNeeds,
	clusterMatrix,
	type Completion,
	expandExpressions,
	HELM_ARCHIVES,
	installsHelm,
	job,
	jobRuns,
	namedStep,
	needsClosure,
	needsOf,
	parseDocument,
	pathFilter,
	posixGlob,
	runScript,
	usesHelm,
} from "./ci-contract-support.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { asArray, asRecord, asString, isRecord } from "./lib/json.ts";
import { commandsOf, loadTasks } from "./lib/task-graph.ts";

void describe("ci-contract: kubernetes", () => {
	void test("a pull request qualifies the Kubernetes installation from its own images", async () => {
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		const workflow = parseDocument(source);
		assert.equal(workflow.getIn(["jobs", "Supported-host-smoke"]), undefined);
		const cluster = job(source, "Kubernetes");
		assert.match(cluster, /needs\.Docker\.result == 'success'/u);
		assert.match(cluster, /needs\.Docker\.outputs\.application-server-digest != ''/u);
		assert.doesNotMatch(cluster, /needs\.server-package/u);
		assert.match(cluster, /outputs\.kubernetes == 'true'/u);
		assert.match(
			cluster,
			/commit: \$\{\{ github\.event\.pull_request\.head\.sha \|\| github\.sha \}\}/u,
		);
		assertNeeds(source, "all-ci-passed", "Kubernetes");
		assert.deepEqual(needsOf(workflow, "Kubernetes"), ["detect-changes", "Docker"]);
		assert.ok(
			needsClosure(workflow, "Kubernetes").has("server-package"),
			"Images already waits for the packaged JAR",
		);
		assert.doesNotMatch(cluster, /application-artifact:/u);
		for (const digest of ["", `sha256:${"a".repeat(64)}`]) {
			assert.equal(
				jobRuns(workflow, "Kubernetes", "pull_request", {
					"detect-changes": {
						result: "success",
						outputs: { should_skip: "false", publishable: "true", kubernetes: "true" },
					},
					Docker: { result: "success", outputs: { "application-server-digest": digest } },
				}),
				digest !== "",
				"a partial image result without the application image cannot start a cluster",
			);
		}
		const reusable = await readFile(".github/workflows/ci-kubernetes.yml", "utf8");
		assert.match(reusable, /resolve-release-images\.ts "\$COMMIT"/u);
		assert.match(reusable, /kubernetes-cluster\.ts verify/u);
		assert.match(reusable, /kubernetes-cluster\.ts request/u);
		assert.doesNotMatch(
			reusable,
			/kubernetes-cluster\.ts live|restore-server-build|application-artifact/u,
		);
		assert.doesNotMatch(source, /sandbox-isolation-probe\.ts/u);
	});

	void test("the Kubernetes boot qualification runs on every source it can be broken by", async () => {
		const filter = pathFilter(await readFile(".github/workflows/cicd.yml", "utf8"), "kubernetes");
		const patterns = [...filter.matchAll(/^ +- '(?<pattern>.+)'$/gmu)].map(({ groups }) =>
			String(groups?.pattern),
		);
		const globbed = await Promise.all(patterns.map(posixGlob));
		const selected = new Set(globbed.flat());
		// Spring binds a class the moment it carries the annotation, wherever it lives, so the filter
		// has to select the whole binding tree rather than the part someone remembered.
		for (const file of await posixGlob("server/application/src/main/java/**/*.java")) {
			if (!/^@ConfigurationProperties(?:Scan)?\b/mu.test(await readFile(file, "utf8"))) {
				continue;
			}
			assert.ok(
				selected.has(file),
				`${file} binds configuration at boot, so a change to it must run the Kubernetes boot qualification`,
			);
		}
	});
});

void test("one pinned Helm installer verifies the official archive before it extracts the binary", async () => {
	const action = parseDocument(await readFile(".github/actions/setup-helm/action.yml", "utf8"));
	const install = namedStep(action, ["runs"], "Install Helm");
	assert.equal(install.getIn(["env", "HELM_VERSION"]), `v\${{ steps.tool.outputs.version }}`);
	assert.equal(install.getIn(["env", "RUNNER_PLATFORM"]), `\${{ runner.os }}/\${{ runner.arch }}`);
	const script = runScript(action, ["runs"], "Install Helm");
	const helm = asRecord(
		JSON.parse(await readFile(".github/ci-tools/helm.json", "utf8")),
		"Helm pins",
	);
	assert.equal(helm.version, "4.3.1");
	assert.equal(helm.url, "https://get.helm.sh/");
	const assets = asRecord(helm.assets, "Helm assets");
	for (const [platform, [name, format, checksum]] of HELM_ARCHIVES) {
		const key = platform
			.replace("Linux", "linux")
			.replace("macOS", "darwin")
			.replace("Windows", "win32")
			.replace("/X64", "-x64")
			.replace("/ARM64", "-arm64");
		const asset = asRecord(assets[key], "Helm asset");
		assert.equal(asset.name, `helm-v4.3.1-${name}.${format}`);
		assert.equal(asset.sha256, checksum);
	}
	assert.match(
		script,
		/CI_TOOL=helm node "\$ACTION_PATH\/\.\.\/\.\.\/\.\.\/scripts\/ci-tool\.ts" file/u,
	);
	const compared = script.indexOf('if [ "$actual" != "$checksum" ]; then');
	assert.ok(compared > 0, "the archive checksum must be compared");
	for (const extraction of ["unzip -q", "tar -xzf"]) {
		assert.ok(script.indexOf(extraction) > compared, `${extraction} must follow the checksum`);
	}
	assert.match(script, /unzip -q "\$work\/\$archive" "\$platform\/\$binary" -d "\$work"/u);
	assert.match(script, /tar -xzf "\$work\/\$archive" -C "\$work" "\$platform\/\$binary"/u);
	assert.match(script, /trap 'rm -rf "\$work"' EXIT/u);
	// Its own directory under the runner's temporary space, first on PATH, never a system location.
	assert.match(script, /install_dir="\$runner_temp\/helm-\$HELM_VERSION\/bin"/u);
	assert.match(
		script,
		/installed=\$\("\$install_dir\/\$binary" version --template '\{\{\.Version\}\}'\)/u,
	);
	assert.match(script, /cygpath -w "\$install_dir" >> "\$GITHUB_PATH"/u);
	assert.doesNotMatch(script, /\bsudo\b|\/usr\/local\/bin|\bhelm version --short\b/u);
	// The PATH check reads the one version from the install step instead of repeating it.
	const check = namedStep(action, ["runs"], "Check the Helm on PATH");
	assert.equal(check.getIn(["env", "HELM_VERSION"]), `\${{ steps.install.outputs.version }}`);
	assert.match(
		runScript(action, ["runs"], "Check the Helm on PATH"),
		/\*\/helm-"\$HELM_VERSION"\/bin\/helm \|/u,
	);
	for (const file of await posixGlob(".github/{actions,workflows}/**/*.{yml,yaml}")) {
		if (file !== ".github/actions/setup-helm/action.yml") {
			assert.doesNotMatch(await readFile(file, "utf8"), /get\.helm\.sh|HELM_VERSION/u, file);
		}
	}
});

void test("every job that runs Helm installs the pinned one first, and the render gate runs on Linux and Windows", async () => {
	const release = parseDocument(await readFile(".github/workflows/release.yml", "utf8"));
	const helmJobs: string[] = [];
	const jobs = release.get("jobs");
	assert.ok(isMap(jobs));
	for (const pair of jobs.items) {
		const steps = isMap(pair.value) ? pair.value.get("steps") : undefined;
		if (!isSeq(steps)) {
			continue;
		}
		const first = steps.items.findIndex(usesHelm);
		const installer = steps.items.findIndex(installsHelm);
		if (first === -1) {
			assert.equal(installer, -1, `${String(pair.key)} installs Helm it never runs`);
			continue;
		}
		helmJobs.push(String(pair.key));
		assert.ok(
			installer !== -1 && installer < first,
			`${String(pair.key)} runs Helm before the installer`,
		);
	}
	assert.deepEqual(helmJobs, ["tag-images", "publish-release"]);

	const leg = parseDocument(await readFile(".github/workflows/ci-quality-gates.yml", "utf8"));
	const setup = namedStep(leg, ["jobs", "quality"], "Setup Helm");
	assert.equal(setup.get("uses"), "./.github/actions/setup-helm");
	assert.equal(setup.get("if"), "env.QUALITY_LEG == 'tooling' || env.QUALITY_LEG == 'windows'");
	const legSteps = leg.getIn(["jobs", "quality", "steps"]);
	assert.ok(isSeq(legSteps));
	assert.ok(
		legSteps.items.indexOf(setup) <
			legSteps.items.indexOf(namedStep(leg, ["jobs", "quality"], "Quality gates")),
		"Helm is installed before the gates",
	);

	const tasks = await loadTasks();
	const dependencies = (entry: string) =>
		asArray(asRecord(tasks[entry], entry).dependsOn, `${entry} dependencies`);
	// The universal tooling suite owns both chart suites, the real Helm render included: its glob
	// matches them, and it reaches every quality entry that the Helm installer serves.
	const tooling = commandsOf(tasks["test:tooling"]).join(" ");
	const toolingGlobs = [...tooling.matchAll(/(?<glob>\S+\.test\.ts)/gu)].map(
		(match) => match.groups?.glob ?? "",
	);
	for (const file of [
		"scripts/kubernetes-chart.test.ts",
		"scripts/kubernetes-chart-render.test.ts",
	]) {
		assert.ok(existsSync(file), `${file} exists`);
		assert.ok(
			toolingGlobs.some((testGlob) => minimatch(file, testGlob)),
			`test:tooling must run ${file}`,
		);
	}
	assert.ok(dependencies("gate:tooling-tests").includes("test:tooling"));
	assert.ok(dependencies("gate:agents").includes("gate:tooling-tests"));
	for (const entry of ["quality", "ci:windows", "affected:agents"]) {
		assert.ok(dependencies(entry).includes("gate:agents"), `${entry} must run gate:agents`);
	}
	// The tooling leg runs the suite in its test jobs, which install Helm like its gates job.
	assert.ok(dependencies("ci:tooling-tests").includes("gate:tooling-tests"));
	// The convenience entry runs the two suites directly; the normal graph never runs them twice.
	const chartTests = asRecord(tasks["test:kubernetes:chart"], "test:kubernetes:chart");
	assert.deepEqual(commandsOf(chartTests), [
		"node --test scripts/kubernetes-chart.test.ts scripts/kubernetes-chart-render*.test.ts",
	]);
	assert.equal(chartTests.cache, false, "real Helm subprocesses are never cached");
	assert.equal(tasks["gate:kubernetes-chart"], undefined);
	for (const [name, task] of Object.entries(tasks)) {
		const dependsOn = isRecord(task) && Array.isArray(task.dependsOn) ? task.dependsOn : [];
		assert.ok(!dependsOn.includes("test:kubernetes:chart"), `${name} runs the chart suites again`);
	}
	// A chart change selects the scoped set that contains the render suite.
	const chartChange = scopesFor(["deploy/chart/hephaestus/values.yaml"]);
	assert.deepEqual(chartChange, ["agents"]);
	assert.deepEqual(commandsFor(chartChange), [["run", "affected:agents"]]);
	// The plain JSON and YAML of the chart and the platform files are formatted and checked; Helm
	// judges the chart's Go templates.
	for (const entry of ["format:config", "gate:config-format"]) {
		assert.match(
			commandsOf(tasks[entry]).join(" "),
			/vp fmt --(?:write|check) 'deploy\/\{chart,platform\}\/\*\*\/\*\.\{json,yaml\}'/u,
			entry,
		);
	}
	const formatter = asRecord(parseJsonc(await readFile(".oxfmtrc.json", "utf8")), ".oxfmtrc.json");
	assert.ok(
		asArray(formatter.ignorePatterns, "ignorePatterns").includes("deploy/chart/**/templates/**"),
		"the formatter ignores the chart's Go templates",
	);

	const detection = job(await readFile(".github/workflows/cicd.yml", "utf8"), "detect-changes");
	assert.match(pathFilter(detection, "tooling"), /- 'deploy\/chart\/\*\*'/u);
	assert.match(pathFilter(detection, "quality-config"), /- '\.github\/actions\/setup-helm\/\*\*'/u);
});

void test("a cluster leg is named for its distribution, minor and architecture, and its pinned image has that minor", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/ci-kubernetes.yml", "utf8"));
	const name = (leg: string) => String(workflow.getIn(["jobs", leg, "name"]));
	// A digest or patch version in a name starts a new check history at each image update.
	const jobs = workflow.get("jobs");
	assert.ok(isMap(jobs));
	for (const entry of jobs.items) {
		assert.doesNotMatch(name(String(entry.key)), /sha256|@|\d+\.\d+\.\d+/u);
	}
	assert.equal(workflow.hasIn(["jobs", "schema"]), false);
	assert.equal(name("k3d"), "k3s 1.37 amd64");
	const minors = [
		...new Set(
			clusterMatrix(true, true)
				.include.filter((leg) => leg.leg === "k3d")
				.map((leg) => leg.minor),
		),
	];
	const images: unknown = JSON.parse(String(workflow.getIn(["env", "K3S_IMAGES"])));
	assert.ok(typeof images === "object" && images !== null);
	assert.deepEqual(Object.keys(images), minors);
	for (const [minor, image] of Object.entries(images)) {
		assert.match(minor, /^1\.\d+$/u);
		assert.match(
			String(image),
			new RegExp(
				`^docker\\.io/rancher/k3s:v${minor.replace(".", String.raw`\.`)}\\.\\d+-k3s\\d+@sha256:[a-f0-9]{64}$`,
				"u",
			),
		);
	}
	const kind = /^kind (?<minor>1\.\d+) Calico$/u.exec(name("calico"));
	assert.ok(kind?.groups?.minor !== undefined);
	assert.match(
		String(workflow.getIn(["jobs", "calico", "env", "NODE_IMAGE"])),
		new RegExp(
			`^docker\\.io/kindest/node:v${kind.groups.minor.replace(".", String.raw`\.`)}\\.\\d+@sha256:[a-f0-9]{64}$`,
			"u",
		),
	);
});

void test("chart lint runs once in its latest-k3s consumer with its parser dependencies installed", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/ci-kubernetes.yml", "utf8"));
	const jobs = asRecord(asRecord(workflow.toJSON(), "Kubernetes").jobs, "jobs");
	let linters = 0;
	for (const [key, value] of Object.entries(jobs)) {
		if (!key.startsWith("k3d")) {
			continue;
		}
		const worker = asRecord(value, key);
		const environment = asRecord(worker.env, "environment");
		const steps = asArray(worker.steps, "steps").map((declaration) =>
			asRecord(declaration, "declaration"),
		);
		const selected = steps
			.filter(
				(declaration) =>
					declaration.uses === "./.github/actions/setup-toolchain" ||
					(typeof declaration.run === "string" &&
						declaration.run.includes("node scripts/crd-schemas.ts")),
			)
			.filter(
				(declaration) =>
					declaration.if === undefined ||
					expandExpressions(
						`\${{ ${asString(declaration.if, "declaration condition").replaceAll(/^\$\{\{\s*|\s*\}\}$/gu, "")} }}`,
						{
							env: environment,
							inputs: {},
							github: { event_name: "pull_request" },
						},
					) === "true",
			);
		const toolchain = selected.filter(
			(declaration) => declaration.uses === "./.github/actions/setup-toolchain",
		);
		assert.equal(toolchain.length, 1, key);
		const lint = selected.find(
			(declaration) =>
				typeof declaration.run === "string" &&
				declaration.run.includes("node scripts/crd-schemas.ts"),
		);
		assert.equal(Boolean(lint), key === "k3d", key);
		assert.equal(
			asRecord(toolchain[0]?.with, "toolchain inputs").install,
			lint ? "frozen" : "none",
		);
		if (lint) {
			linters += 1;
			assert.ok(selected.indexOf(toolchain[0] ?? {}) < selected.indexOf(lint));
		}
	}
	assert.equal(linters, 1);
});

void test("the prepull drill stays on the latest amd64 consumer after flattening cluster jobs", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/ci-kubernetes.yml", "utf8"));
	for (const key of ["k3d", "k3d-1-35-amd64", "k3d-1-35-arm64"]) {
		const prepullStep = namedStep(
			workflow,
			["jobs", key],
			"Start a sandbox on a new node without an image pull",
		);
		const environment = workflow.getIn(["jobs", key, "env"]);
		assert.ok(isMap(environment));
		assert.equal(
			expandExpressions(prepullStep.get("if"), { env: environment.toJSON() }),
			String(key === "k3d"),
		);
		assert.match(String(prepullStep.get("run")), /node scripts\/sandbox-prepull-drill\.ts/u);
	}
});

void test("a nightly eviction failure opens or updates the existing Kubernetes tracking issue", async (context) => {
	const nightly = parseDocument(await readFile(".github/workflows/kubernetes-nightly.yml", "utf8"));
	assert.ok(needsOf(nightly, "report").includes("Kubernetes"));
	assert.equal(nightly.getIn(["jobs", "report", "if"]), "failure()");
	const script = asString(
		nightly.getIn(["jobs", "report", "steps", 0, "run"]),
		"nightly report script",
	);
	const directory = await mkdtemp(path.join(tmpdir(), "nightly-eviction-report-"));
	context.after(async () => rm(directory, { recursive: true, force: true }));
	const log = path.join(directory, "calls.log");
	const answer = path.join(directory, "issues.json");
	await writeFile(
		path.join(directory, "gh"),
		`#!/bin/sh\necho "$*" >> "${log}"\n[ "$1 $2" = "issue list" ] && cat "${answer}"\nexit 0\n`,
	);
	await chmod(path.join(directory, "gh"), 0o755);
	for (const existing of [false, true]) {
		await writeFile(log, "");
		await writeFile(
			answer,
			JSON.stringify(existing ? [{ number: 7, title: "The nightly Kubernetes run failed" }] : []),
		);
		const result = spawnSync("bash", ["--noprofile", "--norc", "-c", script], {
			encoding: "utf8",
			env: environmentForGitFixture({
				PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
				REPOSITORY: "owner/repository",
				COMMIT: "tested-commit",
				RUN_URL: "https://example.invalid/run",
				BUILD: "success",
				KUBERNETES: "failure",
				FOOTPRINT: "success",
			}),
		});
		assert.equal(result.status, 0, result.stderr);
		const calls = await readFile(log, "utf8");
		assert.match(calls, existing ? /issue comment 7/u : /issue create/u);
		assert.match(calls, /The nightly Kubernetes run failed/u);
		assert.match(calls, /tested-commit/u);
		assert.match(calls, /https:\/\/example.invalid\/run/u);
	}
});

void test("an explicit eviction dispatch selects Kubernetes without a Kubernetes path change", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	const completions: Record<string, Completion> = Object.fromEntries(
		[...needsClosure(workflow, "Kubernetes")].map((name) => [name, { result: "success" }]),
	);
	completions["detect-changes"] = {
		result: "success",
		outputs: {
			kubernetes: "false",
			publishable: "true",
			should_skip: "false",
			"queue-run": "",
		},
	};
	completions.Docker = {
		result: "success",
		outputs: { "application-server-digest": "sha256:published" },
	};
	for (const eviction of [false, true]) {
		assert.equal(
			jobRuns(workflow, "Kubernetes", "workflow_dispatch", completions, false, {
				"eviction-drill": eviction,
			}),
			eviction,
		);
	}
	completions.Docker = { result: "failure" };
	assert.equal(
		jobRuns(workflow, "Kubernetes", "workflow_dispatch", completions, false, {
			"eviction-drill": true,
		}),
		false,
		"requesting eviction cannot bypass failed image producers",
	);
});
