import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { parse, parseAllDocuments } from "yaml";

import { gvisorPin } from "./lib/gvisor-pin.ts";
import {
	isImageIndex,
	manifestDigest,
	resolvePlatformDigest,
	scanAll,
	type Subject,
} from "./lib/image-scan.ts";
import { asArray, asRecord, asString, isRecord, parseJson } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";
import { PLATFORMS } from "./scan-upstream-images.ts";

export function platformPins(source: string) {
	const pin = /^# pin: k3s (?<version>v\d+\.\d+\.\d+\+k3s\d+) (?<checksum>[a-f0-9]{64})$/mu.exec(
		source,
	)?.groups;
	if (pin?.version === undefined || pin.checksum === undefined) {
		throw new Error("The platform must pin k3s and its installer checksum");
	}
	const subjects: Subject[] = [];
	function images(value: unknown): void {
		if (!isRecord(value)) {
			return;
		}
		for (const [key, child] of Object.entries(value)) {
			if (key !== "image") {
				images(child);
				continue;
			}
			const image = asRecord(child, "platform image");
			const nameInRegistry = asString(image.repository, "image.repository");
			const repository =
				image.registry === undefined
					? nameInRegistry
					: `${asString(image.registry, "image.registry")}/${nameInRegistry}`;
			const digest = asString(image.digest, "image.digest");
			asString(image.tag, "image.tag");
			if (!/^[a-z0-9./-]+$/u.test(repository) || !/^sha256:[a-f0-9]{64}$/u.test(digest)) {
				throw new Error("The platform image must have a repository and an immutable digest");
			}
			const name = repository.split("/").at(-1);
			if (name === undefined || name === "") {
				throw new Error("The platform image has no name");
			}
			subjects.push({ image: name, repository, reference: `${repository}@${digest}` });
		}
	}
	for (const document of parseAllDocuments(source)) {
		const resource: unknown = document.toJS();
		if (document.errors.length > 0) {
			throw new Error("Invalid platform YAML");
		}
		const spec = asRecord(asRecord(resource, "HelmChart").spec, "HelmChart.spec");
		images(parse(asString(spec.valuesContent, "HelmChart.spec.valuesContent")) as unknown);
	}
	if (subjects.length === 0) {
		throw new Error("The platform has no pinned images");
	}
	const repository = "docker.io/rancher/k3s";
	subjects.push({
		image: "k3s",
		repository,
		reference: `${repository}:${pin.version.replace("+", "-")}`,
	});
	return { version: pin.version, installerSha256: pin.checksum, subjects };
}

/** Both pinned archive checksums are the ones in the release's own `SHA512SUMS`. */
export function verifyGvisorSums(sums: string, template: string): void {
	const { sha512 } = gvisorPin(template);
	const published = new Map(
		sums
			.split("\n")
			.map((line) => line.trim().split(/\s+/u))
			.filter((fields) => fields.length === 2)
			.map(([sum, file]) => [file, sum]),
	);
	if (
		published.get("gvisor-x86_64.tar.bz2") !== sha512.x64 ||
		published.get("gvisor-aarch64.tar.bz2") !== sha512.arm64
	) {
		throw new Error("The pinned gVisor checksums are not those of its release");
	}
}

/** The agent-sandbox release that k3s-agent-sandbox.yaml carries, and its controller image. */
export function agentSandboxPin(source: string) {
	const pin = /^# pin: agent-sandbox (?<version>v\d+\.\d+\.\d+) (?<checksum>[a-f0-9]{64})$/mu.exec(
		source,
	)?.groups;
	if (pin?.version === undefined || pin.checksum === undefined) {
		throw new Error("The platform must pin agent-sandbox and its release manifest checksum");
	}
	const [reference, ...others] = renderedSubjects(source);
	if (reference === undefined || others.length > 0) {
		throw new Error("The agent-sandbox manifest must run exactly one image");
	}
	const repository = reference.split("@")[0] ?? "";
	const subject: Subject = { image: "agent-sandbox-controller", repository, reference };
	return { version: pin.version, manifestSha256: pin.checksum, subject };
}

/**
 * The manifest without the changes that k3s-agent-sandbox.yaml makes to the controller's Deployment
 * (docs/contributor/release-management.mdx § Platform pins lists them). Anything else must be upstream's.
 */
export function withoutPlatformChanges(source: string): unknown[] {
	return parseAllDocuments(source).map((document) => {
		if (document.errors.length > 0) {
			throw new Error("Invalid agent-sandbox YAML");
		}
		const resource = document.toJS() as unknown;
		if (!isRecord(resource) || resource.kind !== "Deployment") {
			return resource;
		}
		const pod = asRecord(
			asRecord(asRecord(resource.spec, "spec").template, "template").spec,
			"pod",
		);
		delete pod.priorityClassName;
		delete pod.securityContext;
		for (const container of asArray(pod.containers, "containers")) {
			const entry = asRecord(container, "container");
			delete entry.resources;
			delete entry.securityContext;
			entry.image = asString(entry.image, "image").replace(/@sha256:[a-f0-9]{64}$/u, "");
		}
		return resource;
	});
}

/** The release manifest matches its pin, and the platform's copy differs from it only as stated. */
export function verifyAgentSandbox(upstream: Uint8Array, source: string): void {
	const { manifestSha256 } = agentSandboxPin(source);
	if (createHash("sha256").update(upstream).digest("hex") !== manifestSha256) {
		throw new Error("The pinned agent-sandbox manifest checksum does not match its release");
	}
	// assert's own message names the first field that differs.
	assert.deepEqual(
		withoutPlatformChanges(source),
		withoutPlatformChanges(Buffer.from(upstream).toString("utf8")),
	);
}

/** The pinned digest is the multi-platform index that the release tag names, not one platform's manifest. */
export function verifyIndexDigest(raw: unknown, resolved: string, pinned: string): void {
	if (!isImageIndex(raw)) {
		throw new Error("The agent-sandbox release tag names no multi-platform image index");
	}
	if (resolved !== pinned) {
		throw new Error(
			"The pinned agent-sandbox controller digest is not the index of its release tag",
		);
	}
}

export function verifyInstaller(installer: Uint8Array, expected: string): void {
	if (createHash("sha256").update(installer).digest("hex") !== expected) {
		throw new Error("The pinned k3s installer checksum does not match its release");
	}
}

/** GitHub requires a stable, distinct category for each SARIF subject in one workflow. */
export function sarifForCategory(value: unknown, category: string) {
	const sarif = asRecord(value, "SARIF");
	const runs = asArray(sarif.runs, "SARIF.runs");
	if (runs.length === 0) {
		throw new Error("The scanner produced no SARIF runs");
	}
	return {
		...sarif,
		runs: runs.map((entry, index) => ({
			...asRecord(entry, "SARIF run"),
			automationDetails: { id: `scheduled-${category}/${index}/` },
		})),
	};
}

async function scanSarif(args: string[], file: string, category: string) {
	await run("trivy", [
		...args,
		"--quiet",
		"--skip-db-update",
		"--scanners",
		"vuln",
		"--format",
		"sarif",
		"--output",
		file,
	]);
	await writeFile(
		file,
		JSON.stringify(sarifForCategory(parseJson(await readFile(file, "utf8")), category)),
	);
}

export function bundledSubjects(source: string): Subject[] {
	const lines = source.trim().split(/\r?\n/u);
	if (lines.length === 0 || lines.length > 64) {
		throw new Error("Invalid k3s image inventory");
	}
	return lines.map((reference) => {
		const match =
			/^(?<repository>docker\.io\/rancher\/(?<name>[a-z0-9.-]+)):[A-Za-z0-9._+-]+$/u.exec(
				reference,
			)?.groups;
		if (match?.repository === undefined || match.name === undefined) {
			throw new Error("Invalid k3s image reference");
		}
		return { image: `k3s-${match.name}`, reference, repository: match.repository };
	});
}

/** The images of each packaged k3s component that `disable` in k3s-config.yaml can turn off. */
const PACKAGED_COMPONENT_IMAGES: Record<string, readonly string[]> = {
	coredns: ["mirrored-coredns-coredns"],
	"local-storage": ["local-path-provisioner", "mirrored-library-busybox"],
	"metrics-server": ["mirrored-metrics-server"],
	servicelb: ["klipper-lb"],
	traefik: ["mirrored-library-traefik"],
};

/** The bundled images that the host runs: k3s-config.yaml disables the others, so they are never pulled. */
export function runningBundledSubjects(bundled: Subject[], config: string): Subject[] {
	const disable = asArray(asRecord(parse(config) as unknown, "k3s config").disable, "disable");
	const skipped = new Set(
		disable.flatMap((entry) => {
			const images = PACKAGED_COMPONENT_IMAGES[asString(entry, "disabled component")];
			if (images === undefined) {
				throw new Error("k3s-config.yaml disables a component that the rescan does not know");
			}
			return images;
		}),
	);
	return bundled.filter((subject) => !skipped.has(subject.repository.split("/").at(-1) ?? ""));
}

async function releaseText(url: string): Promise<string> {
	const response = await fetch(url, { signal: AbortSignal.timeout(30_000) });
	if (!response.ok) {
		throw new Error(`Platform asset download failed: HTTP ${response.status}`);
	}
	return response.text();
}

/** Every rendered Pod image and controller image argument must be immutable. */
export function renderedSubjects(source: string): string[] {
	const references = new Set<string>();
	function remember(reference: string) {
		const match =
			/^(?<repository>[a-z0-9./-]+)(?::[A-Za-z0-9._+-]+)?@(?<digest>sha256:[a-f0-9]{64})$/u.exec(
				reference,
			)?.groups;
		if (match?.repository === undefined || match.digest === undefined) {
			throw new Error("The platform chart renders an image without an immutable digest");
		}
		references.add(`${match.repository}@${match.digest}`);
	}
	function visit(value: unknown): void {
		if (typeof value === "string") {
			const argument = /^--[\w-]*image=(?<image>\S+)$/u.exec(value)?.groups?.image;
			if (argument !== undefined) {
				remember(argument);
			}
		} else if (Array.isArray(value)) {
			for (const item of asArray(value, "rendered list")) {
				visit(item);
			}
		} else if (isRecord(value)) {
			for (const [key, child] of Object.entries(value)) {
				if (
					["containers", "initContainers", "ephemeralContainers"].includes(key) &&
					Array.isArray(child)
				) {
					for (const container of asArray(child, "containers")) {
						remember(asString(asRecord(container, "container").image, "container.image"));
					}
				}
				visit(child);
			}
		}
	}
	for (const document of parseAllDocuments(source)) {
		visit(document.toJS() as unknown);
	}
	return [...references];
}

/** Network proof used only for platform-pin pull requests and scheduled rescans. */
export async function checkPlatformPins(
	source: string,
	agentSandbox: string,
	containerd: string,
): Promise<void> {
	verifyGvisorSums(
		await releaseText(
			`https://github.com/google/gvisor/releases/download/release-${gvisorPin(containerd).release}/SHA512SUMS`,
		),
		containerd,
	);
	const { version, subject: controller } = agentSandboxPin(agentSandbox);
	const tagged = `${controller.repository}:${version}`;
	const signal = AbortSignal.timeout(120_000);
	verifyIndexDigest(
		parseJson(
			await output("docker", ["buildx", "imagetools", "inspect", tagged, "--raw"], { signal }),
		),
		await manifestDigest(tagged, signal),
		controller.reference.split("@")[1] ?? "",
	);
	verifyAgentSandbox(
		Buffer.from(
			await releaseText(
				`https://github.com/kubernetes-sigs/agent-sandbox/releases/download/${encodeURIComponent(version)}/sandbox-with-extensions.yaml`,
			),
		),
		agentSandbox,
	);
	const pins = platformPins(source);
	verifyInstaller(
		Buffer.from(
			await releaseText(
				`https://raw.githubusercontent.com/k3s-io/k3s/${encodeURIComponent(pins.version)}/install.sh`,
			),
		),
		pins.installerSha256,
	);
	const directory = await mkdtemp(path.join(tmpdir(), "platform-pins-"));
	try {
		const rendered: string[] = [];
		for (const document of parseAllDocuments(source)) {
			const chart = asRecord(document.toJS() as unknown, "HelmChart");
			const spec = asRecord(chart.spec, "HelmChart.spec");
			const name = asString(spec.chart, "chart name");
			const file = path.join(directory, "values.yaml");
			await writeFile(file, asString(spec.valuesContent, "chart values"));
			const text = await output(
				"helm",
				[
					"template",
					name,
					name,
					"--repo",
					asString(spec.repo, "chart repo"),
					"--version",
					asString(spec.version, "chart version"),
					"--values",
					file,
				],
				{
					env: {
						HELM_REPOSITORY_CONFIG: path.join(directory, "repositories.yaml"),
						HELM_REPOSITORY_CACHE: path.join(directory, "repository"),
					},
					signal: AbortSignal.timeout(120_000),
				},
			);
			rendered.push(...renderedSubjects(text));
		}
		const expected = pins.subjects
			.filter((subject) => subject.image !== "k3s")
			.map((subject) => subject.reference);
		const actual = new Set(rendered);
		if (actual.size !== expected.length || expected.some((reference) => !actual.has(reference))) {
			throw new Error("The platform charts and rescan must select the same immutable images");
		}
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

/** The platform pins on disk, verified against their releases, and the images they run on a host. */
export async function verifiedPlatformSubjects(): Promise<{
	pins: ReturnType<typeof platformPins>;
	agentSandbox: ReturnType<typeof agentSandboxPin>;
	subjects: Subject[];
}> {
	const source = await readFile("deploy/platform/k3s-platform.yaml", "utf8");
	const agentSandboxSource = await readFile("deploy/platform/k3s-agent-sandbox.yaml", "utf8");
	const containerd = await readFile("deploy/platform/k3s-containerd.toml.tmpl", "utf8");
	await checkPlatformPins(source, agentSandboxSource, containerd);
	const pins = platformPins(source);
	const agentSandbox = agentSandboxPin(agentSandboxSource);
	// The complete upstream list less the add-ons that the host disables. A config change that turns one on scans it again.
	const bundled = runningBundledSubjects(
		bundledSubjects(
			await releaseText(
				`https://github.com/k3s-io/k3s/releases/download/${encodeURIComponent(pins.version)}/k3s-images.txt`,
			),
		),
		await readFile("deploy/platform/k3s-config.yaml", "utf8"),
	);
	return { pins, agentSandbox, subjects: [...pins.subjects, agentSandbox.subject, ...bundled] };
}

/** The directory of the policy reports of one platform. */
export const policyDirectory = (platform: string) =>
	path.join("tmp/platform-policy", platform.replace("/", "-"));

/**
 * The release vulnerability policy on both platforms of every platform image. The one-host installer
 * ships these images, so a fixable HIGH or CRITICAL passes only through a named exception
 * (scripts/write-platform-exceptions.ts).
 */
async function enforcePlatformPolicy(subjects: readonly Subject[]): Promise<string[]> {
	const refused: string[] = [];
	for (const platform of PLATFORMS) {
		const outcomes = await scanAll(subjects, policyDirectory(platform), {
			platform,
			annotate: true,
			continueOnError: true,
		});
		refused.push(
			...outcomes
				.filter((outcome) => !outcome.passed)
				.map((outcome) => `${outcome.image} (${outcome.platform})`),
		);
	}
	return refused;
}

if (import.meta.main) {
	const mode = process.argv[2];
	if (mode !== undefined && mode !== "--check-pins" && mode !== "--policy") {
		throw new Error("Usage: scan-platform-images.ts [--check-pins | --policy]");
	}
	const { subjects } = await verifiedPlatformSubjects();
	if (mode === "--check-pins") {
		process.exit(0);
	}
	if (mode === "--policy") {
		const refused = await enforcePlatformPolicy(subjects);
		if (refused.length > 0) {
			throw new Error(
				`Platform images do not satisfy the vulnerability policy: ${refused.join(", ")}`,
			);
		}
		process.exit(0);
	}
	// One SARIF upload has at most 20 runs. Each architecture has its own upload, plus dependencies on amd64.
	if (subjects.length >= 20) {
		throw new Error("The platform inventory exceeds one SARIF upload per architecture");
	}
	const failures: string[] = [];
	for (const platform of PLATFORMS) {
		const directory = path.join("tmp/platform-rescan", platform.replace("/", "-"));
		await mkdir(directory, { recursive: true });
		if (platform === "linux/amd64") {
			try {
				await scanSarif(
					[
						"fs",
						".",
						"--skip-dirs",
						"**/node_modules",
						"--skip-dirs",
						".git",
						"--skip-dirs",
						"tmp",
					],
					path.join(directory, "dependencies.sarif"),
					"dependencies",
				);
			} catch {
				failures.push("dependency locks");
			}
		}
		for (const subject of subjects) {
			const category = `${subject.image}-${platform.replace("/", "-")}`;
			try {
				const digest = await resolvePlatformDigest(subject.reference, platform);
				await scanSarif(
					["image", `${subject.repository}@${digest}`],
					path.join(directory, `${category}.sarif`),
					category,
				);
			} catch {
				failures.push(category);
			}
		}
	}
	if (failures.length > 0) {
		throw new Error(`Scans unavailable: ${failures.join(", ")}`);
	}
	const refused = await enforcePlatformPolicy(subjects);
	if (refused.length > 0) {
		throw new Error(
			`Platform images do not satisfy the vulnerability policy: ${refused.join(", ")}`,
		);
	}
}
