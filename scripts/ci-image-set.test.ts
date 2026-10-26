import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { Minimatch } from "minimatch";
import { isMap, isSeq, parseDocument } from "yaml";

// The Kubernetes legs and the host boot smoke install every image under the run's commit. Two jobs
// put an image there, by building it or by aliasing it, under conditions that differ per event. A
// combination where neither runs fails only in the run that meets it, often a later pull request's
// merge group, so this test evaluates the workflow's own conditions for every combination instead.

const cicd = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
const docker = parseDocument(await readFile(".github/workflows/ci-docker-build.yml", "utf8"));

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

interface Case {
	event: string;
	paths: string[];
	filters: Record<string, string>;
	aliasBase: string;
	preview: string;
	queueComplete: string;
	releaseCandidate: string;
}

// Only a pull request has a `preview` label, a push resolves no alias base but reuses the merge
// group's images, and only a push asks whether that merge group published every image.
// A release candidate is the Version PR's branch or a merge group that bumps the version; it builds
// every image itself, so its alias base does not matter, and its diff holds the version files.
const SETTINGS = {
	pull_request: {
		aliasBase: ["", "0123abc"],
		preview: ["", "true", "false"],
		queueComplete: [""],
		releaseCandidate: ["false", "true"],
	},
	merge_group: {
		aliasBase: ["", "0123abc"],
		preview: [""],
		queueComplete: [""],
		releaseCandidate: ["false", "true"],
	},
	push: {
		aliasBase: [""],
		preview: [""],
		queueComplete: ["true", "false"],
		releaseCandidate: ["false"],
	},
};

function* cases(): Generator<Case> {
	const changed = changes().map((paths) => ({ paths, filters: filterOutputs(paths) }));
	const released = changed.map(({ paths }) => {
		const withVersion = [...new Set([...paths, ...VERSION_FILES])];
		return { paths: withVersion, filters: filterOutputs(withVersion) };
	});
	for (const [event, options] of Object.entries(SETTINGS)) {
		const settings = options.releaseCandidate.flatMap((releaseCandidate) =>
			(releaseCandidate === "true" ? [""] : options.aliasBase).flatMap((aliasBase) =>
				options.preview.flatMap((preview) =>
					options.queueComplete.map((queueComplete) => ({
						aliasBase,
						preview,
						queueComplete,
						releaseCandidate,
					})),
				),
			),
		);
		for (const setting of settings) {
			for (const change of setting.releaseCandidate === "true" ? released : changed) {
				yield { event, ...change, ...setting };
			}
		}
	}
}

const CONSUMERS = ["Kubernetes", "Supported-host-smoke", "Release-preflight"];

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

function run(entry: Case) {
	const github = {
		event_name: entry.event,
		repository: "hephaestus-build/Hephaestus",
		event: {
			action: "synchronize",
			pull_request:
				entry.event === "pull_request"
					? {
							draft: false,
							head: { sha: "head", repo: { full_name: "hephaestus-build/Hephaestus" } },
						}
					: undefined,
		},
	};
	const steps = {
		filter: { outputs: entry.filters },
		alias_base: { outputs: { commit: entry.aliasBase } },
		opt_in: { outputs: { preview: entry.preview, full: "false" } },
		queue_run: { outputs: { complete: entry.queueComplete, images: "[]", "run-id": "" } },
		release_candidate: { outputs: { "release-candidate": entry.releaseCandidate } },
		version_bump: { outputs: { changed: "false" } },
	};
	const declared = cicd.getIn(["jobs", "detect-changes", "outputs"]);
	assert.ok(isMap(declared));
	const detection = dictionary({ github, steps, inputs: {} });
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
	const job = (name: string) =>
		evaluate(
			cicd.getIn(["jobs", name, "if"]),
			["needs", "github"],
			dictionary({ needs, github }),
		) === "true";
	needs["server-package"] = { result: job("server-package") ? "success" : "skipped" };
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
	const buildsAny = reusable(["jobs", "images", "if"]);
	const aliases = reusable(["jobs", "tag-unchanged-images", "if"]);
	const present = Object.fromEntries(
		Object.entries(IMAGES).map(([image, changed]) => {
			const built = buildsAny && reusable(["jobs", "images", "with", image]);
			const aliased = aliases && inputs[changed] !== "true";
			return [image, built || aliased];
		}),
	);

	needs.Docker = {
		result: dockerRuns ? "success" : "skipped",
		outputs: {
			"application-server-digest": present["application-server"] === true ? "sha256:digest" : "",
		},
	};
	return {
		present,
		consumers: Object.fromEntries(CONSUMERS.map((name) => [name, job(name)])),
	};
}

void test("a job that installs every image of the run runs only when the run has put every image there", () => {
	const reached = new Set<string>();
	for (const entry of cases()) {
		const { present, consumers } = run(entry);
		for (const [consumer, runs] of Object.entries(consumers)) {
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
				`${consumer} runs on ${entry.event} changing [${entry.paths.join(", ")}], alias base '${entry.aliasBase}', preview '${entry.preview}', queue complete '${entry.queueComplete}', release candidate '${entry.releaseCandidate}', but nothing puts ${missing.join(", ")} under the commit`,
			);
		}
	}
	// Each consumer is reached on every event it serves, or the test proves nothing about it.
	assert.deepEqual([...reached].toSorted(), [
		"Kubernetes on merge_group",
		"Kubernetes on pull_request",
		"Kubernetes on push",
		"Release-preflight on merge_group",
		"Release-preflight on pull_request",
		"Supported-host-smoke on pull_request",
	]);
});
