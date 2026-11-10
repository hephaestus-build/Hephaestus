import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import {
	blockingFindings,
	compareVersions,
	exceptionFor,
	mergeExceptions,
	newestStable,
	platformPinsOf,
} from "./write-platform-exceptions.ts";

const release = (tag: string, prerelease = false) => ({ tag, draft: false, prerelease });

await test("the newest stable release skips candidates, drafts and other tag shapes", () => {
	assert.equal(
		newestStable([
			release("v1.37.2+k3s1"),
			release("v1.37.3-rc1+k3s1", true),
			release("v1.36.9+k3s1"),
			release("v1.37.10+k3s1", true),
			{ tag: "v1.38.0+k3s1", draft: true, prerelease: false },
			release("230521-nf-execution"),
		]),
		"v1.37.2+k3s1",
	);
	assert.ok(compareVersions("v1.37.2+k3s2", "v1.37.2+k3s1") > 0);
	assert.ok(compareVersions("v1.37.10", "v1.37.9") > 0);
	assert.equal(compareVersions("42.3.1", "v42.3.1"), 0);
});

await test("each platform pin is read from the files that pin it", async () => {
	const pins = platformPinsOf(
		await readFile("deploy/platform/k3s-platform.yaml", "utf8"),
		await readFile("deploy/platform/k3s-agent-sandbox.yaml", "utf8"),
	);
	assert.deepEqual(
		pins.map((pin) => `${pin.source.kind}:${pin.source.repository}`),
		[
			"github:k3s-io/k3s",
			"github:cert-manager/cert-manager",
			"github:traefik/traefik-helm-chart",
			"dockerhub:library/traefik",
			"github:kubernetes-sigs/agent-sandbox",
		],
	);
	for (const pin of pins) {
		assert.match(pin.pinned, /^v\d+\.\d+\.\d+(?:\+k3s\d+)?$/u, pin.name);
	}
	const owner = (image: string) => pins.find((pin) => pin.owns(image))?.name;
	assert.equal(owner("k3s-klipper-helm"), "k3s");
	assert.equal(owner("cert-manager-webhook"), "cert-manager");
	assert.equal(owner("traefik"), "Traefik");
	assert.equal(owner("agent-sandbox-controller"), "agent-sandbox");
});

const digest = `sha256:${"a".repeat(64)}`;
const pin = {
	name: "k3s",
	source: { kind: "github" as const, repository: "k3s-io/k3s" },
	pinned: "v1.37.2+k3s1",
	owns: () => true,
};
const traefik = {
	name: "Traefik",
	source: { kind: "dockerhub" as const, repository: "library/traefik" },
	pinned: "v3.7.17",
	owns: () => true,
};
const finding = (id: string, severity: string, fixed?: string) => ({
	VulnerabilityID: id,
	PkgName: "golang.org/x/net",
	InstalledVersion: "v0.57.0",
	Severity: severity,
	...(fixed === undefined ? {} : { FixedVersion: fixed }),
});

await test("a blocking finding becomes one affected exception, bound to its digest, dated and checked", () => {
	const findings = blockingFindings(
		{ image: "k3s", platform: "linux/arm64", digest },
		{
			Results: [
				// Two binaries carry the same module version; a MEDIUM and an unfixed HIGH block nothing.
				{
					Vulnerabilities: [
						finding("CVE-2026-1", "HIGH", "0.62.0"),
						finding("CVE-2026-2", "MEDIUM", "0.62.0"),
					],
				},
				{
					Vulnerabilities: [finding("CVE-2026-1", "HIGH", "0.62.0"), finding("CVE-2026-3", "HIGH")],
				},
			],
		},
	);
	assert.deepEqual(
		findings.map((entry) => entry.vulnerability),
		["CVE-2026-1"],
	);
	const [blocking] = findings;
	assert.ok(blocking);
	assert.deepEqual(exceptionFor(blocking, pin, "v1.37.2+k3s1", new Date("2026-11-10T07:30:00Z")), {
		image: "k3s",
		platform: "linux/arm64",
		vulnerability: "CVE-2026-1",
		package: "golang.org/x/net",
		installedVersion: "v0.57.0",
		digest,
		status: "affected",
		justification:
			"No release of k3s-io/k3s newer than the pin exists: on 2026-11-10, v1.37.2+k3s1 was its newest stable release, and v1.37.2+k3s1 is pinned. Retire this entry when the pin moves.",
		evidence: "https://github.com/k3s-io/k3s/releases/tag/v1.37.2%2Bk3s1",
		owner: "FelixTJDietrich",
		expires: "2027-02-08T00:00:00Z",
	});
});

await test("a second run writes the same policy, and keeps the entries that it does not own", () => {
	const generated = (id: string) => ({
		...exceptionFor(
			{
				image: "traefik",
				platform: "linux/amd64",
				digest,
				vulnerability: id,
				package: "p",
				installedVersion: "1",
				severity: "HIGH",
			},
			traefik,
			"v3.7.17",
			new Date("2026-11-10T00:00:00Z"),
		),
	});
	// A reviewed decision about another image named traefik, from an earlier release.
	const reviewed = {
		image: "traefik",
		platform: "linux/amd64",
		vulnerability: "CVE-2026-9",
		package: "p",
		installedVersion: "1",
		status: "not_affected",
		evidence: "https://github.com/hephaestus-build/Hephaestus/pull/1",
	};
	const pins = [traefik];
	const first = mergeExceptions(
		[reviewed],
		[generated("CVE-2026-1"), generated("CVE-2026-9")],
		new Set(["traefik"]),
		pins,
	);
	assert.deepEqual(first, [reviewed, generated("CVE-2026-1")]);
	assert.deepEqual(
		mergeExceptions(
			first,
			[generated("CVE-2026-1"), generated("CVE-2026-9")],
			new Set(["traefik"]),
			pins,
		),
		first,
	);
});
