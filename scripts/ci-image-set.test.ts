import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { Minimatch } from "minimatch";
import { isMap, isSeq, parseDocument } from "yaml";

// The Kubernetes legs, the host boot smoke and the release preflight install every image under the
// run's commit. Jobs put an image there by building it, by reusing the merge group's build on a push,
// or by aliasing it, under conditions that differ per event. A combination where none runs fails only
// in the run that meets it, often a later pull request's merge group, so this test evaluates the
// workflow's own conditions for every combination instead. An alias base here is a published commit;
// resolve-alias-base.test.ts owns whether the resolver returns only such a commit.

const cicd = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
const docker = parseDocument(await readFile(".github/workflows/ci-docker-build.yml", "utf8"));
const reusableBuild = parseDocument(
	await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
);

const functions = [
	{ name: "cancelled", minArgs: 0, maxArgs: 0, call: () => new data.BooleanData(false) },
];
const parsed = new Map<string, ReturnType<Parser["parse"]>>();

function dictionary(context: Record<string, unknown>): data.Dictionary {
	const converted: unknown = JSON.parse(JSON.stringify(context), data.reviver);
	assert.ok(converted instanceof data.Dictionary);
	return converted;
}

function evaluate(source: unknown, names: string[], context: data.Dictionary): string {
	assert.ok(typeof source === "string", `cannot evaluate ${String(source)}`);
	const key = `${names.join(",")}\n${source}`;
	let expression = parsed.get(key);
	if (expression === undefined) {
		expression = new Parser(
			new Lexer(source.trim().replaceAll(/^\$\{\{\s*|\s*\}\}$/gu, "")).lex().tokens,
			names,
			functions,
		).parse();
		parsed.set(key, expression);
	}
	return new Evaluator(expression, context, new Map(functions.map((entry) => [entry.name, entry])))
		.evaluate()
		.coerceString();
}

const IMAGES = {
	"application-server": "application_server_changed",
	webapp: "webapp_changed",
	"agent-pi": "agent_images_changed",
	postgres: "postgres_image_changed",
} as const;

// The filters, as the workflow states them. paths-filter's `some-with-excludes`: a file matches a
// filter when it matches a pattern and no `!` pattern.
const filterStep = (() => {
	const steps = cicd.getIn(["jobs", "detect-changes", "steps"]);
	assert.ok(isSeq(steps));
	const step = steps.items.find((item) => isMap(item) && item.get("id") === "filter");
	assert.ok(isMap(step));
	return step;
})();
const filters = parseDocument(String(filterStep.getIn(["with", "filters"]))).contents;
assert.ok(isMap(filters));
assert.equal(filterStep.getIn(["with", "predicate-quantifier"]), "some-with-excludes");

function compile(pattern: string) {
	return new Minimatch(pattern, { dot: true });
}

const MATCHERS = filters.items.map((item) => {
	assert.ok(isSeq(item.value));
	const patterns = item.value.items.map(String);
	return {
		name: String(item.key),
		include: patterns.filter((pattern) => !pattern.startsWith("!")).map(compile),
		exclude: patterns
			.filter((pattern) => pattern.startsWith("!"))
			.map((pattern) => compile(pattern.slice(1))),
	};
});

function filterOutputs(paths: readonly string[]): Record<string, string> {
	return Object.fromEntries(
		MATCHERS.map(({ name, include, exclude }) => [
			name,
			String(
				paths.some(
					(path) =>
						include.some((pattern) => pattern.match(path)) &&
						!exclude.some((pattern) => pattern.match(path)),
				),
			),
		]),
	);
}

// One changed file per kind of change that selects an image or a consumer. A bot's pull request
// carries no changeset, so `.changeset/**` is one of them rather than always present.
const PATHS = [
	"deploy/chart/hephaestus/values.yaml",
	".github/workflows/ci-kubernetes.yml",
	".github/actions/setup-kubernetes-tools/action.yml",
	"scripts/kubernetes-cluster.ts",
	"server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/sandbox/SandboxRuntime.java",
	"server/application/src/main/resources/application.yml",
	"server/application/src/test/java/de/tum/cit/aet/hephaestus/ExampleTest.java",
	"docker/agents/pi/Dockerfile",
	"docker/postgres/Dockerfile",
	"docker/compose.app.yaml",
	"docker/docker-bake.hcl",
	"webapp/src/main.tsx",
	"webapp/src/main.test.tsx",
	".changeset/example.md",
	"docs/user/index.mdx",
	".github/workflows/cicd.yml",
	// Selects the host boot smoke and no image filter.
	".github/workflows/ci-build.yml",
];

const VERSION_FILES = ["package.json", "CHANGELOG.md", ".changeset/example.md"];

// Every change of one to three of those files.
function changes(): string[][] {
	const all: string[][] = [];
	for (const [first, a] of PATHS.entries()) {
		all.push([a]);
		for (const [second, b] of PATHS.entries()) {
			if (second <= first) {
				continue;
			}
			all.push([a, b]);
			for (const c of PATHS.slice(second + 1)) {
				all.push([a, b, c]);
			}
		}
	}
	return all;
}

const ALL_IMAGES = JSON.stringify(Object.keys(IMAGES));

interface Case {
	event: string;
	paths: string[];
	filters: Record<string, string>;
	aliasBase: string;
	preview: string;
	releaseCandidate: string;
	/** On a push, the images that the merge group's run published, as a JSON list. */
	queueImages: string;
	versionBump: string;
	/** A dispatch's `release-preflight` input. */
	preflight: string;
	/** A run that builds no image, a draft that builds because of its label, or a failed image build. */
	skip: "" | "fork" | "draft" | "draft-preview" | "should-skip" | "package-fails" | "docker-fails";
}

interface Settings {
	event: string;
	aliasBase: string[];
	preview: string[];
	releaseCandidate: string[];
	queue: { queueImages: string; versionBump: string }[];
	preflight: string[];
	/** The runs that put no image under the commit, each tried on its own, with what the run asks for. */
	skips: { skip: Case["skip"]; releaseCandidate?: string; preflight?: string }[];
}

// Only a pull request has a `preview` label. A push resolves no alias base; it reuses what its merge
// group published, all of it or a part. A release candidate is the Version PR's branch or a merge
// group that bumps the version; it builds every image itself, so its alias base does not matter, and
// its diff holds the version files. A dispatch may ask for the release preflight. A fork publishes
// nothing, a draft builds only with its `preview` label, a skipped run builds nothing, and a failed
// build stops every job that needs it.
const NO_QUEUE = { queueImages: "[]", versionBump: "false" };
const SETTINGS: Settings[] = [
	{
		event: "pull_request",
		aliasBase: ["", "0123abc"],
		preview: ["", "true", "false"],
		releaseCandidate: ["false", "true"],
		queue: [NO_QUEUE],
		preflight: ["false"],
		skips: [
			{ skip: "fork" },
			{ skip: "draft" },
			{ skip: "draft-preview" },
			{ skip: "should-skip" },
			{ skip: "package-fails" },
			{ skip: "docker-fails" },
			{ skip: "docker-fails", releaseCandidate: "true" },
		],
	},
	{
		event: "merge_group",
		aliasBase: ["", "0123abc"],
		preview: [""],
		releaseCandidate: ["false", "true"],
		queue: [NO_QUEUE],
		preflight: ["false"],
		skips: [
			{ skip: "package-fails" },
			{ skip: "docker-fails" },
			{ skip: "docker-fails", releaseCandidate: "true" },
		],
	},
	{
		event: "push",
		aliasBase: [""],
		preview: [""],
		releaseCandidate: ["false"],
		queue: [
			NO_QUEUE,
			{ queueImages: JSON.stringify(["application-server"]), versionBump: "false" },
			{ queueImages: JSON.stringify(["webapp", "agent-pi", "postgres"]), versionBump: "false" },
			{ queueImages: ALL_IMAGES, versionBump: "false" },
			{ queueImages: ALL_IMAGES, versionBump: "true" },
		],
		preflight: ["false"],
		skips: [{ skip: "docker-fails" }],
	},
	{
		event: "workflow_dispatch",
		aliasBase: [""],
		preview: [""],
		releaseCandidate: ["false"],
		queue: [NO_QUEUE],
		preflight: ["false", "true"],
		skips: [{ skip: "docker-fails" }, { skip: "docker-fails", preflight: "true" }],
	},
];

function* cases(): Generator<Case> {
	const changed = changes().map((paths) => ({ paths, filters: filterOutputs(paths) }));
	const released = changed.map(({ paths }) => {
		const withVersion = [...new Set([...paths, ...VERSION_FILES])];
		return { paths: withVersion, filters: filterOutputs(withVersion) };
	});
	for (const { event, aliasBase, preview, releaseCandidate, queue, preflight, skips } of SETTINGS) {
		const settings: Omit<Case, "event" | "paths" | "filters">[] = releaseCandidate.flatMap(
			(candidate) =>
				(candidate === "true" ? [""] : aliasBase).flatMap((base) =>
					preview.flatMap((label) =>
						queue.flatMap((reuse) =>
							preflight.map((evidence) => ({
								aliasBase: base,
								preview: label,
								releaseCandidate: candidate,
								...reuse,
								preflight: evidence,
								skip: "" as const,
							})),
						),
					),
				),
		);
		for (const {
			skip,
			releaseCandidate: candidate = "false",
			preflight: evidence = "false",
		} of skips) {
			settings.push({
				aliasBase: candidate === "true" ? "" : (aliasBase.at(-1) ?? ""),
				preview: { "should-skip": "false", "draft-preview": "true" }[skip as string] ?? "",
				releaseCandidate: candidate,
				...NO_QUEUE,
				preflight: evidence,
				skip,
			});
		}
		for (const setting of settings) {
			for (const change of setting.releaseCandidate === "true" ? released : changed) {
				yield { event, ...change, ...setting };
			}
		}
	}
}

const CONSUMERS = ["Kubernetes", "Release-preflight"];

// The detect-changes outputs that these jobs read; evaluating only those keeps the test fast.
const READ = new Set(
	[
		...["server-package", "vulnerability-database", "Docker", ...CONSUMERS].map((name) =>
			String(cicd.getIn(["jobs", name, "if"])),
		),
		JSON.stringify(cicd.getIn(["jobs", "Docker", "with"])),
	]
		.join("\n")
		.matchAll(/needs\.detect-changes\.outputs\.(?<name>[\w-]+)/gu)
		.map(({ groups }) => String(groups?.name)),
);

/** The consumers each event needs, stated from the run's facts rather than from the workflow. */
function needed(entry: Case): Record<string, boolean | undefined> {
	// A failed package may or may not leave an image missing, by whether the base is aliased; only the
	// safety assertions judge that run.
	if (entry.skip === "package-fails") {
		return {};
	}
	const fails = entry.skip === "docker-fails";
	const builtAtAll =
		!fails && entry.skip !== "draft" && entry.skip !== "fork" && entry.skip !== "should-skip";
	const evidence =
		entry.releaseCandidate === "true" ||
		(entry.event === "workflow_dispatch" && entry.preflight === "true");
	return {
		// A push that a merge group tested runs none: the group tested the same commit. A push with no
		// merge-group run runs them all.
		Kubernetes:
			builtAtAll &&
			(entry.event === "push" ? entry.queueImages === "[]" : entry.filters.kubernetes === "true"),
		"Release-preflight": !fails && evidence,
	};
}

function run(entry: Case) {
	const github = {
		event_name: entry.event,
		repository: "hephaestus-build/Hephaestus",
		event: {
			action: "synchronize",
			pull_request:
				entry.event === "pull_request"
					? {
							draft: entry.skip === "draft" || entry.skip === "draft-preview",
							head: {
								sha: "head",
								repo: {
									full_name:
										entry.skip === "fork" ? "someone/Hephaestus" : "hephaestus-build/Hephaestus",
								},
							},
						}
					: undefined,
		},
	};
	const steps = {
		filter: { outputs: entry.filters },
		alias_base: { outputs: { commit: entry.aliasBase } },
		opt_in: { outputs: { preview: entry.preview, full: "false" } },
		queue_run: {
			outputs: {
				complete: String(entry.queueImages === ALL_IMAGES),
				images: entry.queueImages,
				"run-id": entry.queueImages === "[]" ? "" : "1",
			},
		},
		release_candidate: { outputs: { "release-candidate": entry.releaseCandidate } },
		version_bump: { outputs: { changed: entry.versionBump } },
		skip_check: { outputs: { should_skip: String(entry.skip === "should-skip") } },
	};
	const declared = cicd.getIn(["jobs", "detect-changes", "outputs"]);
	assert.ok(isMap(declared));
	const detection = dictionary({
		github,
		steps,
		inputs: { "release-preflight": entry.preflight === "true" },
	});
	const outputs = Object.fromEntries(
		declared.items
			.filter((item) => READ.has(String(item.key)))
			.map((item) => [
				String(item.key),
				evaluate(String(item.value), ["github", "steps", "inputs"], detection),
			]),
	);

	const needs: Record<string, { result: string; outputs?: Record<string, string> }> = {
		"detect-changes": { result: "success", outputs },
	};
	// A condition without a status function also needs every job it waits for to succeed.
	const job = (name: string) => {
		const condition = String(cicd.getIn(["jobs", name, "if"]));
		const waits = cicd.getIn(["jobs", name, "needs"]);
		assert.ok(isSeq(waits));
		const succeeded = waits.items.every((need) => {
			const result = needs[String(need)]?.result;
			assert.ok(
				result !== undefined,
				`${name} needs ${String(need)}, which this model does not run`,
			);
			return result === "success";
		});
		return (
			(/\b(?:always|cancelled|failure|success)\(/u.test(condition) || succeeded) &&
			evaluate(condition, ["needs", "github"], dictionary({ needs, github })) === "true"
		);
	};
	// A merge group's heavy legs wait for its Security scan, which passes in this model.
	needs["Security-merge-group"] = { result: job("Security-merge-group") ? "success" : "skipped" };
	let packageResult = "skipped";
	if (job("server-package")) {
		packageResult = entry.skip === "package-fails" ? "failure" : "success";
	}
	needs["server-package"] = { result: packageResult };
	needs["vulnerability-database"] = {
		result: job("vulnerability-database") ? "success" : "skipped",
	};
	const dockerRuns = job("Docker");

	const inputs: Record<string, string> = {};
	const declaredInputs = cicd.getIn(["jobs", "Docker", "with"]);
	assert.ok(isMap(declaredInputs));
	const caller = dictionary({ needs, github });
	for (const item of declaredInputs.items) {
		const value = String(item.value);
		inputs[String(item.key)] = /^\$\{\{.*\}\}$/su.test(value)
			? evaluate(value, ["needs", "github"], caller)
			: value;
	}
	const called = dictionary({ inputs, github });
	const reusable = (path: string[]) =>
		dockerRuns && evaluate(docker.getIn(path), ["inputs", "github"], called) === "true";
	const selects = reusable(["jobs", "images", "if"]);
	const aliases = reusable(["jobs", "tag-unchanged-images", "if"]);
	// reusable-docker-build.yml's inputs, typed as it declares them, and its build job's condition.
	const selected = Object.fromEntries(
		Object.keys(IMAGES).map((image) => [
			image,
			selects && reusable(["jobs", "images", "with", image]),
		]),
	);
	const reuseImages = String(inputs.reuse_images);
	const builds =
		evaluate(
			reusableBuild.getIn(["jobs", "build", "if"]),
			["inputs", "github"],
			dictionary({ inputs: { ...selected, "reuse-images": reuseImages }, github }),
		) === "true";
	const reuseList: unknown = JSON.parse(reuseImages);
	assert.ok(Array.isArray(reuseList));
	const reused = new Set<unknown>(reuseList);
	const fails = entry.skip === "docker-fails";
	// What the `images` job publishes, the one source of the digest output, and what the alias adds.
	// A failed run may still have published some of them before a later job failed.
	const published = Object.fromEntries(
		Object.keys(IMAGES).map((image) => [
			image,
			inputs.publish === "true" && selected[image] === true && (reused.has(image) || builds),
		]),
	);
	const aliased = Object.fromEntries(
		Object.entries(IMAGES).map(([image, changed]) => [
			image,
			!fails && aliases && inputs[changed] !== "true",
		]),
	);
	const present = Object.fromEntries(
		Object.keys(IMAGES).map((image) => [
			image,
			published[image] === true || aliased[image] === true,
		]),
	);

	let dockerResult = "skipped";
	if (dockerRuns) {
		dockerResult = fails ? "failure" : "success";
	}
	needs.Docker = { result: dockerResult };
	return {
		present,
		published,
		aliased,
		built: Object.fromEntries(
			Object.keys(IMAGES).map((image) => [image, published[image] === true && !reused.has(image)]),
		),
		packaged: needs["server-package"].result === "success",
		expected: needed(entry),
		consumers: Object.fromEntries(CONSUMERS.map((name) => [name, job(name)])),
	};
}

function describeCase(entry: Case): string {
	return `${entry.event} changing [${entry.paths.join(", ")}], alias base '${entry.aliasBase}', preview '${entry.preview}', release candidate '${entry.releaseCandidate}', queue images ${entry.queueImages}, version bump '${entry.versionBump}', preflight '${entry.preflight}', skip '${entry.skip}'`;
}

void test("the step ids this model sets are the workflow's", () => {
	const steps = cicd.getIn(["jobs", "detect-changes", "steps"]);
	assert.ok(isSeq(steps));
	const ids = new Set(steps.items.map((item) => (isMap(item) ? String(item.get("id")) : "")));
	for (const id of [
		"filter",
		"alias_base",
		"opt_in",
		"queue_run",
		"release_candidate",
		"version_bump",
		"skip_check",
	]) {
		assert.ok(ids.has(id), `detect-changes has no step ${id}`);
	}
});

void test("a job that installs every image runs exactly when its event needs it and the run has put every image there", () => {
	const reached = new Set<string>();
	for (const entry of cases()) {
		const { present, published, aliased, built, packaged, expected, consumers } = run(entry);
		for (const image of Object.keys(IMAGES)) {
			// An alias would overwrite the tag of the image this run built.
			assert.ok(
				!(published[image] === true && aliased[image] === true),
				`${image} is both built and aliased on ${describeCase(entry)}`,
			);
		}
		assert.ok(
			built["application-server"] !== true || packaged,
			`the App Server image is built without a packaged server on ${describeCase(entry)}`,
		);
		// A version bump runs the release checks on the push, and they need the packaged server.
		assert.ok(
			entry.event !== "push" || entry.versionBump !== "true" || entry.skip !== "" || packaged,
			`a version-bump push does not package the server: ${describeCase(entry)}`,
		);
		for (const [consumer, runs] of Object.entries(consumers)) {
			if (expected[consumer] !== undefined) {
				assert.equal(
					runs,
					expected[consumer],
					`${consumer} ${runs ? "runs" : "does not run"} on ${describeCase(entry)}`,
				);
			}
			if (!runs) {
				continue;
			}
			reached.add(`${consumer} on ${entry.event}`);
			const missing = Object.entries(present)
				.filter(([, image]) => !image)
				.map(([image]) => image);
			assert.deepEqual(
				missing,
				[],
				`${consumer} runs on ${describeCase(entry)}, but nothing puts ${missing.join(", ")} under the commit`,
			);
		}
	}
	// Each consumer is reached on every event it serves, or the test proves nothing about it.
	assert.deepEqual([...reached].toSorted(), [
		"Kubernetes on merge_group",
		"Kubernetes on pull_request",
		"Kubernetes on push",
		"Kubernetes on workflow_dispatch",
		"Release-preflight on merge_group",
		"Release-preflight on pull_request",
		"Release-preflight on workflow_dispatch",
	]);
});
