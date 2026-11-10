/**
 * Writes the vulnerability policy's exceptions for the platform images that the one-host installer
 * ships: k3s and the images it bundles, cert-manager, Traefik and the agent-sandbox controller.
 *
 * An exception here accepts a fixable finding only because no upstream release ships the fix yet. So
 * each pin must be the newest stable release of its upstream: the script checks that first, and
 * refuses to write anything while a newer release exists. Bump that pin instead; its findings may go.
 *
 * Run `node scripts/scan-platform-images.ts --policy` first. This reads its scan reports in
 * tmp/platform-policy, replaces the exceptions that it wrote before in
 * security/vulnerability-policy.json, and prints the summary that the change's review reads.
 */
import { readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

import { parseAllDocuments } from "yaml";

import { reportStem } from "./lib/image-scan.ts";
import { asArray, asRecord, asString, isRecord, parseJson } from "./lib/json.ts";
import { agentSandboxPin, platformPins, policyDirectory } from "./scan-platform-images.ts";
import { PLATFORMS } from "./scan-upstream-images.ts";

const POLICY = "security/vulnerability-policy.json";
const OWNER = "FelixTJDietrich";
const EXPIRY_DAYS = 90;

/** One platform pin and the upstream releases it follows. */
export interface Pin {
	name: string;
	/**
	 * Where the upstream publishes what the pin can move to: a GitHub repository's releases, or, for an
	 * image that appears in its registry later than its release, the registry's tags.
	 */
	source: { kind: "github" | "dockerhub"; repository: string };
	pinned: string;
	/** Whether an image of the policy results belongs to this pin. */
	owns: (image: string) => boolean;
}

/** A stable release version: `v1.2.3`, or k3s's `v1.2.3+k3s4`. Release candidates do not match. */
const STABLE = /^v?(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)(?:\+k3s(?<build>\d+))?$/u;

function versionParts(version: string): number[] | undefined {
	const groups = STABLE.exec(version)?.groups;
	if (groups === undefined) {
		return undefined;
	}
	return [groups.major, groups.minor, groups.patch, groups.build ?? "0"].map(Number);
}

/** Orders two stable versions; a version that is not stable sorts first. */
export function compareVersions(a: string, b: string): number {
	const left = versionParts(a) ?? [-1];
	const right = versionParts(b) ?? [-1];
	for (let index = 0; index < Math.max(left.length, right.length); index += 1) {
		const difference = (left[index] ?? 0) - (right[index] ?? 0);
		if (difference !== 0) {
			return difference;
		}
	}
	return 0;
}

/** The newest stable version among release tags; drafts and prereleases do not count. */
export function newestStable(
	releases: readonly { tag: string; draft: boolean; prerelease: boolean }[],
): string | undefined {
	return releases
		.filter((release) => !release.draft && !release.prerelease && STABLE.test(release.tag))
		.map((release) => release.tag)
		.toSorted(compareVersions)
		.at(-1);
}

/** The pins of the platform files, by the upstream that releases each. */
export function platformPinsOf(platformSource: string, agentSandboxSource: string): Pin[] {
	const charts = new Map<string, { version: string; values: string }>();
	for (const document of parseAllDocuments(platformSource)) {
		const spec = asRecord(asRecord(document.toJS() as unknown, "HelmChart").spec, "HelmChart.spec");
		charts.set(asString(spec.chart, "chart name"), {
			version: asString(spec.version, "chart version"),
			values: asString(spec.valuesContent, "chart values"),
		});
	}
	const chart = (name: string) => {
		const found = charts.get(name);
		if (found === undefined) {
			throw new Error(`The platform pins no ${name} chart`);
		}
		return found;
	};
	const traefikImage = /^\s+tag: (?<tag>v\d+\.\d+\.\d+)$/mu.exec(chart("traefik").values)?.groups
		?.tag;
	if (traefikImage === undefined) {
		throw new Error("The Traefik chart values pin no image tag");
	}
	return [
		{
			name: "k3s",
			source: { kind: "github", repository: "k3s-io/k3s" },
			pinned: platformPins(platformSource).version,
			// k3s pins the images it bundles, so they move only with k3s.
			owns: (image) => image === "k3s" || image.startsWith("k3s-"),
		},
		{
			name: "cert-manager",
			source: { kind: "github", repository: "cert-manager/cert-manager" },
			pinned: chart("cert-manager").version,
			owns: (image) => image.startsWith("cert-manager-"),
		},
		{
			name: "Traefik chart",
			source: { kind: "github", repository: "traefik/traefik-helm-chart" },
			pinned: `v${chart("traefik").version.replace(/^v/u, "")}`,
			owns: () => false,
		},
		{
			name: "Traefik",
			// The official image appears on Docker Hub hours after the GitHub release.
			source: { kind: "dockerhub", repository: "library/traefik" },
			pinned: traefikImage,
			owns: (image) => image === "traefik",
		},
		{
			name: "agent-sandbox",
			source: { kind: "github", repository: "kubernetes-sigs/agent-sandbox" },
			pinned: agentSandboxPin(agentSandboxSource).version,
			owns: (image) => image === "agent-sandbox-controller",
		},
	];
}

async function getJson(url: string, headers: Record<string, string> = {}): Promise<unknown> {
	const response = await fetch(url, { headers, signal: AbortSignal.timeout(30_000) });
	if (!response.ok) {
		throw new Error(`${url} is unavailable: HTTP ${response.status}`);
	}
	return parseJson(await response.text());
}

async function releasesOf(source: Pin["source"]) {
	if (source.kind === "dockerhub") {
		// Newest first: the newest stable version is on the first page.
		const page = await getJson(
			`https://hub.docker.com/v2/repositories/${source.repository}/tags?page_size=100&ordering=last_updated`,
		);
		return asArray(asRecord(page, "tags").results, "results").map((value) => ({
			tag: asString(asRecord(value, "tag").name, "tag.name"),
			draft: false,
			prerelease: false,
		}));
	}
	const headers: Record<string, string> = { accept: "application/vnd.github+json" };
	const token = process.env.GITHUB_TOKEN ?? process.env.GH_TOKEN;
	if (token !== undefined && token !== "") {
		headers.authorization = `Bearer ${token}`;
	}
	const releases = await getJson(
		`https://api.github.com/repos/${source.repository}/releases?per_page=100`,
		headers,
	);
	return asArray(releases, "releases").map((value) => {
		const release = asRecord(value, "release");
		return {
			tag: asString(release.tag_name, "release.tag_name"),
			draft: release.draft === true,
			prerelease: release.prerelease === true,
		};
	});
}

/** Where a version of the pin's upstream is published: the evidence of its exceptions. */
export function releaseUrl(pin: Pin, version: string): string {
	return pin.source.kind === "github"
		? `https://github.com/${pin.source.repository}/releases/tag/${encodeURIComponent(version)}`
		: `https://hub.docker.com/v2/repositories/${pin.source.repository}/tags/${encodeURIComponent(version)}`;
}

/** One finding that blocks a release unless an exception accepts it. */
export interface Refusal {
	image: string;
	platform: string;
	digest: string;
	vulnerability: string;
	package: string;
	installedVersion: string;
	severity: string;
}

/**
 * Every finding of one scan that blocks a release without an exception: HIGH or CRITICAL, with a fix
 * that upstream has published. Read from the report, not from the evaluator's refusals, which leave
 * out the findings that an exception already accepts: a second run writes the same entries.
 */
export function blockingFindings(
	subject: { image: string; platform: string; digest: string },
	report: unknown,
): Refusal[] {
	const findings = new Map<string, Refusal>();
	for (const entry of asArray(asRecord(report, "Trivy report").Results ?? [], "Results")) {
		for (const value of isRecord(entry) && Array.isArray(entry.Vulnerabilities)
			? entry.Vulnerabilities
			: []) {
			const record = asRecord(value, "vulnerability");
			const severity = asString(record.Severity, "severity");
			const fixed = typeof record.FixedVersion === "string" && record.FixedVersion.trim() !== "";
			if (!fixed || (severity !== "HIGH" && severity !== "CRITICAL")) {
				continue;
			}
			const finding = {
				...subject,
				vulnerability: asString(record.VulnerabilityID, "id"),
				package: asString(record.PkgName, "package"),
				installedVersion: asString(record.InstalledVersion, "version"),
				severity,
			};
			// A module that several binaries carry is one finding: one exception covers them all.
			findings.set(
				`${finding.vulnerability}|${finding.package}|${finding.installedVersion}`,
				finding,
			);
		}
	}
	return [...findings.values()];
}

const exceptionKey = (entry: Record<string, unknown>) =>
	[entry.image, entry.platform, entry.vulnerability, entry.package, entry.installedVersion].join(
		"|",
	);

/**
 * The policy's exceptions with this generator's own entries replaced: `affected` entries for a
 * platform image whose evidence is a release of a platform pin. Every other entry stays, such as a
 * reviewed `not_affected` decision, or an entry for another image of the same name. A finding that
 * such an entry already accepts gets no second one.
 */
export function mergeExceptions(
	existing: readonly unknown[],
	generated: readonly Record<string, unknown>[],
	platformImages: ReadonlySet<string>,
	pins: readonly Pin[],
): unknown[] {
	const owned = (entry: unknown) =>
		isRecord(entry) &&
		entry.status === "affected" &&
		platformImages.has(String(entry.image)) &&
		pins.some((pin) => String(entry.evidence).startsWith(releaseUrl(pin, "")));
	const kept = existing.filter((entry) => !owned(entry));
	const accepted = new Set(kept.filter(isRecord).map(exceptionKey));
	return [
		...kept,
		...generated
			.filter((entry) => !accepted.has(exceptionKey(entry)))
			.toSorted((a, b) => exceptionKey(a).localeCompare(exceptionKey(b))),
	];
}

/** The exception that accepts one refused finding while its pin is the newest upstream release. */
export function exceptionFor(refusal: Refusal, pin: Pin, checked: string, today: Date) {
	const expires = new Date(
		Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate() + EXPIRY_DAYS),
	);
	const day = today.toISOString().slice(0, 10);
	return {
		image: refusal.image,
		platform: refusal.platform,
		vulnerability: refusal.vulnerability,
		package: refusal.package,
		installedVersion: refusal.installedVersion,
		digest: refusal.digest,
		status: "affected",
		justification: `No release of ${pin.source.repository} newer than the pin exists: on ${day}, ${checked} was its newest stable release, and ${pin.pinned} is pinned. Retire this entry when the pin moves.`,
		evidence: releaseUrl(pin, checked),
		owner: OWNER,
		expires: expires.toISOString().replace(".000Z", "Z"),
	};
}

if (import.meta.main) {
	const pins = platformPinsOf(
		await readFile("deploy/platform/k3s-platform.yaml", "utf8"),
		await readFile("deploy/platform/k3s-agent-sandbox.yaml", "utf8"),
	);
	const checked = new Map<string, string>();
	const stale: string[] = [];
	for (const pin of pins) {
		const newest = newestStable(await releasesOf(pin.source));
		if (newest === undefined) {
			throw new Error(`${pin.source.repository} lists no stable release`);
		}
		if (compareVersions(newest, pin.pinned) > 0) {
			stale.push(`${pin.name}: pinned ${pin.pinned}, newest ${newest}`);
		}
		checked.set(pin.name, newest);
	}
	if (stale.length > 0) {
		throw new Error(
			`Bump these platform pins before excepting their findings:\n${stale.join("\n")}`,
		);
	}

	const refusals: Refusal[] = [];
	const platformImages = new Set<string>();
	for (const platform of PLATFORMS) {
		const directory = policyDirectory(platform);
		const files = await readdir(directory);
		for (const file of files.filter((name) => name.endsWith(".policy.json"))) {
			const result = asRecord(
				parseJson(await readFile(path.join(directory, file), "utf8")),
				"policy result",
			);
			const image = asString(result.image, "image");
			platformImages.add(image);
			const report = parseJson(
				await readFile(path.join(directory, `${reportStem(image, platform)}.json`), "utf8"),
			);
			refusals.push(
				...blockingFindings({ image, platform, digest: asString(result.digest, "digest") }, report),
			);
		}
	}

	const today = new Date();
	const exceptions = refusals.map((refusal) => {
		const pin = pins.find((candidate) => candidate.owns(refusal.image));
		if (pin === undefined) {
			throw new Error(`No platform pin owns ${refusal.image}`);
		}
		return exceptionFor(refusal, pin, checked.get(pin.name) ?? pin.pinned, today);
	});
	const policy = asRecord(parseJson(await readFile(POLICY, "utf8")), "policy");
	policy.exceptions = mergeExceptions(
		asArray(policy.exceptions, "exceptions"),
		exceptions,
		platformImages,
		pins,
	);
	await writeFile(POLICY, `${JSON.stringify(policy, null, "\t")}\n`);

	const count = (list: readonly Refusal[], severity: string) =>
		list.filter((refusal) => refusal.severity === severity).length;
	console.log("| Image | Exceptions | HIGH | CRITICAL |\n|---|---:|---:|---:|");
	for (const image of [...new Set(refusals.map((refusal) => refusal.image))].toSorted()) {
		const own = refusals.filter((refusal) => refusal.image === image);
		console.log(`| ${image} | ${own.length} | ${count(own, "HIGH")} | ${count(own, "CRITICAL")} |`);
	}
	const day = today.toISOString().slice(0, 10);
	console.log(
		`\n| Pin | Pinned | Newest stable on ${day} | HIGH | CRITICAL |\n|---|---|---|---:|---:|`,
	);
	for (const pin of pins) {
		const own = refusals.filter((refusal) => pin.owns(refusal.image));
		console.log(
			`| ${pin.name} | ${pin.pinned} | ${checked.get(pin.name) ?? pin.pinned} | ${count(own, "HIGH")} | ${count(own, "CRITICAL")} |`,
		);
	}
}
