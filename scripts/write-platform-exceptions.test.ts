import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { isRecord, parseJson } from "./lib/json.ts";
import {
	blockingFindings,
	compareVersions,
	exceptionFor,
	mergeExceptions,
	newestStable,
	platformPinsOf,
	platformFindings,
	releasesOf,
} from "./write-platform-exceptions.ts";

const release = (tag: string, prerelease = false) => ({ tag, draft: false, prerelease });

await test("Docker Hub registry checks every Link page using its anonymous pull token", async (t) => {
	const requested: string[] = [];
	t.mock.method(globalThis, "fetch", async (url: string, options: RequestInit) => {
		if (new URL(url).hostname === "auth.docker.io") {
			assert.equal(new URL(url).searchParams.get("scope"), "repository:library/traefik:pull");
			return Response.json({ token: "fixture-token" });
		}
		requested.push(url);
		assert.equal(new Headers(options.headers).get("authorization"), "Bearer fixture-token");
		const second = new URL(url).searchParams.has("last");
		return Response.json(
			{ name: "library/traefik", tags: second ? ["3.7.18", "3.8.0-rc1"] : ["3.6.9", "3.7.17"] },
			{
				headers: second
					? {}
					: { link: '</v2/library/traefik/tags/list?n=1000&last=3.7.17>; rel="next"' },
			},
		);
	});
	assert.equal(
		newestStable(await releasesOf({ kind: "dockerhub", repository: "library/traefik" })),
		"3.7.18",
	);
	assert.equal(requested.length, 2);
});

await test("a rate-limited registry page fails closed without retrying or accepting a partial list", async (t) => {
	let calls = 0;
	t.mock.method(globalThis, "fetch", async (url: string) => {
		if (new URL(url).hostname === "auth.docker.io") {
			return Response.json({ token: "fixture-token" });
		}
		calls += 1;
		return new Response(null, { status: 429, headers: { "retry-after": "60" } });
	});
	await assert.rejects(
		releasesOf({ kind: "dockerhub", repository: "library/traefik" }),
		/rate limited; Retry-After: 60/u,
	);
	assert.equal(calls, 1);
});

await test("registry pagination rejects cycles and another origin before sending its token", async (t) => {
	for (const next of ["?n=1000", "https://other.example/tags"]) {
		let calls = 0;
		t.mock.method(globalThis, "fetch", async (url: string) => {
			if (new URL(url).hostname === "auth.docker.io") {
				return Response.json({ token: "fixture-token" });
			}
			calls += 1;
			return Response.json(
				{ name: "library/traefik", tags: [] },
				{ headers: { link: `<${next}>; rel="next"` } },
			);
		});
		await assert.rejects(
			releasesOf({ kind: "dockerhub", repository: "library/traefik" }),
			/Invalid Docker Hub tag pagination/u,
		);
		assert.equal(calls, 1);
	}
});

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
	assert.equal(owner("platform-traefik"), "Traefik");
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
				image: "platform-traefik",
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
	// A reviewed decision about the same scanned platform digest remains authoritative.
	const reviewed = {
		image: "platform-traefik",
		platform: "linux/amd64",
		vulnerability: "CVE-2026-9",
		package: "p",
		installedVersion: "1",
		digest,
		status: "not_affected",
		evidence: "https://github.com/hephaestus-build/Hephaestus/pull/1",
	};
	const pins = [traefik];
	const first = mergeExceptions(
		[reviewed],
		[generated("CVE-2026-1"), generated("CVE-2026-9")],
		pins,
	);
	assert.deepEqual(first, [reviewed, generated("CVE-2026-1")]);
	assert.deepEqual(
		mergeExceptions(first, [generated("CVE-2026-1"), generated("CVE-2026-9")], pins),
		first,
	);
});

await test("a rebuilt image replaces a kept exception's old digest without a duplicate key", () => {
	const current = exceptionFor(
		{
			image: "platform-traefik",
			platform: "linux/amd64",
			digest,
			vulnerability: "CVE-2026-9",
			package: "p",
			installedVersion: "1",
			severity: "HIGH",
		},
		traefik,
		"3.7.18",
		new Date("2026-11-12T00:00:00Z"),
	);
	const stale = {
		...current,
		status: "not_affected",
		digest: `sha256:${"b".repeat(64)}`,
		evidence: "https://github.com/hephaestus-build/Hephaestus/pull/1",
	};
	const merged = mergeExceptions([stale], [current], [traefik]);
	assert.deepEqual(merged, [current]);
	assert.deepEqual(mergeExceptions(merged, [current], [traefik]), merged);
});

await test("platform Traefik findings cannot replace reviewed Compose release exceptions", async () => {
	const policy = parseJson(await readFile("security/vulnerability-policy.json", "utf8"));
	assert.ok(isRecord(policy) && Array.isArray(policy.exceptions));
	const historical: unknown = policy.exceptions.find(
		(entry) => isRecord(entry) && entry.image === "traefik",
	);
	assert.ok(isRecord(historical));
	const current = { ...historical, image: "platform-traefik", digest, status: "affected" };
	assert.deepEqual(mergeExceptions([historical], [current], [traefik]), [historical, current]);
});

await test("retired generated platform entries are removed while reviewed decisions stay", () => {
	const retired = exceptionFor(
		{
			image: "k3s-retired",
			platform: "linux/amd64",
			digest,
			vulnerability: "CVE-2026-1",
			package: "p",
			installedVersion: "1",
			severity: "HIGH",
		},
		pin,
		pin.pinned,
		new Date("2026-11-12T00:00:00Z"),
	);
	const reviewed = {
		...retired,
		status: "not_affected",
		evidence: "https://github.com/hephaestus-build/Hephaestus/pull/1",
	};
	assert.deepEqual(mergeExceptions([retired, reviewed], [], [pin]), [reviewed]);
});

await test("exception generation requires every current subject and rejects stale or erroneous scans", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "platform-findings-"));
	const subject = {
		image: "platform-traefik",
		platform: "linux/amd64",
		repository: "traefik",
		reference: `traefik@${digest}`,
		digest,
	};
	const resultFile = path.join(directory, "platform-traefik-linux-amd64.policy.json");
	const result = { ...subject, errors: [], rejected: ["an expected policy refusal"] };
	try {
		await writeFile(resultFile, JSON.stringify(result));
		await writeFile(
			path.join(directory, "platform-traefik-linux-amd64.json"),
			JSON.stringify({
				ArtifactName: subject.reference,
				Results: [{ Vulnerabilities: [finding("CVE-2026-1", "HIGH", "0.62.0")] }],
			}),
		);
		const findings = await platformFindings([subject], directory);
		assert.equal(findings.length, 1);
		await assert.rejects(
			platformFindings([subject, { ...subject, image: "k3s" }], directory),
			/ENOENT/u,
		);
		await writeFile(resultFile, JSON.stringify({ ...result, digest: `sha256:${"b".repeat(64)}` }));
		await assert.rejects(platformFindings([subject], directory), /No completed current scan/u);
		await writeFile(
			resultFile,
			JSON.stringify({ ...result, errors: ["invalid or expired exception: old entry"] }),
		);
		const renewal = await platformFindings([subject], directory);
		assert.equal(renewal.length, 1);
		await writeFile(
			path.join(directory, "platform-traefik-linux-amd64.json"),
			JSON.stringify({ ArtifactName: subject.reference }),
		);
		await assert.rejects(platformFindings([subject], directory), /malformed Trivy report/u);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});
