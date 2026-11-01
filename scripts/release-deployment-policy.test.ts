import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { parseDocument } from "yaml";

import { asArray, asRecord, asString, asStringArray, at } from "./lib/json.ts";
import { releaseSignerIdentity, releaseSignerRepository } from "./lib/release-signer.ts";
import { POSTGRES_IMAGE_INPUTS } from "./reconcile-deployment.ts";

const release = readFileSync(".github/workflows/release.yml", "utf8");
const promotion = readFileSync(".github/workflows/promote.yml", "utf8");
const resolver = readFileSync("scripts/resolve-promotion.ts", "utf8");
const reconciler = readFileSync("scripts/reconcile-deployment.ts", "utf8");
const upgradeDrill = readFileSync("scripts/release-upgrade-test.ts", "utf8");

const upgrade = readFileSync(".github/workflows/release-upgrade.yml", "utf8");

await test("release publication requires the seeded upgrade gate", () => {
	assert.match(
		release,
		/publish-release:\n\s+needs: \[release, tag-images, upgrade-test, k3s-host-smoke\]/u,
	);
	assert.match(release, /previous-version: \$\{\{ needs\.release\.outputs\.previous_version \}\}/u);
	assert.match(upgrade, /INPUT_PREVIOUS_VERSION: \$\{\{ inputs\.previous-version \}\}/u);
	assert.match(
		release,
		/candidate-application-image: .*@\$\{\{ needs\.tag-images\.outputs\.application-server-digest \}\}/u,
	);
	assert.match(
		release,
		/postgres-image: .*@\$\{\{ needs\.tag-images\.outputs\.postgres-digest \}\}/u,
	);
	assert.match(release, /candidate-source-sha: \$\{\{ needs\.release\.outputs\.sha \}\}/u);
	assert.match(upgrade, /schedule:/u);
	assert.match(
		upgrade,
		/ref: \$\{\{ inputs\.candidate-source-sha \|\| inputs\.candidate-sha \|\| github\.sha \}\}/u,
	);
	const upgradeGate = / {2}upgrade-test:[\s\S]*?\n {2}k3s-host-smoke:/u.exec(release)?.[0] ?? "";
	assert.doesNotMatch(upgradeGate, /secrets: inherit/u);
});

await test("release upgrade runs the server topology without optional infrastructure", () => {
	assert.match(upgradeDrill, /"HEPHAESTUS_RUNTIME_WORKER_ENABLED=false"/u);
	assert.match(upgradeDrill, /"HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED=false"/u);
	assert.match(upgradeDrill, /"HEPHAESTUS_SYNC_NATS_ENABLED=false"/u);
	assert.doesNotMatch(upgradeDrill, /"NATS_ENABLED=false"/u);
});

await test("verification identity is the release's own: run context now, the map for history", () => {
	const rescan = readFileSync(".github/workflows/rescan-images.yml", "utf8");
	const rescanSource = readFileSync("scripts/rescan-release-images.ts", "utf8");
	const prepareChart = readFileSync("scripts/prepare-release-chart.ts", "utf8");
	const releaseChart = readFileSync("scripts/release-chart.ts", "utf8");
	const derivedIdentity =
		/\$\{\{ github\.server_url \}\}\/\$\{\{ github\.repository \}\}\/\.github\/workflows\/release\.yml@refs\/heads\/main/u;

	assert.match(release, derivedIdentity);
	assert.match(rescanSource, /releaseCertificateIdentity\(release, process\.env\)/u);
	assert.match(prepareChart, /environment: process\.env/u);
	assert.match(prepareChart, /releaseCertificateIdentity\(release, dependencies\.environment\)/u);
	assert.match(prepareChart, /releaseRepository\(release, dependencies\.environment\)/u);
	// The chart digest is verified with the same identity as the lock it is named in.
	assert.match(releaseChart, /releaseCertificateIdentity\(lock\.release, environment\)/u);
	assert.match(
		releaseChart,
		/readChartLock\(lockPath, release\),\n\t+await readInventory\(inventory\),\n\t+destination,\n\t+process\.env,\n/u,
	);
	for (const contents of [release, reconciler, rescan, rescanSource, prepareChart, releaseChart]) {
		assert.doesNotMatch(contents, /certificate-identity[^\n]*\n?[^\n]*ls1intum\/Hephaestus/u);
		assert.doesNotMatch(
			contents,
			/certificate-identity[^\n]*\n?[^\n]*hephaestus-build\/Hephaestus/u,
		);
	}
});

const promotionWorkflow: unknown = parseDocument(promotion).toJS();
const promoteSteps = asArray(
	at(promotionWorkflow, ["jobs", "promote", "steps"], "promote.yml"),
	"promote steps",
).map((step, index) => asRecord(step, `promote step ${index}`));

/** One field of a named `promote` step, such as `if` or `env.RELEASE`. */
function promoteStep(name: string, path: readonly string[]): unknown {
	const step = promoteSteps.find((candidate) => candidate.name === name);
	assert.ok(step, `promote.yml must keep the step '${name}'`);
	return at(step, path, name);
}

const resolved = (output: string) => `\${{ steps.release.outputs.${output} }}`;

await test("every promotion decision is taken by the script that owns it", () => {
	// Each decision below is proven by its own spec; what no spec can see is the workflow that
	// stops calling it, or a step reading a channel file the resolver did not write.
	assert.match(promotion, /^ +run: node scripts\/resolve-promotion\.ts$/mu);
	assert.equal(promoteStep("Sign the channel", ["env", "CHANNEL_FILE"]), resolved("channel"));
	assert.equal(
		promoteStep("Record the deployment", ["env", "CHANNEL_FILE"]),
		`deploy-state/${resolved("channel")}`,
	);
	// Rollback must support releases predating immutable tags; the signed lock binds their digests.
	assert.doesNotMatch(resolver, /isImmutable/u);
});

await test("a promotion records one deployment, never blocks on it, and always closes it", () => {
	assert.equal(
		at(promotionWorkflow, ["jobs", "promote", "environment", "deployment"], "promote.yml"),
		false,
	);
	const order = promoteSteps.map(({ name }) => name);
	assert.ok(order.indexOf("Record the deployment") < order.indexOf("Sign the channel"));
	assert.equal(promoteStep("Record the deployment", ["continue-on-error"]), true);
	const finish = "Finish the deployment record";
	assert.equal(
		promoteStep(finish, ["if"]),
		`\${{ always() && steps.deployment.outputs.id != '' }}`,
	);
	assert.equal(promoteStep(finish, ["continue-on-error"]), true);
});

function referenced(expression: string, pattern: RegExp): string[] {
	return [...expression.matchAll(pattern)].flatMap(({ groups }) =>
		groups?.name === undefined ? [] : [groups.name],
	);
}

/**
 * What makes `cicd.yml` rebuild the published PostgreSQL image, split into the path globs the change
 * filters behind it name and the outputs that decide a rebuild without reading a diff at all.
 */
function postgresRebuildTriggers(): { paths: string[]; unconditional: string[] } {
	const workflow: unknown = parseDocument(
		readFileSync(".github/workflows/cicd.yml", "utf8"),
	).toJS();
	const detect = asRecord(at(workflow, ["jobs", "detect-changes"], "cicd.yml"), "detect-changes");
	const outputs = asRecord(detect.outputs, "detect-changes.outputs");
	const step = asArray(detect.steps, "detect-changes.steps")
		.map((value, index) => asRecord(value, `detect-changes.steps[${index}]`))
		.find((candidate) => candidate.id === "filter");
	if (step === undefined) {
		throw new Error("detect-changes declares no `filter` step");
	}
	// dorny/paths-filter takes its filters as YAML inside a string, so they parse in a second pass.
	const filters = asRecord(
		parseDocument(
			asString(asRecord(step.with, "the filter step").filters, "the filter step's filters"),
		).toJS(),
		"the change filters",
	);
	const condition = asString(
		at(workflow, ["jobs", "Docker", "with", "postgres_image_changed"], "cicd.yml"),
		"Docker's postgres_image_changed input",
	);

	const paths = new Set<string>();
	const unconditional = new Set<string>();
	for (const name of referenced(condition, /needs\.detect-changes\.outputs\.(?<name>[\w-]+)/gu)) {
		// A host follows main's commits, which push runs build, so a clause for merge groups alone
		// never rebuilds an image the host applies.
		const expression = asString(outputs[name], `detect-changes.outputs.${name}`).replaceAll(
			/\(github\.event_name == 'merge_group' && [^)]*\)/gu,
			"",
		);
		const behind = referenced(expression, /steps\.filter\.outputs\.(?<name>[\w-]+)/gu);
		if (behind.length === 0) {
			unconditional.add(name);
		}
		for (const filter of behind) {
			for (const glob of asStringArray(filters[filter], `the ${filter} filter`)) {
				paths.add(glob);
			}
		}
	}
	return { paths: [...paths], unconditional: [...unconditional].toSorted() };
}

await test("a host following commits watches every path CI rebuilds the database image for", () => {
	const { paths, unconditional } = postgresRebuildTriggers();
	// A path CI rebuilds for that the host does not diff is a rebuilt image the host never applies.
	assert.deepEqual([...paths].toSorted(), [...POSTGRES_IMAGE_INPUTS].toSorted());
	// What is left decides a rebuild without reading the diff, so the host cannot answer it from the
	// two commits; it takes those rebuilds on a day's cadence instead. A new entry here is a decision
	// about `commitImages`, not a list to extend.
	assert.deepEqual(unconditional, ["all-images"]);
});

await test("derives the canonical signer identity and refuses missing CI identity", () => {
	const canonicalRun = {
		CI: "true",
		GITHUB_SERVER_URL: "https://github.com",
		GITHUB_REPOSITORY: "hephaestus-build/Hephaestus",
	};
	assert.equal(releaseSignerRepository(canonicalRun), "hephaestus-build/Hephaestus");
	assert.equal(
		releaseSignerIdentity(canonicalRun),
		"https://github.com/hephaestus-build/Hephaestus/.github/workflows/release.yml@refs/heads/main",
	);
	assert.equal(
		releaseSignerIdentity({}),
		"https://github.com/hephaestus-build/Hephaestus/.github/workflows/release.yml@refs/heads/main",
	);
	assert.throws(() => releaseSignerRepository({ CI: "true" }), /GITHUB_REPOSITORY/u);
});

await test("release publication requires native k3s smokes and receipts for both architectures", () => {
	const smoke = readFileSync(".github/workflows/release-host-smoke.yml", "utf8");
	assert.match(smoke, /architecture: amd64\n\s+runner: ubuntu-24\.04/u);
	assert.match(smoke, /architecture: arm64\n\s+runner: ubuntu-24\.04-arm/u);
	assert.match(smoke, /kubernetes-cluster\.ts release-smoke/u);
	assert.match(smoke, /--receipt "\$RUNNER_TEMP\/host-smoke-k3s-\$ARCHITECTURE\.json"/u);
	assert.match(release, /needs: \[release, tag-images, upgrade-test, k3s-host-smoke\]/u);
	assert.match(release, /gh release upload "\$TAG_NAME" host-smoke-k3s\/\*\.json/u);
	assert.doesNotMatch(release, /docker compose|supported-host-smoke:|sandbox-isolation-probe\.ts/u);
});

await test("a release publishes one signed k3s bundle, and the smoke runs what operators unpack", () => {
	const workflow: unknown = parseDocument(release).toJS();
	const step = (name: string) =>
		asString(
			asRecord(
				asArray(at(workflow, ["jobs", "tag-images", "steps"], "tag-images"), "steps")
					.map((value, index) => asRecord(value, `steps[${index}]`))
					.find((candidate) => candidate.name === name),
				name,
			).run,
			name,
		);
	// Built from the deploy/platform folder and covered by the release attestation.
	assert.match(
		step("Build the k3s bundle"),
		/package-k3s-bundle\.ts "\$bundle" "\$\(git log -1 --format=%ct\)"/u,
	);
	assert.match(step("Generate subject checksums"), /sha256sum "\$K3S_BUNDLE" >> subjects\.sha256/u);
	// Signed and verified with the release identity, then uploaded with its signature.
	assert.match(
		step("Sign release image lock"),
		/for lock in "\$JOINT" "\$K3S_BUNDLE"; do\n\s+node scripts\/keyless-sign\.ts sign-blob/u,
	);
	assert.match(
		step("Verify signature with the deploy-side identity (fail fast)"),
		/for lock in "\$JOINT" "\$K3S_BUNDLE"; do\n\s+cosign verify-blob/u,
	);
	assert.match(
		step("Upload release image lock to GitHub Release"),
		/"\$K3S_BUNDLE" "\$\{K3S_BUNDLE\}\.sigstore\.json" evidence\/\*/u,
	);
	// No loose k3s file is collected, signed or uploaded.
	assert.doesNotMatch(release, /k3s-platform\/|cp deploy\/platform|find evidence -name 'k3s-/u);
	// The smoke downloads the bundle, checks its signature, and unpacks it before the installer runs.
	const smoke = readFileSync(".github/workflows/release-host-smoke.yml", "utf8");
	assert.match(smoke, /bundle="hephaestus-k3s-\$\{TAG#v\}\.tar\.gz"/u);
	assert.match(smoke, /cosign verify-blob --bundle "k3s-download\/\$bundle\.sigstore\.json"/u);
	assert.match(smoke, /tar -xzf "k3s-download\/\$bundle" -C k3s-assets/u);
	assert.doesNotMatch(smoke, /--pattern 'k3s-\*'/u);
});

await test("release evidence waits for the digest-bound sandbox check", () => {
	const workflow: unknown = parseDocument(release).toJS();
	const steps = asArray(
		at(workflow, ["jobs", "tag-images", "steps"], "tag-images"),
		"tag-images",
	).map((value, index) => asRecord(value, `tag-images.steps[${index}]`));
	const digests = steps.findIndex((step) => step.id === "retag");
	const sandbox = steps.findIndex((step) => step.id === "sandbox-contract");
	const security = steps.findIndex(
		(step) => step.uses === "./.github/actions/setup-release-security-tools",
	);
	const database = steps.findIndex((step) => step.uses === "./.github/actions/download-trivy-db");
	const join = steps.findIndex((step) => step.wait === "sandbox-contract");
	const evidence = steps.findIndex((step) => step.name === "Generate and enforce release evidence");
	assert.ok(digests !== -1 && sandbox > digests && security > sandbox && database > security);
	assert.ok(join > database && evidence > join);
	const sandboxStep = steps[sandbox];
	assert.ok(sandboxStep);
	assert.equal(sandboxStep.background, true);
	assert.equal(typeof sandboxStep.run, "string");
	assert.deepEqual(
		steps.filter((step) => step.background === true).map((step) => step.id),
		["sandbox-contract"],
	);
	for (const name of ["publish-release"]) {
		const preparation = asArray(at(workflow, ["jobs", name, "steps"], name), name);
		assert.ok(preparation.every((value) => asRecord(value, name).background !== true));
	}
});

/** A step's `if` as GitHub would evaluate it, for a condition that uses no status function. */
function runsWhen(condition: string, apply: string, automatic: boolean): boolean {
	const expression = /^\$\{\{(?<body>[\s\S]+)\}\}$/u.exec(condition)?.groups?.body ?? condition;
	const parsed = new Parser(new Lexer(expression).lex().tokens, ["inputs", "steps"], []).parse();
	const context: unknown = JSON.parse(
		JSON.stringify({
			inputs: { automatic, commit: "c".repeat(40), freeze: false },
			// A skipped step with an id still has its outputs, each an empty string.
			steps: { policy: { outputs: { apply } } },
		}),
		data.reviver,
	);
	assert.ok(context instanceof data.Dictionary);
	return new Evaluator(parsed, context).evaluate().coerceString() === "true";
}

await test("an automatic promotion decides under the workflow lock, in the job that publishes", () => {
	const workflow = parseDocument(promotion);
	assert.equal(workflow.getIn(["concurrency", "group"]), `promote-\${{ inputs.environment }}`);
	assert.equal(workflow.getIn(["concurrency", "cancel-in-progress"]), false);
	// A job before it would wait for a second runner before the promotion could start.
	const jobs = asRecord(at(promotionWorkflow, ["jobs"], "promote.yml"), "jobs");
	for (const [name, job] of Object.entries(jobs)) {
		if (name !== "promote") {
			assert.equal(asRecord(job, name).needs, "promote", `${name} must run after the promotion`);
		}
	}
	assert.equal(promoteStep("Check automatic promotion", ["if"]), `\${{ inputs.automatic }}`);
	const policy = promoteSteps.findIndex(({ name }) => name === "Check automatic promotion");
	// Every step that resolves, records, signs, publishes or observes a channel follows the decision:
	// none runs when it skipped the request, and all run when it applied it or nobody asked it.
	for (const step of promoteSteps.slice(policy + 1)) {
		const label = String(step.name ?? step.uses);
		const condition = asString(step.if, `${label} condition`);
		// These two read outputs that only the steps below can set.
		if (condition.startsWith("${{ always()")) {
			continue;
		}
		assert.equal(
			runsWhen(condition, "false", true),
			false,
			`${label} must not run for a skipped request`,
		);
		assert.equal(
			runsWhen(condition, "true", true),
			true,
			`${label} must run for an applied request`,
		);
		assert.equal(runsWhen(condition, "", false), true, `${label} must run for a manual promotion`);
	}
});
