import assert from "node:assert/strict";
import { chmod, mkdtemp, readdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";
import { parse as parseJsonc } from "jsonc-parser";
import { isMap, isScalar, isSeq } from "yaml";
import {
	buildStageCopySources,
	filesReadByConfig,
	job,
	namedStep,
	parseDocument,
	pathFilter,
	posixGlob,
	runnerStepsOnly,
	runScript,
	runStep,
	step,
	workflowSources,
} from "./ci-contract-support.ts";
import { ciToolPin } from "./lib/ci-tool-catalog.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";

void describe("ci-contract: images", () => {
	void test("every Dockerfile build refreshes OS packages through its exported runtime", async () => {
		const reusable = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		const build = namedStep(reusable, ["jobs", "build"], "Build and push the Dockerfile images");
		assert.equal(String(build.getIn(["with", "files"])).split("\n")[0], "./docker/docker-bake.hcl");
		// Only trusted runs write the shared cache: main, and the merge group whose images main reuses.
		assert.equal(
			build.getIn(["env", "WRITE_CACHE"]),
			`\${{ github.ref == 'refs/heads/main' || github.event_name == 'merge_group' }}`,
		);
		const bake = await readFile("docker/docker-bake.hcl", "utf8");
		assert.match(
			bake,
			/target "image" \{\n(?:\s*#.*\n)*\s*no-cache-filter = \["os-packages", "runtime"\]/u,
		);
		const targets = [...bake.matchAll(/^target "(?<name>[a-z-]+)" \{\n(?<body>(?:.*\n)*?)\}/gmu)]
			.map(({ groups }) => ({ name: groups?.name ?? "", body: groups?.body ?? "" }))
			.filter(({ name }) => name !== "image");
		assert.deepEqual(
			targets.map(({ name }) => name),
			["webapp", "agent-pi", "postgres"],
		);
		const dockerfiles = new Set(await posixGlob("{docker,webapp}/**/Dockerfile"));
		assert.ok(dockerfiles.size > 0);
		for (const { name, body } of targets) {
			assert.match(body, new RegExp(`inherits\\s*= \\["image", "${name}-metadata"\\]`, "u"));
			assert.ok(
				body.includes(
					`cache-from = ["type=registry,ref=\${repository("${name}")}:cache-main-\${PLATFORM_PAIR}"]`,
				),
				name,
			);
			assert.ok(
				body.includes(
					`cache-to   = WRITE_CACHE == "true" ? ["type=registry,ref=\${repository("${name}")}:cache-main-\${PLATFORM_PAIR},mode=max"] : []`,
				),
				name,
			);
			const context = /context\s*= "(?<path>[^"]+)"/u.exec(body)?.groups?.path;
			const dockerfilePath = /dockerfile = "(?<path>[^"]+)"/u.exec(body)?.groups?.path;
			assert.ok(context !== undefined && dockerfilePath !== undefined, name);
			const file = path.posix.join(context, dockerfilePath);
			dockerfiles.delete(file);
			const dockerfile = await readFile(file, "utf8");
			const stages = dockerfile.split(/^FROM /mu);
			const packages = stages.find((stage) => /^.* AS os-packages$/mu.test(stage));
			assert.ok(packages !== undefined, `${file} must have the stage named by no-cache-filter`);
			assert.match(stages.at(-1) ?? "", /^os-packages AS runtime$/mu);
			assert.match(packages, /^RUN .*?(?:apt-get|apk)/msu);
			assert.doesNotMatch(packages, /^COPY |^ARG SOURCE_COMMIT/mu);
			if (packages.includes("apt-get")) {
				assert.match(packages, /apt-get update -o APT::Update::Error-Mode=any/u);
			}
			for (const stage of stages.filter((candidate) => candidate !== packages)) {
				assert.doesNotMatch(stage, /\b(?:apt-get|apk) (?:update|upgrade|install|add)\b/u);
			}
		}
		assert.deepEqual(
			[...dockerfiles],
			[],
			"every Dockerfile must be a target of docker/docker-bake.hcl",
		);
		// The bake file decides how every Dockerfile image builds, so a change to it rebuilds them all.
		const orchestrator = await readFile(".github/workflows/cicd.yml", "utf8");
		assert.match(
			pathFilter(orchestrator, "docker-config"),
			/^ {14}- 'docker\/docker-bake\.hcl'$/mu,
		);
		for (const input of ["webapp_changed", "agent_images_changed", "postgres_image_changed"]) {
			assert.match(
				job(orchestrator, "Docker"),
				new RegExp(`${input}: .*docker-config == 'true'`, "u"),
			);
		}
	});

	void test("the webapp image copies every file its Vite config reads", async () => {
		const reads = await filesReadByConfig("webapp/vite.config.ts");
		assert.ok(
			[...reads].some((file) => !file.startsWith("webapp/")),
			"Expected the Vite config to read configuration from outside webapp/",
		);
		const sources = buildStageCopySources(await readFile("webapp/Dockerfile", "utf8"));
		for (const file of reads) {
			assert.ok(
				sources.some((source) =>
					source.endsWith("/") ? file.startsWith(source) : file === source,
				),
				`webapp/Dockerfile's build stage does not copy ${file}, which webapp/vite.config.ts reads`,
			);
		}
	});

	void test("the webapp image copies every workspace manifest before installing dependencies", async () => {
		const workspace = parseDocument(await readFile("pnpm-workspace.yaml", "utf8"));
		const packages = workspace.get("packages");
		assert.ok(isSeq(packages));
		const dockerfile = await readFile("webapp/Dockerfile", "utf8");
		const fetch = dockerfile.indexOf("pnpm fetch --filter webapp");
		assert.ok(fetch > 0);
		const sources = buildStageCopySources(dockerfile.slice(0, fetch));
		for (const item of packages.items) {
			const directory = asString(isScalar(item) ? item.value : item, "workspace package");
			const manifest = path.posix.join(directory, "package.json");
			assert.ok(sources.includes(manifest), `The install layer must copy ${manifest}`);
		}
	});

	void test("the webapp image copies the catalogue imported by preview fixtures", async () => {
		const config = asRecord(parseJsonc(await readFile("webapp/tsconfig.json", "utf8")), "tsconfig");
		const options = asRecord(config.compilerOptions, "compiler options");
		const paths = asRecord(options.paths, "paths");
		const resources = asArray(paths["@bundled-practices"], "bundled practices");
		const sources = buildStageCopySources(await readFile("webapp/Dockerfile", "utf8"));
		for (const resource of resources) {
			const file = path.posix.join("webapp", asString(resource, "catalogue path"));
			assert.ok(
				sources.some((source) =>
					source.endsWith("/") ? file.startsWith(source) : file === source,
				),
				`The webapp image must contain ${file} for type-aware lint`,
			);
		}
	});

	void test("path exclusions cannot select unrelated files for server tests or webapp images", async () => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const steps = workflow.getIn(["jobs", "detect-changes", "steps"]);
		assert.ok(isSeq(steps));
		const filters = steps.items.filter(
			(item) => isMap(item) && String(item.get("uses")).startsWith("dorny/paths-filter@"),
		);
		assert.equal(filters.length, 1);
		const filter = filters[0];
		assert.ok(isMap(filter));
		assert.equal(filter.getIn(["with", "predicate-quantifier"]), "some-with-excludes");
	});

	void test("buildpack reporting changes exercise the image pipeline", async () => {
		const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		const filter = step(workflow, ["jobs", "detect-changes"], "dorny/paths-filter");
		const filters = asRecord(parseDocument(String(filter.get("filters"))).toJSON(), "CI filters");
		assert.ok(
			asArray(filters["application-server-image"], "image paths").includes(
				"scripts/summarize-buildpack-log.ts",
			),
		);
	});

	void test("a failed image build still lets every other image build and report, but hands over nothing", async () => {
		const workflow = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		const build = ["jobs", "build"];
		for (const name of [
			"Resolve the pinned pack",
			"Set up pack",
			"Resolve the buildpack dependencies",
			"Download the executable JAR",
			"Build with Buildpacks and CDS",
			"Export digests",
			"Enforce the release vulnerability policy",
		]) {
			assert.match(
				String(namedStep(workflow, build, name).get("if")),
				/^\$\{\{ !cancelled\(\) && steps\.prep\.outcome == 'success' && /u,
				name,
			);
		}
		// The export reads each image's digest, never the whole bake metadata: its provenance records
		// exceed what a process environment can hold.
		const exported = namedStep(workflow, build, "Export digests");
		assert.equal(exported.hasIn(["env", "BAKE_METADATA"]), false);
		for (const [variable, target] of [
			["WEBAPP_DIGEST", ".webapp"],
			["AGENT_PI_DIGEST", "['agent-pi']"],
			["POSTGRES_DIGEST", ".postgres"],
		] as const) {
			assert.equal(
				exported.getIn(["env", variable]),
				`\${{ steps.bake.outputs.metadata && fromJSON(steps.bake.outputs.metadata)${target}['containerimage.digest'] || '' }}`,
			);
		}
		// The handover runs only when every step succeeded, and the manifest job needs the build.
		assert.equal(namedStep(workflow, build, "Upload digests").get("if"), "inputs.publish");
		const caller = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		const dependencies = caller.getIn(["jobs", "merge", "needs"]);
		assert.ok(isSeq(dependencies));
		assert.deepEqual(dependencies.toJSON(), ["build", "build-arm64"]);
	});

	void test("provides dependency patches before image installs and validates extension patch changes", async () => {
		const dockerfile = await readFile("webapp/Dockerfile", "utf8");
		const workspace = parseDocument(await readFile("pnpm-workspace.yaml", "utf8"));
		const patched = workspace.get("patchedDependencies");
		assert.ok(isMap(patched) && patched.items.length > 0, "The workspace configures patches");
		// The filtered install still reads every configured patch.
		for (const command of ["pnpm fetch --filter webapp", "pnpm install --offline"]) {
			const at = dockerfile.indexOf(command);
			assert.ok(at !== -1, `webapp/Dockerfile runs ${command}`);
			const sources = buildStageCopySources(dockerfile.slice(0, at));
			for (const { value } of patched.items) {
				const file = isScalar(value) ? value.value : undefined;
				assert.ok(typeof file === "string", "A patched dependency names its patch file");
				assert.ok(
					sources.some((source) =>
						source.endsWith("/") ? file.startsWith(source) : file === source,
					),
					`${command} requires ${file} first`,
				);
			}
		}
		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		const detection = job(source, "detect-changes");
		for (const filter of ["extension", "extension-e2e"]) {
			assert.match(pathFilter(detection, filter), /- 'patches\/\*\*'/u);
		}
	});

	void test("decides an image's architectures once, for every image the run builds", async () => {
		const source = await readFile(".github/workflows/cicd.yml", "utf8");

		const docker = await readFile(".github/workflows/reusable-docker-build.yml", "utf8");
		// The platform list is one decision, taken in cicd.yml and handed to every image build, so
		// a run cannot evidence one image on both platforms and its sibling on one. The test below
		// owns what that decision is; this owns that nothing decides it locally.
		const images = job(docker, "build");
		assert.match(images, /contains\(fromJSON\(inputs\.platforms\), 'linux\/amd64'\)/u);
		assert.equal(parseDocument(images).getIn(["build", "strategy"]), undefined);
		// Required, and with no default: a caller that forgets it fails to start, rather than
		// silently publishing one architecture where a release needs two.
		assert.match(docker, /^ {6}platforms:\n(?: {8}.*\n)*? {8}required: true$/mu);
		assert.match(
			job(source, "Docker"),
			/platforms: \$\{\{ needs\.detect-changes\.outputs\.platforms \}\}/u,
		);
		for (const text of [source, docker]) {
			assert.doesNotMatch(text, /single[-_]arch/u);
		}
		const inherited = job(docker, "tag-unchanged-images");
		assert.match(inherited, /HEAD_SHA/u);
		assert.match(inherited, /pr-\$PR_NUMBER/u);

		const reusable = await readFile(".github/workflows/reusable-docker-build.yml", "utf8");
		// The webapp image uploads no source maps anywhere, so no build is handed an upload credential.
		const webappImage = await readFile("webapp/Dockerfile", "utf8");
		for (const text of [source, docker, reusable, webappImage]) {
			assert.doesNotMatch(text, /sentry/iu);
		}
		assert.doesNotMatch(reusable, /single[-_]arch/u);
		const parsed = parseDocument(reusable);
		const declaration = parsed.getIn(["on", "workflow_call", "inputs", "platforms"]);
		assert.ok(isMap(declaration));
		assert.equal(declaration.get("required"), true);
		assert.equal(declaration.get("default"), undefined);
		// One code path for every platform list: the build matrix, which scans, and the merge read it.
		for (const [key, platform] of [
			["build", "linux/amd64"],
			["build-arm64", "linux/arm64"],
		] as const) {
			assert.match(
				String(parsed.getIn(["jobs", key, "if"])),
				new RegExp(`contains\\(fromJSON\\(inputs\\.platforms\\), '${platform}'\\)`, "u"),
			);
		}

		assert.equal(
			namedStep(parsed, ["jobs", "merge"], "Create manifests and push").getIn(["env", "PLATFORMS"]),
			`\${{ inputs.platforms }}`,
		);
		// Each platform builds natively on its own runner; no emulation.
		assert.equal(parsed.getIn(["jobs", "build", "runs-on"]), "ubuntu-24.04");
		assert.doesNotMatch(reusable, /qemu/iu);
	});

	void test("builds fork pull-request images without registry writes", async () => {
		const orchestrator = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		// One decision, taken where the run can see whose repository the head branch is on. A fork's
		// GITHUB_TOKEN is read-only, so a publishing build there fails on its first write.
		assert.match(
			String(orchestrator.getIn(["jobs", "detect-changes", "outputs", "publishable"])),
			/^\$\{\{ github\.event_name != 'pull_request' \|\| github\.event\.pull_request\.head\.repo\.full_name == github\.repository \}\}$/u,
		);
		assert.match(
			String(orchestrator.getIn(["jobs", "Docker", "with", "publish"])),
			/^\$\{\{ needs\.detect-changes\.outputs\.publishable == 'true' \}\}$/u,
		);

		const reusable = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		const declaration = reusable.getIn(["on", "workflow_call", "inputs", "publish"]);
		assert.ok(isMap(declaration));
		assert.equal(declaration.get("type"), "boolean");
		// No default: a caller that forgets it fails to start, rather than silently writing to the
		// registry on behalf of a run that may not be allowed to.
		assert.equal(declaration.get("required"), true);
		assert.equal(declaration.get("default"), undefined);

		const build = ["jobs", "build"];
		for (const name of ["Log in to Container Registry", "Export digests", "Upload digests"]) {
			assert.match(
				String(namedStep(reusable, build, name).get("if")),
				/inputs\.publish/u,
				`${name} writes to the registry and must be gated on publish`,
			);
		}
		// BuildKit builds the Dockerfile images in a container; Buildpacks use the daemon directly.
		assert.equal(
			namedStep(reusable, build, "Set up Docker Buildx").getIn(["with", "driver"]),
			"docker-container",
		);
		assert.equal(
			namedStep(reusable, ["jobs", "merge"], "Set up Docker Buildx").getIn(["with", "driver"]),
			"docker",
		);
		const bake = namedStep(reusable, build, "Build and push the Dockerfile images");
		const inputs = bake.get("with");
		assert.ok(isMap(inputs));
		// An untagged local image is one nothing can name, so a fork loads nothing into the daemon.
		for (const input of ["push", "load", "tags"]) {
			assert.equal(inputs.has(input), false, input);
		}
		assert.equal(bake.getIn(["env", "PUBLISH"]), `\${{ inputs.publish }}`);
		// Without publication each target exports to the build cache only.
		const definition = await readFile("docker/docker-bake.hcl", "utf8");
		assert.equal((definition.match(/: \["type=cacheonly"\]/gu) ?? []).length, 3);
		assert.match(String(reusable.getIn(["jobs", "merge", "if"])), /inputs\.publish/u);
		assert.equal(
			namedStep(reusable, build, "Enforce the release vulnerability policy").get("if"),
			`\${{ !cancelled() && steps.prep.outcome == 'success' && inputs.publish && inputs.scan-images }}`,
		);

		const docker = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		assert.match(String(docker.getIn(["jobs", "tag-unchanged-images", "if"])), /inputs\.publish/u);
	});

	void test(
		"a fork's buildpack build produces an image without contacting the registry",
		runnerStepsOnly,
		async () => {
			const reusable = parseDocument(
				await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
			);
			const shell = runScript(reusable, ["jobs", "build"], "Build with Buildpacks and CDS");
			const directory = await mkdtemp(path.join(tmpdir(), "buildpacks-"));
			await writeFile(path.join(directory, "hephaestus-application-1.0.0.jar"), "");
			const calls = path.join(directory, "calls");
			for (const tool of ["pack", "docker"]) {
				const stub = path.join(directory, tool);
				await writeFile(
					stub,
					`#!/bin/sh\necho "${tool} $*" >> "${calls}"\n[ "$1" = image ] && echo sha256:stub\nexit 0\n`,
				);
				await chmod(stub, 0o755);
			}
			const environment = {
				APPLICATION_DIRECTORY: directory,
				BUILDPACK_DEPENDENCIES: path.join(directory, "buildpack-dependencies"),
				RUNNER_TEMP: directory,
				GITHUB_RUN_ID: "1",
				INPUT_IMAGE_NAME: "hephaestus-build/application-server",
				INPUT_REGISTRY: "ghcr.io",
				MATRIX_PLATFORM: "linux/amd64",
				PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
				PLATFORM_PAIR: "linux-amd64",
				PROJECT_DESCRIPTOR: "project.toml",
				RUN_IMAGE: "paketobuildpacks/run",
			};

			const local = await runStep(shell, { ...environment, PUBLISH: "false" });
			assert.equal(local.failed, false, local.diagnosis);
			// Nothing downstream of a build without registry writes reads a digest, so it reports none.
			assert.equal(local.outputs.digest, undefined);
			const invoked = await readFile(calls, "utf8");
			assert.doesNotMatch(invoked, /--publish/u);
			assert.doesNotMatch(invoked, /^docker /mu);
			assert.match(invoked, /^pack build .*--trust-builder/mu);
			assert.match(
				invoked,
				/--volume \S+\/buildpack-dependencies\/bindings:\/platform\/bindings:ro/u,
			);
			// A reporting pipe must never turn a failed archive build into a successful image.
			await writeFile(
				path.join(directory, "pack"),
				"#!/bin/sh\necho archive failed >&2\nexit 23\n",
			);
			const failedBuild = await runStep(shell, { ...environment, PUBLISH: "false" });
			assert.equal(failedBuild.failed, true);
			assert.match(
				await readFile(path.join(directory, "buildpacks.log"), "utf8"),
				/archive failed/u,
			);
		},
	);

	void test("every image a consumer resolves is one the build published for that event", async () => {
		const reusable = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		const declared = reusable.getIn(["env", "STANDARD_IMAGE_TAGS"]);
		assert.equal(typeof declared, "string");
		// Native metadata-action entries keep the event guard alongside the value. A tag names
		// the artifact, not a run attempt that a re-run could never resolve.
		const guard =
			/^type=raw,value=\$\{\{ (?<value>.+?) \}\},enable=\$\{\{ github\.event_name (?<operator>==|!=) '(?<event>\w+)' \}\}$/u;
		const lines = String(declared)
			.split("\n")
			.filter((line) => line.length > 0);
		const publishedOn = (event: string): string[] =>
			lines.flatMap((line) => {
				const parsed = guard.exec(line);
				assert.ok(parsed, `image tag "${line}" is published under every event`);
				const { value, operator, event: guarded } = parsed.groups ?? {};
				return (operator === "==") === (guarded === event) ? [String(value)] : [];
			});

		assert.deepEqual(publishedOn("push"), [
			"github.ref_name",
			"format('ci-{0}', github.run_number)",
			"github.sha",
		]);
		assert.deepEqual(publishedOn("pull_request"), [
			"github.event.pull_request.head.sha",
			"format('pr-{0}', github.event.number)",
		]);
		for (const event of ["merge_group", "workflow_dispatch"]) {
			assert.deepEqual(publishedOn(event), ["github.sha"]);
		}
		// Only the merge job tags: a platform build pushes by digest alone, and its metadata reads the
		// commit only for the version label, which a merge group and the push that reuses it share.
		assert.equal(
			step(reusable, ["jobs", "merge"], "docker/metadata-action").get("tags"),
			`\${{ env.STANDARD_IMAGE_TAGS }}`,
		);
		assert.equal(
			step(reusable, ["jobs", "build"], "docker/metadata-action").get("tags"),
			`type=raw,value=\${{ github.event.pull_request.head.sha || github.sha }}`,
		);
		assert.doesNotMatch(String(reusable), /steps\.tags\.outputs|Prepare tag configuration/u);

		const source = await readFile(".github/workflows/cicd.yml", "utf8");
		const triggers = /^on:\n(?<body>[\s\S]*?)^\S/mu.exec(source)?.groups?.body ?? "";
		const events = [...triggers.matchAll(/^ {2}(?<event>\w+):/gmu)].map(({ groups }) =>
			String(groups?.event),
		);
		assert.ok(events.includes("workflow_dispatch") && events.includes("merge_group"));
		for (const event of events) {
			// A pull request resolves its own head: `github.sha` there is a merge commit no image
			// carries. Every other event resolves the commit it ran on.
			const resolved =
				event === "pull_request" ? "github.event.pull_request.head.sha" : "github.sha";
			assert.ok(
				publishedOn(event).includes(resolved),
				`a ${event} run resolves images by ${resolved}, which the image build does not publish`,
			);
		}

		// The consumers, and the commit each resolves through. The scan resolves a digest its own
		// producer job emitted, so it needs no tag at all.
		const preflight = job(source, "Release-preflight");
		assert.match(
			preflight,
			/COMMIT: \$\{\{ github\.event\.pull_request\.head\.sha \|\| github\.sha \}\}/u,
		);
		const preflightJob = parseDocument(source).getIn(["jobs", "Release-preflight"]);
		assert.ok(isMap(preflightJob));
		const steps = asArray(
			asRecord(preflightJob.toJSON(), "preflight job").steps,
			"preflight steps",
		).map((entry) => asRecord(entry, "step"));
		const checkout = steps.find(
			(entry) => typeof entry.uses === "string" && entry.uses.startsWith("actions/checkout@"),
		);
		const generate = steps.find(
			(entry) =>
				typeof entry.run === "string" && entry.run.includes("generate-release-evidence.ts"),
		);
		assert.ok(checkout && generate);
		assert.equal(
			asRecord(checkout.with, "checkout inputs").ref,
			asRecord(generate.env, "generation env").COMMIT,
		);
		assert.match(preflight, /resolve-release-images\.ts "\$COMMIT"/u);
		assert.match(preflight, /--commit "\$COMMIT"/u);
		const cluster = job(source, "Kubernetes");
		assert.match(
			cluster,
			/commit: \$\{\{ github\.event\.pull_request\.head\.sha \|\| github\.sha \}\}/u,
		);
		// `workflow_run.head_sha` is the producing push run's own `github.sha`.
		const release = await readFile(".github/workflows/release.yml", "utf8");
		assert.match(
			job(release, "tag-images"),
			/SOURCE_TAG: \$\{\{ needs\.release\.outputs\.sha \}\}/u,
		);

		for (const [file, workflow] of await workflowSources()) {
			assert.doesNotMatch(
				workflow,
				/:run-\$/u,
				`${file} must not resolve an image by the run that happened to build it`,
			);
		}
	});
});

void test("the image build installs pack from its pin through the CI tool resolver", async () => {
	const pack = asRecord(
		JSON.parse(await readFile(".github/ci-tools/pack.json", "utf8")),
		"pack pin",
	);
	const pin = await ciToolPin("pack", "linux-x64");
	assert.equal(
		pin.url,
		`https://github.com/buildpacks/pack/releases/download/v${pin.version}/${pin.name}`,
	);
	const assets = asRecord(pack.assets, "pack assets");
	assert.deepEqual(Object.keys(assets).toSorted(), ["linux-arm64", "linux-x64"]);
	for (const [platform, suffix] of [
		["linux-x64", "linux"],
		["linux-arm64", "linux-arm64"],
	] as const) {
		const asset = await ciToolPin("pack", platform);
		assert.equal(asset.name, `pack-v${pin.version}-${suffix}.tgz`);
	}
	const workflow = parseDocument(
		await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
	);
	const build = ["jobs", "build"];
	const resolve = namedStep(workflow, build, "Resolve the pinned pack");
	assert.equal(resolve.get("uses"), "./.github/actions/setup-ci-tool");
	assert.equal(resolve.getIn(["with", "tool"]), "pack");
	const steps = workflow.getIn([...build, "steps"]);
	assert.ok(isSeq(steps));
	// The resolver is a Node script, so the pinned Node.js comes first.
	const toolchain = steps.items.findIndex(
		(item) => isMap(item) && item.get("uses") === "./.github/actions/setup-toolchain",
	);
	assert.ok(toolchain !== -1 && toolchain < steps.items.indexOf(resolve));
	const script = runScript(workflow, build, "Set up pack");
	assert.equal(
		namedStep(workflow, build, "Set up pack").getIn(["env", "PACK_VERSION"]),
		`\${{ steps.pack.outputs.version }}`,
	);
	const fetched = script.indexOf('CI_TOOL=pack node scripts/ci-tool.ts file "$work/pack.tgz"');
	assert.ok(fetched > 0, "pack must come from the resolver");
	assert.ok(script.indexOf('tar -xzf "$work/pack.tgz" -C "$install_dir" pack') > fetched);
	assert.match(script, /"\$PACK_VERSION"\+\*\) ;;/u);
	for (const file of await posixGlob(".github/{actions,workflows}/**/*.{yml,yaml}")) {
		assert.doesNotMatch(
			await readFile(file, "utf8"),
			/buildpacks\/github-actions|buildpacks\/pack\/releases/u,
			file,
		);
	}
});

void test("the App Server image build reads every buildpack dependency from a pinned binding", async () => {
	const pins = await readdir(".github/ci-tools");
	const ids = pins
		.filter((name) => name.startsWith("buildpack-"))
		.map((name) => name.slice(0, -".json".length));
	assert.deepEqual(ids.toSorted(), [
		"buildpack-liberica-jre",
		"buildpack-spring-cloud-bindings",
		"buildpack-syft",
		"buildpack-tiny-health-checker",
	]);
	// Each image platform has its own file, or one file serves both.
	for (const id of ids) {
		for (const platform of ["linux-x64", "linux-arm64"]) {
			await ciToolPin(id, platform);
		}
	}
	const workflow = parseDocument(
		await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
	);
	const build = ["jobs", "build"];
	const steps = workflow.getIn([...build, "steps"]);
	assert.ok(isSeq(steps));
	const resolve = namedStep(workflow, build, "Resolve the buildpack dependencies");
	assert.equal(resolve.get("id"), "buildpack-dependencies");
	assert.equal(resolve.get("run"), "node scripts/buildpack-dependencies.ts");
	const packBuild = namedStep(workflow, build, "Build with Buildpacks and CDS");
	assert.ok(steps.items.indexOf(resolve) < steps.items.indexOf(packBuild));
	assert.equal(
		packBuild.getIn(["env", "BUILDPACK_DEPENDENCIES"]),
		`\${{ steps.buildpack-dependencies.outputs.directory }}`,
	);
	const script = runScript(workflow, build, "Build with Buildpacks and CDS");
	assert.ok(
		script.includes('--volume "$BUILDPACK_DEPENDENCIES/files:$BUILDPACK_DEPENDENCIES/files:ro"'),
		"the files mount at the host path that the binding names",
	);
});
