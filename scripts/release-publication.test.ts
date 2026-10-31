import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
	copyFile,
	mkdir,
	mkdtemp,
	readFile,
	readdir,
	rm,
	symlink,
	writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

import { parseInventory } from "./commit-image-lock.ts";
import { githubReleaseBody } from "./github-release-notes.ts";
import { chartValues, releaseValuesLines } from "./kubernetes-chart.ts";
import { asRecord, parseJson } from "./lib/json.ts";
import { lockFromEvidence } from "./release-image-lock.ts";
import { verifyReleaseEvidence } from "./verify-release-evidence.ts";

const release = "v0.93.0";
const commit = "c".repeat(40);
const repository = "ghcr.io/hephaestus-build/webapp";
const indexDigest = `sha256:${"a".repeat(64)}`;
const chart = {
	repository: "ghcr.io/hephaestus-build/charts/hephaestus",
	version: release.slice(1),
	digest: `sha256:${"b".repeat(64)}`,
};
const chartPackage = `hephaestus-${chart.version}.tgz`;
const defaultChart = chart;
/**
 * The other images that the chart runs, as upstream pins: their evidence needs no attestation and
 * no alias, so webapp stays the one first-party image that this suite promotes.
 */
const upstream = ["agent-pi", "application-server", "nats", "postgres"].map((name, index) => ({
	name,
	repository: `docker.io/library/${name}`,
	tag: "1",
	digest: `sha256:${"de34".charAt(index).repeat(64)}`,
}));
/** The release image inventory of the fixture checkout and its evidence. */
const inventory = { schemaVersion: 1, images: ["webapp"], upstream };
const jointLock = `release-${release}.chart.json`;
/** The k3s platform assets, in the job's workspace as release.yml collects them. */
const platformAssets = ["k3s-platform.yaml", "k3s-values.yaml"];
/** A synthetic Cosign bundle: a JSON object naming the signed bytes. It proves nothing about Sigstore. */
const bundle = (bytes: string | Uint8Array) => JSON.stringify({ sha256: hash(bytes) });
const sign = async (file: string) =>
	writeFile(`${file}.sigstore.json`, bundle(await readFile(file)));
const platforms = ["amd64", "arm64"];
const root = path.resolve(import.meta.dirname, "..");
const hash = (bytes: string | Uint8Array) => createHash("sha256").update(bytes).digest("hex");

const workflow = parseDocument(await readFile(".github/workflows/release.yml", "utf8"));
/** The named steps of a release job, each its own script, as Actions runs them. */
const jobSteps = (job: string, names: readonly string[]) => {
	const steps = workflow.getIn(["jobs", job, "steps"]);
	assert.ok(isSeq(steps));
	return names.map((name) => {
		const step = steps.items.find((item) => isMap(item) && item.get("name") === name);
		assert.ok(isMap(step));
		const body = step.get("run");
		assert.equal(typeof body, "string");
		assert.ok(typeof body === "string");
		const script = body
			.replaceAll(/\$\{\{ github.server_url \}\}/gu, "https://github.com")
			.replaceAll(/\$\{\{ github.repository \}\}/gu, "hephaestus-build/Hephaestus");
		assert.ok(!script.includes("${{"));
		return script;
	});
};
const publication = jobSteps("publish-release", [
	"Verify k3s host smoke records",
	"Verify durable assets from a clean environment",
	"Publish immutable release",
	"Promote verified image digests",
]);
// The draft writer runs after the evidence gate, under the promotion lock that publication holds.
const writer = jobSteps("tag-images", [
	"Create the draft release",
	"Upload release image lock to GitHub Release",
]);
// The producer: chart push behind the release-state guard, the joint lock and its projection,
// the two lock signatures and the chart's registry signature, and the fail-fast verification.
const producer = jobSteps("tag-images", [
	"Publish the chart by digest",
	"Write release image lock",
	"Sign release image lock",
	"Verify signature with the deploy-side identity (fail fast)",
]);
/** The v0.94.7 lock reader, byte-exact; hosts one release behind verify a new release with it. */
const previousReader = path.join(root, "scripts/fixtures/release-v0.94.7/release-image-lock.ts");
/** Remote release, tag and registry state; everything else in the archive is a release asset. */
const remoteState: ReadonlySet<string> = new Set([
	"state",
	"aliases",
	"releases",
	"tags",
	"tag-objects",
	"chart-registry",
	"chart-metadata",
	"chart-tags",
	"chart-signatures",
]);

// Only remote services are fixtures. The steps run the real lock, checksum, SBOM and policy
// verifiers. Synthetic signatures authenticate fixture bytes; they make no claim about Sigstore.
const transport = String.raw`#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const tool = path.basename(process.argv[1]);
const args = process.argv.slice(2);
const store = process.env.ARCHIVE;
const read = name => JSON.parse(fs.readFileSync(path.join(store, name), 'utf8'));
const state = () => read('state');
const option = flag => args[args.indexOf(flag) + 1];
const write = (name, value) => fs.writeFileSync(path.join(store, name), JSON.stringify(value));
fs.appendFileSync(process.env.CALL_LOG, JSON.stringify([tool, ...args]) + '\n');
if (tool === 'gh') {
  if (args[0] === 'attestation') {
    // The chart package has no release attestation in this scenario; images keep theirs.
    if (process.env.CHART_ATTESTATION_MISSING === 'true' && args[2].endsWith('.tgz')) { console.error('no matching attestations found'); process.exit(1); }
    process.exit(0);
  }
  if (args[0] === 'api') {
    if (process.env.AUTHORITY_UNAVAILABLE === 'true') throw new Error('Release authority unavailable');
    const endpoint = args.find(arg => arg.startsWith('repos/')) ?? '';
    let response;
    if (endpoint.endsWith('/commits/main')) {
      console.log(process.env.MAIN_SHA ?? read('manifest.json').commit); process.exit(0);
    } else if (endpoint.endsWith('/releases?per_page=100')) {
      if (!args.includes('--paginate') || !args.includes('--jq') || args.includes('--slurp')) throw new Error('Expected projected paginated release listing');
      response = read('releases').map(release => ({target_commitish:'main', ...release}));
    } else if (endpoint.includes('/git/matching-refs/tags/')) {
      if (!args.includes('--paginate') || !args.includes('--jq')) throw new Error('Expected projected paginated tag listing');
      const prefix = 'refs/tags/' + endpoint.split('/git/matching-refs/tags/')[1];
      response = Object.entries(read('tags')).map(([name, object]) => ({ref:'refs/tags/' + name, object})).filter(({ref}) => ref.startsWith(prefix));
    } else if (endpoint.includes('/git/tags/')) {
      response = read('tag-objects')[endpoint.split('/git/tags/')[1]];
      if (response === undefined) { console.error('gh: Not Found (HTTP 404)'); process.exit(1); }
    } else throw new Error('Unexpected API endpoint');
    const projected = require('node:child_process').spawnSync('jq', ['-c', option('--jq')], {input:JSON.stringify(response), encoding:'utf8'});
    if (projected.status !== 0) throw new Error(projected.stderr);
    process.stdout.write(projected.stdout);
    process.exit(0);
  }
  const current = state();
  if (args[1] === 'view' && process.env.RELEASE_UNAVAILABLE === 'true') { console.error('HTTP 502: Bad Gateway'); process.exit(1); }
  if (['view', 'upload', 'edit'].includes(args[1]) && current === null) { console.error('release not found'); process.exit(1); }
  if (args[1] === 'download') {
    for (const file of fs.readdirSync(store)) {
      if (!['state', 'aliases', 'releases', 'tags', 'tag-objects', 'chart-registry', 'chart-metadata', 'chart-tags', 'chart-signatures'].includes(file)) fs.copyFileSync(path.join(store, file), path.join(option('--dir'), file));
    }
  } else if (args[1] === 'view') {
    if (args.includes('--jq')) {
      const field = option('--jq');
      if (!['.isDraft', '.isImmutable', '.isPrerelease'].includes(field)) throw new Error('Unexpected release query');
      console.log(field === '.isPrerelease' ? (current.isPrerelease ?? false) : current[field.slice(1)]);
    } else if (args.includes('--json')) {
      const fields = option('--json').split(',');
      if (!fields.every(field => ['isDraft', 'targetCommitish'].includes(field))) throw new Error('Unexpected release fields');
      console.log(JSON.stringify(Object.fromEntries(fields.map(field => [field, current[field]]))));
    } else console.log(current.isDraft ? 'draft' : 'published');
  } else if (args[1] === 'create') {
    if (current !== null) throw new Error('a release with the same tag name already exists');
    write('state', {isDraft:true, isImmutable:false, isPrerelease:args.includes('--prerelease'), targetCommitish:option('--target')});
  } else if (args[1] === 'upload') {
    if (!current.isDraft) throw new Error('Immutable assets cannot be overwritten');
    for (const file of args.slice(3, args.indexOf('--clobber'))) fs.copyFileSync(file, path.join(store, path.basename(file)));
  } else if (args[1] === 'edit') {
    if (!current.isDraft) throw new Error('Published releases cannot be edited');
    write('state', {...current, isDraft:false, isPrerelease:args.includes('--prerelease=true'), isImmutable:process.env.PUBLISH_MUTABLE !== 'true'});
    // Publication creates a missing tag at the draft's target, as GitHub does.
    const tags = read('tags');
    if (!(args[2] in tags)) write('tags', {...tags, [args[2]]: {type:'commit', sha:current.targetCommitish}});
  } else throw new Error('Unexpected gh command');
} else if (tool === 'cosign') {
  // A synthetic bundle is a JSON object naming the signed bytes, as keyless-sign.ts requires one.
  if (args[0] === 'verify-blob') {
    const bytes = fs.readFileSync(args.at(-1));
    const signedHash = JSON.parse(fs.readFileSync(option('--bundle'), 'utf8')).sha256;
    if (crypto.createHash('sha256').update(bytes).digest('hex') !== signedHash) throw new Error('Signature mismatch');
  } else if (args[0] === 'sign-blob') {
    const bytes = fs.readFileSync(args.at(-1));
    fs.writeFileSync(option('--bundle'), JSON.stringify({sha256: crypto.createHash('sha256').update(bytes).digest('hex')}));
  } else if (args[0] === 'verify-attestation') {
    const subject = read('manifest.json').subjects.find(subject => args.at(-1).endsWith(subject.digest));
    const predicate = read('webapp-' + subject.platform.replace('/', '-') + '.spdx.json');
    console.log(JSON.stringify({payload:Buffer.from(JSON.stringify({predicate})).toString('base64')}));
  } else if (args[0] === 'sign') {
    // The registry keeps a signature beside the chart digest that the release signed.
    write('chart-signatures', [...read('chart-signatures'), args.at(-1)]);
  } else if (args[0] === 'verify') {
    // A chart digest verifies only with its registry signature; image signatures are not modelled.
    if (args[1] in read('chart-registry') && !read('chart-signatures').includes(args[1])) { console.error('Error: no signatures found'); process.exit(1); }
  } else throw new Error('Unexpected cosign command');
} else if (tool === 'helm') {
  // The registry holds chart packages by manifest reference; a pull by any other digest misses.
  if (args[0] === 'pull') {
    const packages = read('chart-registry');
    const reference = args[1].replace(/^oci:\/\//, '');
    if (!(reference in packages)) { console.error('Error: ' + reference + ': not found'); process.exit(1); }
    const name = reference.split('/').at(-1).replace(':', '-') + '.tgz';
    fs.writeFileSync(path.join(option('--destination'), name), Buffer.from(packages[reference].bytes, 'base64'));
    console.log('Digest: ' + reference.split('@')[1]);
  } else if (args[0] === 'show' && args[1] === 'chart') {
    process.stdout.write(fs.readFileSync(path.join(store, 'chart-metadata'), 'utf8'));
  } else if (args[0] === 'show' && args[1] === 'values') {
    // A package holds the values.yaml it was made from, after one line that names the package.
    process.stdout.write(fs.readFileSync(args[2], 'utf8').split('\n').slice(1).join('\n') + '\n');
  } else if (args[0] === 'version' || (args[0] === 'registry' && args[1] === 'login')) {
    fs.readFileSync(0);
  } else if (args[0] === 'package') {
    fs.writeFileSync(path.join(option('--destination'), 'hephaestus-' + option('--version') + '.tgz'), 'packaged at ' + option('--app-version') + '\n' + fs.readFileSync(path.join(args[1], 'values.yaml'), 'utf8'));
    fs.writeFileSync(path.join(store, 'chart-metadata'), 'apiVersion: v2\nname: hephaestus\nversion: ' + option('--version') + '\nappVersion: ' + option('--app-version') + '\n');
  } else if (args[0] === 'push') {
    // A push writes the version tag; the registry answers with the manifest digest on stdout.
    const bytes = fs.readFileSync(args[1]);
    const digest = 'sha256:' + crypto.createHash('sha256').update(bytes).digest('hex');
    const packages = read('chart-registry');
    const name = args[2].replace(/^oci:\/\//, '') + '/hephaestus';
    const version = path.basename(args[1]).replace(/^hephaestus-|\.tgz$/g, '');
    write('chart-registry', {...packages, [name + '@' + digest]: {version, bytes: bytes.toString('base64')}});
    write('chart-tags', {...read('chart-tags'), [name + ':' + version]: digest});
    console.log('Pushed: ' + name + ':' + version + '\nDigest: ' + digest);
  } else throw new Error('Unexpected helm command');
} else if (tool === 'docker') {
  if (args.includes('--raw')) {
    console.log(JSON.stringify({manifests:read('manifest.json').subjects.map(subject => ({digest:subject.digest, platform:{os:'linux', architecture:subject.platform.split('/')[1]}}))}));
  } else if (args.includes('inspect')) {
    const reference = args[3];
    const digest = reference.includes('@') ? reference.split('@')[1] : read('aliases')[reference];
    console.log(JSON.stringify({digest}));
  } else if (args.includes('create')) {
    if (!state().isImmutable) throw new Error('Aliases require immutable publication');
    const aliases = read('aliases');
    for (let i = 0; i < args.length; i++) {
      if (args[i] !== '-t') continue;
      aliases[args[++i]] = args.at(-1).split('@')[1];
      fs.writeFileSync(path.join(store, 'aliases'), JSON.stringify(aliases));
      if (process.env.FAIL_PARTIAL === 'true') process.exit(23);
    }
  } else throw new Error('Unexpected docker command');
} else throw new Error('Unexpected tool');
`;

async function fixture(directory: string, source = commit, tag = release) {
	const fixtureRelease = tag;
	const fixtureChart = { ...defaultChart, version: tag.slice(1) };
	const fixtureJointLock = `release-${fixtureRelease}.chart.json`;
	const archive = path.join(directory, "archive");
	const bin = path.join(directory, "bin");
	const smoke = path.join(directory, "host-smoke-k3s");
	for (const folder of [archive, bin, smoke]) {
		await mkdir(folder);
	}
	await symlink(path.join(root, "scripts"), path.join(directory, "scripts"), "dir");
	await symlink(path.join(root, "deploy"), path.join(directory, "deploy"), "dir");
	const json = async (name: string, value: unknown) =>
		writeFile(path.join(archive, name), JSON.stringify(value));
	for (const tool of ["gh", "cosign", "docker", "helm"]) {
		await writeFile(path.join(bin, tool), transport, { mode: 0o755 });
	}
	const environment = {
		...process.env,
		PATH: `${bin}${path.delimiter}${process.env.PATH ?? ""}`,
		ARCHIVE: archive,
		CALL_LOG: path.join(directory, "calls"),
		RUNNER_TEMP: directory,
		GITHUB_REPOSITORY: "hephaestus-build/Hephaestus",
		GITHUB_ACTIONS: "true",
		GH_TOKEN: "fixture-not-a-secret",
		TAG_NAME: fixtureRelease,
		RELEASE_SHA: source,
		VERSION: fixtureRelease.slice(1),
		SERIES: "0.93",
		CANDIDATE: "",
		PRERELEASE: String(fixtureRelease.includes("-rc.")),
	};
	const subjects = [
		...platforms.map((architecture, index) => ({
			image: "webapp",
			repository,
			provenance: "first-party",
			indexDigest,
			digest: `sha256:${String(index + 1).repeat(64)}`,
			platform: `linux/${architecture}`,
		})),
		...upstream.flatMap((pin) =>
			platforms.map((architecture, index) => ({
				image: pin.name,
				repository: pin.repository,
				provenance: "upstream",
				indexDigest: pin.digest,
				digest: `sha256:${String(index + 5).repeat(64)}`,
				platform: `linux/${architecture}`,
			})),
		),
	];
	const manifest = {
		schemaVersion: 1,
		release: fixtureRelease,
		commit: source,
		bundledNpmSbom: 1,
		subjects,
	};
	await json("manifest.json", manifest);
	await json("release-images.json", inventory);
	// The checkout's inventory, which the release scripts read beside the evidence.
	await mkdir(path.join(directory, "security"));
	await writeFile(
		path.join(directory, "security", "release-images.json"),
		JSON.stringify(inventory),
	);
	await json("vulnerability-policy.json", {
		schemaVersion: 3,
		componentInventories: [],
		goAdvisories: [],
		exceptions: [],
	});
	// Both locks come from the release's own producer: one joint lock, and its schema 1 projection.
	await writeFile(path.join(directory, "chart.json"), JSON.stringify(fixtureChart));
	const produced = spawnSync(
		process.execPath,
		[
			path.join(root, "scripts/release-image-lock.ts"),
			"write",
			path.join(archive, "manifest.json"),
			path.join(directory, "chart.json"),
			fixtureRelease,
			path.join(archive, fixtureJointLock),
			path.join(archive, `release-${fixtureRelease}.json`),
		],
		{ encoding: "utf8", env: environment, timeout: 30_000 },
	);
	assert.equal(produced.status, 0, produced.stderr);
	for (const subject of subjects) {
		const prefix = `${subject.image}-${subject.platform.replace("/", "-")}`;
		const reference = `${subject.repository}@${subject.digest}`;
		const packagePath =
			subject.image === "webapp"
				? "/usr/share/nginx/html/.vite/packages/example%401.0.0/package.json"
				: "/app/package.json";
		const artifact = {
			name: "example",
			version: "1.0.0",
			type: "npm",
			foundBy: "javascript-package-cataloger",
			purl: "pkg:npm/example@1.0.0",
			locations: [{ path: packagePath }],
			licenses: [{ value: "MIT" }],
		};
		await json(`${prefix}.syft.json`, {
			source: {
				type: "image",
				name: subject.repository,
				version: subject.digest,
				metadata: {
					userInput: reference,
					manifestDigest: subject.digest,
					mediaType: "application/vnd.oci.image.manifest.v1+json",
					os: "linux",
					architecture: subject.platform.split("/")[1],
					repoDigests: [reference],
				},
			},
			artifacts: [artifact],
			files: [{ location: { path: packagePath } }],
		});
		await json(`${prefix}.spdx.json`, {
			spdxVersion: "SPDX-2.3",
			dataLicense: "CC0-1.0",
			documentNamespace: "https://example.invalid/sbom",
			packages: [
				{
					name: subject.repository,
					versionInfo: subject.digest,
					primaryPackagePurpose: "CONTAINER",
				},
				{
					name: artifact.name,
					versionInfo: artifact.version,
					externalRefs: [{ referenceType: "purl", referenceLocator: artifact.purl }],
				},
			],
		});
		await json(`${prefix}.cdx.json`, {
			bomFormat: "CycloneDX",
			specVersion: "1.7",
			metadata: {
				component: { type: "container", name: subject.repository, version: subject.digest },
			},
			components: [artifact],
		});
		for (const kind of ["trivy", "license"]) {
			await json(`${prefix}.${kind}.json`, {
				ArtifactName: reference,
				Results:
					subject.image === "webapp"
						? [
								{
									Type: "node-pkg",
									Target: "Node.js",
									Packages: [
										{
											Name: artifact.name,
											Version: artifact.version,
											FilePath: packagePath.slice(1),
										},
									],
								},
							]
						: [],
			});
		}
	}
	const validation = spawnSync(
		process.execPath,
		[path.join(root, "scripts/verify-release-evidence.ts"), archive, "--write-validation"],
		{ encoding: "utf8", env: environment },
	);
	assert.equal(validation.status, 0, validation.stderr);
	const files = await readdir(archive);
	const checksums = await Promise.all(
		files.map(async (file) => `${hash(await readFile(path.join(archive, file)))}  ${file}\n`),
	);
	await writeFile(path.join(archive, "SHA256SUMS"), checksums.join(""));
	for (const file of ["SHA256SUMS", `release-${fixtureRelease}.json`, fixtureJointLock]) {
		await sign(path.join(archive, file));
	}
	// The k3s platform assets: collected in the job's workspace, where the producer signs them, and
	// published beside the locks. They are not evidence.
	await mkdir(path.join(directory, "k3s-platform"));
	for (const name of platformAssets) {
		for (const folder of [path.join(directory, "k3s-platform"), archive]) {
			await writeFile(path.join(folder, name), `# ${name} of ${source}\n`);
		}
		await sign(path.join(archive, name));
	}
	// The chart is not evidence: the registry, not the release, holds the package and its signature.
	// Its values pin the images of the lock that the evidence makes.
	const pinned = releaseValuesLines(
		chartValues(lockFromEvidence(manifest, fixtureRelease), parseInventory(inventory)),
	);
	const packageBytes = `hephaestus chart ${fixtureChart.version} from ${source}\n${pinned}`;
	await json("chart-signatures", [`${fixtureChart.repository}@${fixtureChart.digest}`]);
	await json("chart-tags", {});
	await json("chart-registry", {
		[`${fixtureChart.repository}@${fixtureChart.digest}`]: {
			version: fixtureChart.version,
			bytes: Buffer.from(packageBytes).toString("base64"),
		},
	});
	await writeFile(
		path.join(archive, "chart-metadata"),
		`apiVersion: v2\nname: hephaestus\nversion: ${fixtureChart.version}\nappVersion: ${fixtureChart.version}\n`,
	);
	for (const architecture of platforms) {
		await writeFile(
			path.join(smoke, `host-smoke-k3s-${architecture}.json`),
			JSON.stringify({
				schemaVersion: 1,
				release: fixtureRelease,
				commit: source,
				testedAt: new Date("2026-10-15T10:00:00Z").toISOString(),
				host: {
					architecture,
					os: "Ubuntu 24.04",
					kernel: "6.14.0",
					kubeletVersion: "v1.35.9+k3s1",
				},
				workers: 2,
				checks: {
					install: true,
					secondInstall: true,
					upgrade: true,
					workloadsReady: true,
					isolation: true,
					reviewsEnabled: true,
					gateway: true,
					shutdownBudget: true,
					statefulPodsPreserved: true,
					secretsPreserved: true,
				},
			}),
		);
	}
	await json("state", {
		isDraft: true,
		isImmutable: false,
		isPrerelease: fixtureRelease.includes("-rc."),
		targetCommitish: source,
	});
	await json("tags", {});
	await json("tag-objects", {});
	await json("aliases", {});
	await json("releases", [
		{
			tag_name: fixtureRelease,
			draft: false,
			prerelease: fixtureRelease.includes("-rc."),
			immutable: true,
			target_commitish: source,
		},
		{ tag_name: "v0.9.0", draft: false, prerelease: false },
		{ tag_name: "v99.0.0", draft: false, prerelease: true },
		{ tag_name: "v100.0.0", draft: true, prerelease: false },
	]);
	// Each step runs in its own shell, and the job stops at the first failing step.
	const run = async (overrides: Record<string, string> = {}, job = publication) => {
		if (job === publication) {
			await rm(path.join(directory, "evidence"), { recursive: true, force: true });
			await rm(path.join(directory, "release-lock.env"), { force: true });
			await rm(path.join(directory, "chart"), { recursive: true, force: true });
			await rm(path.join(directory, "installed-chart"), { recursive: true, force: true });
		}
		await writeFile(environment.CALL_LOG, "");
		let status: number | null = 0;
		let output = "";
		for (const step of job) {
			const result = spawnSync("bash", ["--noprofile", "--norc", "-e", "-c", step], {
				cwd: directory,
				encoding: "utf8",
				env: { ...environment, ...overrides },
			});
			assert.equal(result.error, undefined);
			output += result.stdout + result.stderr;
			status = result.status;
			if (status !== 0) {
				break;
			}
		}
		return { status, output, calls: await readFile(environment.CALL_LOG, "utf8") };
	};
	const assets = async () => {
		const assetFiles = await readdir(archive);
		return Object.fromEntries(
			await Promise.all(
				assetFiles
					.filter((file) => !remoteState.has(file))
					.map(async (file) => [file, hash(await readFile(path.join(archive, file)))] as const),
			),
		);
	};
	return { archive, smoke, json, run, assets, manifest };
}

const authorityRefusal = /Only the current stable release/u;
const refusalReasons = new Map([
	["wrong-commit", /qualified source commit/u],
	["tampered-policy", /sha256sum failed/u],
	["missing-sbom", /sha256sum failed/u],
	["authority-unavailable", /Release authority unavailable/u],
	["newer-patch", authorityRefusal],
	["newer-minor", authorityRefusal],
	["authority-empty", authorityRefusal],
	["authority-inconsistent", authorityRefusal],
	["authority-malformed", /boolean draft and prerelease flags/u],
	["authority-malformed-draft", /boolean draft and prerelease flags/u],
	["version-mismatch", /Release version does not match verified evidence/u],
	["unsigned-chart", /no signatures found/u],
	["unpinned-chart", /hephaestus@sha256-b+\.tgz does not pin the v0\.93\.0 images/u],
	["unlocked-chart-digest", /not found/u],
	["missing-joint-lock", /ENOENT/u],
	["projection-mismatch", /the schema 1 lock is not the projection of the joint lock/u],
	["foreign-chart-version", /is not the hephaestus chart 0\.93\.0/u],
	["chart-attestation-missing", /no matching attestations found/u],
]);

/**
 * The chart half of a published release, changed the way each chart scenario needs before the
 * retry verifies it: the registry, the chart metadata, or the joint lock. Other scenarios change none.
 */
async function tamperChartAssets(
	scenario: string,
	archive: string,
	json: (name: string, value: unknown) => Promise<void>,
): Promise<void> {
	if (scenario === "unsigned-chart") {
		await json("chart-signatures", []);
	}
	if (scenario === "unpinned-chart") {
		// A signed chart at the locked digest whose values pin another webapp than the joint lock.
		const reference = `${chart.repository}@${chart.digest}`;
		const registry = asRecord(
			parseJson(await readFile(path.join(archive, "chart-registry"), "utf8")),
			"chart registry",
		);
		const entry = asRecord(registry[reference], "locked chart");
		const bytes = Buffer.from(String(entry.bytes), "base64")
			.toString("utf8")
			.replace(
				`webapp: "${repository}@${indexDigest}"`,
				`webapp: "${repository}@sha256:${"9".repeat(64)}"`,
			);
		await json("chart-registry", {
			[reference]: { ...entry, bytes: Buffer.from(bytes).toString("base64") },
		});
	}
	if (scenario === "unlocked-chart-digest") {
		await json("chart-registry", {
			[`${chart.repository}@sha256:${"c".repeat(64)}`]: { version: chart.version, bytes: "" },
		});
	}
	if (scenario === "foreign-chart-version") {
		await writeFile(
			path.join(archive, "chart-metadata"),
			`apiVersion: v2\nname: hephaestus\nversion: 0.93.1\nappVersion: 0.93.1\n`,
		);
	}
	if (scenario === "missing-joint-lock") {
		// The schema 1 lock alone is valid history, but this release must name its chart.
		await rm(path.join(archive, jointLock));
		await rm(path.join(archive, `${jointLock}.sigstore.json`));
	}
	if (scenario === "projection-mismatch") {
		// A correctly signed joint lock whose images are not the ones the schema 1 lock names.
		const lock = path.join(archive, jointLock);
		const joint = asRecord(parseJson(await readFile(lock, "utf8")), "joint lock");
		await writeFile(
			lock,
			JSON.stringify(joint).replaceAll(indexDigest, `sha256:${"9".repeat(64)}`),
		);
		await sign(lock);
	}
}

for (const scenario of [
	"draft",
	"retry",
	"tampered-smoke",
	"missing-smoke",
	"tampered-policy",
	"missing-sbom",
	"wrong-commit",
	"mutable-publication",
	"newer-patch",
	"newer-minor",
	"authority-unavailable",
	"authority-empty",
	"authority-inconsistent",
	"authority-malformed",
	"authority-malformed-draft",
	"version-mismatch",
	"unsigned-chart",
	"unpinned-chart",
	"unlocked-chart-digest",
	"missing-joint-lock",
	"projection-mismatch",
	"foreign-chart-version",
	"chart-attestation-missing",
]) {
	void test(
		`release publication: ${scenario}`,
		{ skip: process.platform === "win32" },
		async (t) => {
			const directory = await mkdtemp(path.join(tmpdir(), "release-publication-"));
			t.after(async () => rm(directory, { recursive: true, force: true }));
			const { archive, smoke, json, run, assets } = await fixture(directory);
			if (scenario === "draft") {
				const result = await run();
				assert.equal(result.status, 0, result.output);
				assert.match(result.calls, /"upload"/u);
				assert.match(result.calls, /"edit"/u);
				// The chart is pulled by its locked digest, never by a tag, and its attestation checked.
				assert.match(
					result.calls,
					/\["helm","pull","oci:\/\/ghcr\.io\/hephaestus-build\/charts\/hephaestus@sha256:b{64}","--destination",/u,
				);
				assert.doesNotMatch(result.calls, /charts\/hephaestus:/u);
				assert.match(result.calls, /"--destination","[^"]*\/installed-chart\/\.chart-[^"]*"/u);
				assert.match(
					await readFile(path.join(directory, "installed-chart", `${release}.images.yaml`), "utf8"),
					/ghcr\.io\/hephaestus-build\/webapp@sha256:/u,
				);

				// Its signature is the registry signature of that digest, by the release workflow.
				assert.match(
					result.calls,
					/\["cosign","verify","ghcr\.io\/hephaestus-build\/charts\/hephaestus@sha256:b{64}","--certificate-identity","https:\/\/github\.com\/hephaestus-build\/Hephaestus\/\.github\/workflows\/release\.yml@refs\/heads\/main",/u,
				);
				assert.match(
					result.calls,
					/\["gh","attestation","verify","[^"]*\/chart\/hephaestus-0\.93\.0\.tgz","--repo","hephaestus-build\/Hephaestus","--signer-workflow","hephaestus-build\/Hephaestus\/\.github\/workflows\/release\.yml"/u,
				);
				assert.deepEqual(
					await readFile(path.join(archive, "host-smoke-k3s-arm64.json")),
					await readFile(path.join(smoke, "host-smoke-k3s-arm64.json")),
				);
				assert.deepEqual(JSON.parse(await readFile(path.join(archive, "state"), "utf8")), {
					isDraft: false,
					isImmutable: true,
					isPrerelease: false,
					targetCommitish: commit,
				});
				assert.deepEqual(JSON.parse(await readFile(path.join(archive, "tags"), "utf8")), {
					[release]: { type: "commit", sha: commit },
				});
				return;
			}
			if (scenario === "mutable-publication") {
				const result = await run({ PUBLISH_MUTABLE: "true" });
				assert.notEqual(result.status, 0);
				assert.match(result.output, /published release is not immutable/u);
				assert.doesNotMatch(result.calls, /"create"/u);
				return;
			}
			const first = await run({ FAIL_PARTIAL: "true" });
			assert.equal(first.status, 23, first.output);
			assert.match(first.calls, /"upload"/u);
			assert.match(first.calls, /"edit"/u);
			const archivedSmoke = path.join(archive, "host-smoke-k3s-amd64.json");
			assert.deepEqual(
				await readFile(archivedSmoke),
				await readFile(path.join(smoke, "host-smoke-k3s-amd64.json")),
			);
			if (scenario === "tampered-smoke") {
				await writeFile(archivedSmoke, "{}");
			}
			if (scenario === "missing-smoke") {
				await rm(archivedSmoke);
			}
			if (scenario === "tampered-policy") {
				await json("vulnerability-policy.json", {});
			}
			if (scenario === "missing-sbom") {
				await rm(path.join(archive, "webapp-linux-arm64.spdx.json"));
			}
			if (scenario === "wrong-commit") {
				const lock = path.join(archive, `release-${release}.json`);
				const contents = await readFile(lock, "utf8");
				await writeFile(lock, contents.replace(commit, "d".repeat(40)));
				await sign(lock);
			}
			if (scenario === "newer-patch" || scenario === "newer-minor") {
				const newer = scenario === "newer-patch" ? "v0.93.1" : "v0.100.0";
				await json("releases", [
					{ tag_name: release, draft: false, prerelease: false, created_at: "2026-10-15" },
					{ tag_name: newer, draft: false, prerelease: false, created_at: "2026-10-01" },
				]);
				await json("aliases", {
					[`${repository}:latest`]: `sha256:${"f".repeat(64)}`,
					[`${repository}:0.93`]: `sha256:${"f".repeat(64)}`,
				});
			}
			if (scenario === "authority-empty") {
				await json("releases", []);
			}
			if (scenario === "authority-inconsistent") {
				await json("releases", [{ tag_name: release, draft: false, prerelease: true }]);
			}
			if (scenario === "authority-malformed") {
				await json("releases", [
					{
						tag_name: release,
						draft: false,
						prerelease: false,
						immutable: true,
						target_commitish: commit,
					},
					{ tag_name: "v0.93.1", draft: false },
				]);
			}
			if (scenario === "authority-malformed-draft") {
				await json("releases", [
					{
						tag_name: release,
						draft: false,
						prerelease: false,
						immutable: true,
						target_commitish: commit,
					},
					{ tag_name: "v0.93.1", prerelease: false },
				]);
			}
			if (scenario === "version-mismatch") {
				await json("releases", [{ tag_name: "v0.93.1", draft: false, prerelease: false }]);
			}
			await tamperChartAssets(scenario, archive, json);
			const before = await assets();
			const aliasesBefore = await readFile(path.join(archive, "aliases"), "utf8");
			const retry = await run(
				scenario === "version-mismatch"
					? { VERSION: "0.93.1" }
					: {
							AUTHORITY_UNAVAILABLE: String(scenario === "authority-unavailable"),
							CHART_ATTESTATION_MISSING: String(scenario === "chart-attestation-missing"),
						},
			);
			assert.equal(retry.status === 0, scenario === "retry", retry.output);
			assert.match(retry.calls, /"verify-blob"/u);
			assert.doesNotMatch(retry.calls, /"upload"|"edit"/u);
			if (scenario === "retry") {
				assert.match(retry.calls, /"verify-attestation"/u);
				assert.equal(
					await readFile(path.join(archive, "aliases"), "utf8"),
					JSON.stringify({
						[`${repository}:0.93.0`]: indexDigest,
						[`${repository}:0.93`]: indexDigest,
						[`${repository}:latest`]: indexDigest,
					}),
				);
			} else {
				assert.doesNotMatch(retry.calls, /"create"/u);
				assert.equal(await readFile(path.join(archive, "aliases"), "utf8"), aliasesBefore);
				const reason = refusalReasons.get(scenario) ?? /host smoke record differs or is missing/u;
				assert.match(retry.output, reason);
			}
			assert.deepEqual(await assets(), before, "retry must keep published assets unchanged");
		},
	);
}

// A commit planned after `commit`, before either had a draft: the forward-fix rule lets both cut.
const later = "f".repeat(40);
const tagObject = "e".repeat(40);
const releaseState = async (directory: string) =>
	readFile(path.join(directory, "archive", "state"), "utf8");

/** Valid evidence of the later commit, where the draft writer finds it after its evidence gate. */
async function laterEvidence(directory: string) {
	const source = path.join(directory, "later");
	await mkdir(source);
	const { archive } = await fixture(source, later);
	const evidence = path.join(directory, "evidence");
	await mkdir(evidence);
	for (const file of await readdir(archive)) {
		if (!remoteState.has(file)) {
			await copyFile(path.join(archive, file), path.join(evidence, file));
		}
	}
	// What the producer signed in the job's workspace before the writer uploads it.
	for (const name of platformAssets) {
		await sign(path.join(directory, "k3s-platform", name));
	}
	const notes = `## ${release.slice(1)}\n\nComplete release notes.\n`;
	const notesDirectory = path.join(directory, "release-notes");
	await mkdir(notesDirectory);
	await writeFile(path.join(evidence, "release-notes.md"), notes);
	await writeFile(
		path.join(notesDirectory, "release-body.md"),
		githubReleaseBody({
			notes,
			release,
			commit: later,
			repository: "hephaestus-build/Hephaestus",
			migrations: false,
		}),
	);
	const attestation = path.join(directory, "attestation.jsonl");
	await writeFile(attestation, '{"mediaType":"application/vnd.dev.sigstore.bundle.v0.3+json"}\n');
	return {
		SHA: later,
		ASSET: `evidence/release-${release}.json`,
		ATTESTATION: attestation,
		JOINT: `evidence/${jointLock}`,
		MIGRATIONS: "false",
	};
}

const writerRefusals = new Map([
	["other-source-draft", /draft v0\.93\.0 targets c{40}, not f{40}/u],
	["published", /v0\.93\.0 is published; this step accepts only absent or draft/u],
	["other-source-tag", /tag v0\.93\.0 points to c{40}, not f{40}/u],
	["release-unavailable", /HTTP 502/u],
]);

for (const scenario of ["absent", "same-source-draft", ...writerRefusals.keys()]) {
	void test(
		`release draft writer: ${scenario}`,
		{ skip: process.platform === "win32" },
		async (t) => {
			const directory = await mkdtemp(path.join(tmpdir(), "release-draft-"));
			t.after(async () => rm(directory, { recursive: true, force: true }));
			// The fixture's draft and assets belong to the commit that wrote first.
			const { archive, json, run, assets } = await fixture(directory);
			const evidence = await laterEvidence(directory);
			if (scenario === "absent" || scenario === "other-source-tag") {
				await json("state", null);
			}
			if (scenario === "same-source-draft") {
				await json("state", {
					isDraft: true,
					isImmutable: false,
					isPrerelease: false,
					targetCommitish: later,
				});
			}
			if (scenario === "published") {
				await json("state", {
					isDraft: false,
					isImmutable: true,
					isPrerelease: false,
					targetCommitish: later,
				});
				await json("tags", { [release]: { type: "commit", sha: later } });
			}
			if (scenario === "other-source-tag") {
				await json("tags", { [release]: { type: "commit", sha: commit } });
			}
			const before = await assets();
			const stateBefore = await releaseState(directory);
			const result = await run(
				{ ...evidence, RELEASE_UNAVAILABLE: String(scenario === "release-unavailable") },
				writer,
			);
			const reason = writerRefusals.get(scenario);
			if (reason === undefined) {
				assert.equal(result.status, 0, result.output);
				assert.equal(result.calls.includes('"create"'), scenario === "absent");
				assert.match(result.calls, /"upload"/u);
				assert.deepEqual(JSON.parse(await releaseState(directory)), {
					isDraft: true,
					isImmutable: false,
					isPrerelease: false,
					targetCommitish: later,
				});
				assert.deepEqual(
					await readFile(path.join(archive, `release-${release}.json`)),
					await readFile(path.join(directory, evidence.ASSET)),
				);
				assert.deepEqual(
					await readFile(path.join(archive, "release-notes.md")),
					await readFile(path.join(directory, "evidence", "release-notes.md")),
				);
				// The release attestation travels with the release, under the name provenance tools read.
				assert.deepEqual(
					await readFile(path.join(archive, `release-${release}.intoto.jsonl`)),
					await readFile(evidence.ATTESTATION),
				);
				assert.deepEqual(
					await readFile(path.join(archive, jointLock)),
					await readFile(path.join(directory, evidence.JOINT)),
				);
				return;
			}
			assert.doesNotMatch(result.calls, /"create"|"upload"|"edit"/u);
			assert.deepEqual(await assets(), before, "a refused writer must leave the assets unchanged");
			assert.equal(await releaseState(directory), stateBefore);
			assert.notEqual(result.status, 0);
			assert.match(result.output, reason);
		},
	);
}

const sourceRefusals = new Map([
	// The later commit's valid evidence on the earlier commit's draft, as an unchecked resume leaves it.
	["other-source-draft", /draft v0\.93\.0 targets c{40}, not f{40}/u],
	["other-source-tag", /tag v0\.93\.0 points to f{40}, not c{40}/u],
	["other-annotated-tag-retry", /tag v0\.93\.0 points to f{40}, not c{40}/u],
]);

for (const scenario of ["annotated-tag-retry", ...sourceRefusals.keys()]) {
	void test(
		`release publication source: ${scenario}`,
		{ skip: process.platform === "win32" },
		async (t) => {
			const directory = await mkdtemp(path.join(tmpdir(), "release-source-"));
			t.after(async () => rm(directory, { recursive: true, force: true }));
			const { archive, json, run, assets } = await fixture(
				directory,
				scenario === "other-source-draft" ? later : commit,
			);
			if (scenario === "other-source-draft") {
				await json("state", {
					isDraft: true,
					isImmutable: false,
					isPrerelease: false,
					targetCommitish: commit,
				});
			}
			if (scenario === "other-source-tag") {
				await json("tags", { [release]: { type: "commit", sha: later } });
			}
			if (scenario.endsWith("annotated-tag-retry")) {
				const first = await run({ FAIL_PARTIAL: "true" });
				assert.equal(first.status, 23, first.output);
				await json("tags", { [release]: { type: "tag", sha: tagObject } });
				await json("tag-objects", {
					[tagObject]: {
						object: { type: "commit", sha: scenario === "annotated-tag-retry" ? commit : later },
					},
				});
			}
			const before = await assets();
			const stateBefore = await releaseState(directory);
			const aliasesBefore = await readFile(path.join(archive, "aliases"), "utf8");
			const result = await run();
			assert.doesNotMatch(result.calls, /"upload"|"edit"/u);
			assert.deepEqual(
				await assets(),
				before,
				"publication must not change another source's assets",
			);
			assert.equal(await releaseState(directory), stateBefore);
			const reason = sourceRefusals.get(scenario);
			if (reason === undefined) {
				assert.equal(result.status, 0, result.output);
				assert.equal(
					await readFile(path.join(archive, "aliases"), "utf8"),
					JSON.stringify({
						[`${repository}:0.93.0`]: indexDigest,
						[`${repository}:0.93`]: indexDigest,
						[`${repository}:latest`]: indexDigest,
					}),
				);
				return;
			}
			assert.doesNotMatch(result.calls, /"create"/u);
			assert.equal(await readFile(path.join(archive, "aliases"), "utf8"), aliasesBefore);
			assert.notEqual(result.status, 0);
			assert.match(result.output, reason);
		},
	);
}

const producerRefusals = new Map([
	["published", /v0\.93\.0 is published; this step accepts only absent or draft/u],
	["other-source-draft", /draft v0\.93\.0 targets f{40}, not c{40}/u],
]);

void test(
	"RC publication marks prerelease, preserves stable aliases and promotes only its version",
	{ skip: process.platform === "win32" },
	async (t) => {
		const directory = await mkdtemp(path.join(tmpdir(), "release-prerelease-"));
		t.after(async () => rm(directory, { recursive: true, force: true }));
		const tag = "v1.0.0-rc.2";
		const { archive, json, run, manifest } = await fixture(directory, commit, tag);
		const aliases = {
			[`${repository}:latest`]: `sha256:${"f".repeat(64)}`,
			[`${repository}:1.0`]: `sha256:${"f".repeat(64)}`,
		};
		await json("aliases", aliases);
		const stableChartTag = `${chart.repository}:0.95.1`;
		await json("chart-tags", { [stableChartTag]: `sha256:${"f".repeat(64)}` });
		await mkdir(path.join(directory, "evidence"));
		await writeFile(path.join(directory, "evidence/manifest.json"), JSON.stringify(manifest));
		const chartPublished = await run(
			{
				SHA: commit,
				ACTOR: "release-bot",
				REGISTRY_TOKEN: "fixture-token",
				GITHUB_OUTPUT: path.join(directory, "chart-outputs"),
			},
			jobSteps("tag-images", ["Publish the chart by digest", "Write release image lock"]),
		);
		assert.equal(chartPublished.status, 0, chartPublished.output);
		const tags = asRecord(
			parseJson(await readFile(path.join(archive, "chart-tags"), "utf8")),
			"chart tags",
		);
		assert.equal(tags[stableChartTag], `sha256:${"f".repeat(64)}`);
		assert.ok(`${chart.repository}:1.0.0-rc.2` in tags);
		assert.equal(`${chart.repository}:1.0.0` in tags, false);
		const result = await run();
		assert.equal(result.status, 0, result.output);
		assert.match(result.calls, /"--prerelease=true","--latest=false"/u);
		assert.deepEqual(parseJson(await readFile(path.join(archive, "aliases"), "utf8")), {
			...aliases,
			[`${repository}:1.0.0-rc.2`]: indexDigest,
		});
		assert.equal(
			asRecord(parseJson(await readFile(path.join(archive, "state"), "utf8")), "release")
				.isPrerelease,
			true,
		);
	},
);

void test(
	"stable promotion preserves signed RC images and source while packaging stable chart metadata",
	{ skip: process.platform === "win32" },
	async (t) => {
		const directory = await mkdtemp(path.join(tmpdir(), "release-promotion-"));
		t.after(async () => rm(directory, { recursive: true, force: true }));
		const tag = "v1.0.0-rc.2";
		const { archive, json, run, manifest } = await fixture(directory, commit, tag);
		await json("tags", { [tag]: { type: "commit", sha: commit } });
		const overrides = {
			CANDIDATE: tag,
			SOURCE_TAG: commit,
			SHA: commit,
			VERSION: "1.0.0",
			TAG_NAME: "v1.0.0",
			ACTOR: "release-bot",
			GH_TOKEN: "github-token",
			REGISTRY_TOKEN: "registry-token",
			GITHUB_OUTPUT: path.join(directory, "outputs"),
		};
		const capture = jobSteps("tag-images", ["Capture release image digests"]);
		const captured = await run(overrides, capture);
		assert.equal(captured.status, 0, captured.output);
		assert.match(captured.calls, /"verify-blob"/u);
		assert.doesNotMatch(
			captured.calls,
			/"docker"/u,
			"candidate image identities come from its signed lock",
		);
		const resolved = await readFile(path.join(directory, "release-images.tsv"), "utf8");
		assert.ok(resolved.includes(indexDigest));
		assert.doesNotMatch(resolved, new RegExp(`:${commit}`, "u"));
		await mkdir(path.join(directory, "evidence"));
		await writeFile(
			path.join(directory, "evidence/manifest.json"),
			JSON.stringify({ ...manifest, release: "v1.0.0" }),
		);
		const before = await readFile(path.join(archive, "chart-registry"));
		const produced = await run(
			overrides,
			jobSteps("tag-images", ["Publish the chart by digest", "Write release image lock"]),
		);
		assert.equal(produced.status, 0, produced.output);
		assert.doesNotMatch(produced.calls, /"docker"/u);
		assert.match(produced.calls, /"helm","package"/u);
		assert.match(produced.calls, /"helm","push"/u);
		const original = asRecord(
			parseJson(await readFile(path.join(archive, `release-${tag}.chart.json`), "utf8")),
			"candidate",
		);
		const promoted = asRecord(
			parseJson(await readFile(path.join(directory, "release-v1.0.0.chart.json"), "utf8")),
			"stable",
		);
		assert.equal(promoted.release, "v1.0.0");
		assert.equal(promoted.commit, original.commit);
		assert.deepEqual(promoted.images, original.images);
		const stableChart = asRecord(promoted.chart, "stable chart");
		const candidateChart = asRecord(original.chart, "candidate chart");
		assert.equal(stableChart.repository, candidateChart.repository);
		assert.equal(stableChart.version, "1.0.0");
		assert.notEqual(stableChart.digest, candidateChart.digest);
		assert.match(
			await readFile(path.join(directory, "chart/hephaestus-1.0.0.tgz"), "utf8"),
			/packaged at 1\.0\.0\n/u,
		);
		assert.equal(
			await readFile(path.join(directory, "candidate-chart/hephaestus-1.0.0-rc.2.tgz"), "utf8"),
			Buffer.from(
				String(
					asRecord(
						asRecord(parseJson(before.toString()), "registry")[
							`${chart.repository}@${chart.digest}`
						],
						"package",
					).bytes,
				),
				"base64",
			).toString(),
		);
		// Main or the signed archive changing after planning must fail before artifact selection.
		const movedMain = await run(
			{ ...overrides, MAIN_SHA: "f".repeat(40), RUNNER_TEMP: path.join(directory, "moved") },
			capture,
		);
		assert.notEqual(movedMain.status, 0);
		assert.match(movedMain.output, /Main advanced/u);
		await writeFile(
			path.join(archive, `release-${tag}.chart.json`),
			JSON.stringify({ ...original, commit: "f".repeat(40) }),
		);
		const tampered = await run(
			{ ...overrides, RUNNER_TEMP: path.join(directory, "tampered") },
			capture,
		);
		assert.notEqual(tampered.status, 0);
		assert.match(tampered.output, /Signature mismatch/u);
	},
);

for (const scenario of ["absent", "same-source-draft", ...producerRefusals.keys()]) {
	void test(`release producer: ${scenario}`, { skip: process.platform === "win32" }, async (t) => {
		const directory = await mkdtemp(path.join(tmpdir(), "release-producer-"));
		t.after(async () => rm(directory, { recursive: true, force: true }));
		const { archive, json, run, manifest } = await fixture(directory);
		// The job's own workspace: the verified evidence manifest, nothing signed yet.
		await mkdir(path.join(directory, "evidence"));
		await copyFile(
			path.join(archive, "manifest.json"),
			path.join(directory, "evidence", "manifest.json"),
		);
		await rm(path.join(directory, "chart.json"));
		if (scenario === "absent") {
			await json("state", null);
		}
		if (scenario === "published") {
			await json("state", {
				isDraft: false,
				isImmutable: true,
				isPrerelease: false,
				targetCommitish: commit,
			});
			await json("tags", { [release]: { type: "commit", sha: commit } });
		}
		if (scenario === "other-source-draft") {
			await json("state", {
				isDraft: true,
				isImmutable: false,
				isPrerelease: false,
				targetCommitish: later,
			});
		}
		const registryBefore = await readFile(path.join(archive, "chart-registry"), "utf8");
		const result = await run(
			{
				SHA: commit,
				ACTOR: "release-bot",
				GH_TOKEN: "github-token",
				REGISTRY_TOKEN: "registry-token",
				GITHUB_OUTPUT: path.join(directory, "outputs"),
				ASSET: `release-${release}.json`,
				JOINT: jointLock,
				CHART_PACKAGE: path.join(directory, "chart", chartPackage),
			},
			producer,
		);
		assert.doesNotMatch(
			result.calls,
			/registry-token/u,
			"the registry token travels on stdin only",
		);
		const reason = producerRefusals.get(scenario);
		if (reason !== undefined) {
			// The release-state guard runs before the push, so no chart version tag moves.
			assert.notEqual(result.status, 0);
			assert.match(result.output, reason);
			assert.doesNotMatch(result.calls, /"helm"/u);
			assert.equal(await readFile(path.join(archive, "chart-tags"), "utf8"), "{}");
			assert.equal(await readFile(path.join(archive, "chart-registry"), "utf8"), registryBefore);
			return;
		}
		assert.equal(result.status, 0, result.output);
		const calls = result.calls.trimEnd().split("\n");
		const push = calls.findIndex((call) => call.startsWith('["helm","push"'));
		assert.ok(
			push > 0 && calls.slice(0, push).some((call) => call.startsWith('["gh",')),
			"guard before push",
		);
		// One joint lock, and a schema 1 lock that is exactly its projection.
		const tags = asRecord(
			parseJson(await readFile(path.join(archive, "chart-tags"), "utf8")),
			"chart tags",
		);
		const joint = asRecord(
			parseJson(await readFile(path.join(directory, jointLock), "utf8")),
			"joint",
		);
		const legacy = asRecord(
			parseJson(await readFile(path.join(directory, `release-${release}.json`), "utf8")),
			"lock",
		);
		assert.deepEqual(joint.chart, {
			repository: chart.repository,
			version: chart.version,
			digest: tags[`${chart.repository}:${chart.version}`],
		});
		assert.deepEqual(Object.keys(legacy).toSorted(), [
			"commit",
			"images",
			"release",
			"schemaVersion",
		]);
		const { chart: _chart, ...projection } = joint;
		assert.equal(legacy.commit, commit);
		assert.deepEqual(legacy, { ...projection, schemaVersion: 1 });
		// The pushed package pins the evidence's index digests, the ones both locks name.
		const pushed = `${chart.repository}@${String(tags[`${chart.repository}:${chart.version}`])}`;
		const registry = asRecord(
			parseJson(await readFile(path.join(archive, "chart-registry"), "utf8")),
			"chart registry",
		);
		const packaged = Buffer.from(
			String(asRecord(registry[pushed], "pushed chart").bytes),
			"base64",
		).toString("utf8");
		assert.match(packaged, /^release:\n {2}version: "v0\.93\.0"\n {2}commit: "c{40}"\nimages:\n/mu);
		for (const subject of manifest.subjects) {
			if (
				["agent-pi", "application-server", "nats", "postgres", "webapp"].includes(subject.image)
			) {
				assert.ok(
					packaged.includes(`  ${subject.image}: "${subject.repository}@${subject.indexDigest}"\n`),
					subject.image,
				);
			}
		}
		// The chart digest is signed in the registry, after the push, with no bundle beside it.
		const signing = calls.indexOf(`["cosign","sign","--yes","${pushed}"]`);
		assert.ok(signing > push, "the pushed digest is signed");
		assert.deepEqual(parseJson(await readFile(path.join(archive, "chart-signatures"), "utf8")), [
			`${chart.repository}@${chart.digest}`,
			pushed,
		]);
		for (const file of [
			`release-${release}.json`,
			jointLock,
			...platformAssets.map((name) => `k3s-platform/${name}`),
		]) {
			const signature = asRecord(
				parseJson(await readFile(path.join(directory, `${file}.sigstore.json`), "utf8")),
				file,
			);
			assert.equal(typeof signature.sha256, "string", file);
		}
		// The archived v0.94.7 reader of a host one release behind: it accepts the schema 1 lock this
		// producer wrote, with the environment the current reader writes, and refuses the joint lock.
		// This proves the old parser and its CLI, not the old reconciler or a Docker host.
		const previous = (lock: string, output: string) =>
			spawnSync(
				process.execPath,
				[
					previousReader,
					path.join(directory, lock),
					path.join(directory, "evidence", "manifest.json"),
					release,
					path.join(directory, output),
				],
				{ encoding: "utf8", timeout: 30_000 },
			);
		const accepted = previous(`release-${release}.json`, "previous.env");
		assert.equal(accepted.status, 0, accepted.stderr);
		assert.equal(
			await readFile(path.join(directory, "previous.env"), "utf8"),
			await readFile(path.join(directory, "release-lock.env"), "utf8"),
		);
		const refused = previous(jointLock, "refused.env");
		assert.notEqual(refused.status, 0);
		assert.match(refused.stderr, /lock has missing or extra fields/u);
	});
}

void test(
	"capture verifies declared bundle coverage even with an older release label",
	{ skip: process.platform === "win32" },
	async (t) => {
		const directory = await mkdtemp(path.join(tmpdir(), "release-bundle-coverage-"));
		t.after(async () => rm(directory, { recursive: true, force: true }));
		const { archive, json } = await fixture(directory);
		const scanFile = "webapp-linux-amd64.trivy.json";
		const scan = asRecord(
			parseJson(await readFile(path.join(archive, scanFile), "utf8")),
			"Trivy report",
		);
		await json(scanFile, { ...scan, Results: [] });
		assert.throws(
			() => verifyReleaseEvidence(archive, "write-validation"),
			/Trivy omitted bundled npm/u,
		);
		await json(scanFile, scan);

		const file = "webapp-linux-amd64.syft.json";
		const native = asRecord(
			parseJson(await readFile(path.join(archive, file), "utf8")),
			"Syft inventory",
		);
		await json(file, { ...native, files: [] });
		assert.throws(() => verifyReleaseEvidence(archive, "write-validation"), /no bundled npm/u);
	},
);

void test(
	"publication refuses incomplete, legacy or mismatched k3s host evidence before publishing",
	{ skip: process.platform === "win32" },
	async (t) => {
		const directory = await mkdtemp(path.join(tmpdir(), "release-k3s-receipts-"));
		t.after(async () => rm(directory, { recursive: true, force: true }));
		const { smoke, run } = await fixture(directory);
		const file = path.join(smoke, "host-smoke-k3s-arm64.json");
		const bytes = await readFile(file);
		const valid = asRecord(parseJson(bytes.toString()), "receipt");
		const invalid = [
			{ ...valid, commit: "d".repeat(40) },
			{ ...valid, release: "v0.93.1" },
			{ ...valid, host: { ...asRecord(valid.host, "host"), architecture: "amd64" } },
			{ ...valid, checks: { ...asRecord(valid.checks, "checks"), isolation: false } },
			{ ...valid, workers: "2" },
			{
				schemaVersion: 1,
				release,
				commit,
				testedAt: valid.testedAt,
				host: { architecture: "arm64", os: "Ubuntu 24.04", dockerCompose: "2.24.4" },
				services: [{ state: "running", health: "healthy" }],
			},
		];
		for (const value of invalid) {
			await writeFile(file, JSON.stringify(value));
			const result = await run();
			assert.notEqual(result.status, 0);
			assert.doesNotMatch(result.calls, /"edit"|"upload"|"imagetools","create"/u);
		}
		await rm(file);
		const missing = await run();
		assert.notEqual(missing.status, 0);
		assert.doesNotMatch(missing.calls, /"edit"|"upload"|"imagetools","create"/u);
	},
);
