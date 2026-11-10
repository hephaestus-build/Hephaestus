/**
 * Writes the vulnerability policy's exceptions for the platform images that the one-host installer
 * ships: k3s and the images it bundles, cert-manager, Traefik and the agent-sandbox controller.
 *
 * An exception here accepts a fixable finding only because no upstream release ships the fix yet. So
 * each pin must be the newest stable release of its upstream: the script checks that first, and
 * refuses to write anything while a newer release exists. Bump that pin instead; its findings may go.
 *
 * Run `node scripts/scan-platform-images.ts --policy` first. This reads its policy results in
 * tmp/platform-policy, replaces every platform exception in security/vulnerability-policy.json, and
 * prints the summary that the change's review reads.
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
	/** The GitHub repository whose releases name the upstream versions. */
	repository: string;
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
			repository: "k3s-io/k3s",
			pinned: platformPins(platformSource).version,
			// k3s pins the images it bundles, so they move only with k3s.
			owns: (image) => image === "k3s" || image.startsWith("k3s-"),
		},
		{
			name: "cert-manager",
			repository: "cert-manager/cert-manager",
			pinned: chart("cert-manager").version,
			owns: (image) => image.startsWith("cert-manager-"),
		},
		{
			name: "Traefik chart",
			repository: "traefik/traefik-helm-chart",
			pinned: `v${chart("traefik").version.replace(/^v/u, "")}`,
			owns: () => false,
		},
		{
			name: "Traefik",
			repository: "traefik/traefik",
			pinned: traefikImage,
			owns: (image) => image === "traefik",
		},
		{
			name: "agent-sandbox",
			repository: "kubernetes-sigs/agent-sandbox",
			pinned: agentSandboxPin(agentSandboxSource).version,
			owns: (image) => image === "agent-sandbox-controller",
		},
	];
}

async function releasesOf(repository: string) {
	const headers: Record<string, string> = { accept: "application/vnd.github+json" };
	const token = process.env.GITHUB_TOKEN ?? process.env.GH_TOKEN;
	if (token !== undefined && token !== "") {
		headers.authorization = `Bearer ${token}`;
	}
	const response = await fetch(`https://api.github.com/repos/${repository}/releases?per_page=100`, {
		headers,
		signal: AbortSignal.timeout(30_000),
	});
	if (!response.ok) {
		throw new Error(`The releases of ${repository} are unavailable: HTTP ${response.status}`);
	}
	return asArray(parseJson(await response.text()), "releases").map((value) => {
		const release = asRecord(value, "release");
		return {
			tag: asString(release.tag_name, "release.tag_name"),
			draft: release.draft === true,
			prerelease: release.prerelease === true,
		};
	});
}

/** One refused finding of a policy result, with what its exception must carry. */
export interface Refusal {
	image: string;
	platform: string;
	digest: string;
	vulnerability: string;
	package: string;
	installedVersion: string;
	severity: string;
}

/** The refused findings of one policy result, with their severity from the scan report. */
export function refusalsOf(result: unknown, report: unknown): Refusal[] {
	const outcome = asRecord(result, "policy result");
	const image = asString(outcome.image, "image");
	const platform = asString(outcome.platform, "platform");
	const digest = asString(outcome.digest, "digest");
	const severities = new Map<string, string>();
	for (const entry of asArray(asRecord(report, "Trivy report").Results ?? [], "Results")) {
		for (const finding of isRecord(entry) && Array.isArray(entry.Vulnerabilities)
			? entry.Vulnerabilities
			: []) {
			const record = asRecord(finding, "vulnerability");
			severities.set(
				`${asString(record.VulnerabilityID, "id")}|${asString(record.PkgName, "package")}|${asString(record.InstalledVersion, "version")}`,
				asString(record.Severity, "severity"),
			);
		}
	}
	// The evaluator names a finding once for each binary that carries it; one exception covers them all.
	const rejected = new Set(
		asArray(outcome.rejected, "rejected").map((value) => asString(value, "fingerprint")),
	);
	return [...rejected].map((value) => {
		const [, vulnerability, pkg, installedVersion] = value.split("|");
		if (vulnerability === undefined || pkg === undefined || installedVersion === undefined) {
			throw new Error(`malformed policy fingerprint: ${value}`);
		}
		return {
			image,
			platform,
			digest,
			vulnerability,
			package: pkg,
			installedVersion,
			severity: severities.get(`${vulnerability}|${pkg}|${installedVersion}`) ?? "UNKNOWN",
		};
	});
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
		justification: `No upstream release ships the fix. On ${day}, ${checked} was the newest stable release of ${pin.repository}, and ${pin.pinned} is pinned. Retire this entry when the pin moves.`,
		evidence: `https://github.com/${pin.repository}/releases/tag/${encodeURIComponent(checked)}`,
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
		const newest = newestStable(await releasesOf(pin.repository));
		if (newest === undefined) {
			throw new Error(`${pin.repository} lists no stable release`);
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
	for (const platform of PLATFORMS) {
		const directory = policyDirectory(platform);
		const files = await readdir(directory);
		for (const file of files.filter((name) => name.endsWith(".policy.json"))) {
			const result = parseJson(await readFile(path.join(directory, file), "utf8"));
			const image = asString(asRecord(result, "policy result").image, "image");
			const report = parseJson(
				await readFile(path.join(directory, `${reportStem(image, platform)}.json`), "utf8"),
			);
			refusals.push(...refusalsOf(result, report));
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
	const platformImages = new Set(refusals.map((refusal) => refusal.image));
	const policy = asRecord(parseJson(await readFile(POLICY, "utf8")), "policy");
	const kept = asArray(policy.exceptions, "exceptions").filter(
		(entry) => !(isRecord(entry) && platformImages.has(String(entry.image))),
	);
	policy.exceptions = [
		...kept,
		...exceptions.toSorted((a, b) =>
			`${a.image}|${a.platform}|${a.vulnerability}|${a.package}`.localeCompare(
				`${b.image}|${b.platform}|${b.vulnerability}|${b.package}`,
			),
		),
	];
	await writeFile(POLICY, `${JSON.stringify(policy, null, "\t")}\n`);

	const count = (list: readonly Refusal[], severity: string) =>
		list.filter((refusal) => refusal.severity === severity).length;
	console.log("| Image | Exceptions | HIGH | CRITICAL |\n|---|---:|---:|---:|");
	for (const image of [...platformImages].toSorted()) {
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
