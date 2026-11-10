import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import {
	compareVersions,
	exceptionFor,
	newestStable,
	platformPinsOf,
	refusalsOf,
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
		pins.map((pin) => pin.repository),
		[
			"k3s-io/k3s",
			"cert-manager/cert-manager",
			"traefik/traefik-helm-chart",
			"traefik/traefik",
			"kubernetes-sigs/agent-sandbox",
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

await test("a refused finding becomes an affected exception bound to its digest, dated and checked", () => {
	const digest = `sha256:${"a".repeat(64)}`;
	const refusals = refusalsOf(
		{
			image: "k3s",
			platform: "linux/arm64",
			digest,
			// Two binaries carry the same module version.
			rejected: [
				"k3s|CVE-2026-1|golang.org/x/net|v0.57.0",
				"k3s|CVE-2026-1|golang.org/x/net|v0.57.0",
			],
		},
		{
			Results: [
				{
					Vulnerabilities: [
						{
							VulnerabilityID: "CVE-2026-1",
							PkgName: "golang.org/x/net",
							InstalledVersion: "v0.57.0",
							Severity: "HIGH",
						},
					],
				},
			],
		},
	);
	assert.equal(refusals.length, 1);
	const [refusal] = refusals;
	assert.ok(refusal);
	assert.equal(refusal.severity, "HIGH");
	const pin = {
		name: "k3s",
		repository: "k3s-io/k3s",
		pinned: "v1.37.2+k3s1",
		owns: () => true,
	};
	const exception = exceptionFor(refusal, pin, "v1.37.2+k3s1", new Date("2026-11-10T07:30:00Z"));
	assert.deepEqual(exception, {
		image: "k3s",
		platform: "linux/arm64",
		vulnerability: "CVE-2026-1",
		package: "golang.org/x/net",
		installedVersion: "v0.57.0",
		digest,
		status: "affected",
		justification:
			"No upstream release ships the fix. On 2026-11-10, v1.37.2+k3s1 was the newest stable release of k3s-io/k3s, and v1.37.2+k3s1 is pinned. Retire this entry when the pin moves.",
		evidence: "https://github.com/k3s-io/k3s/releases/tag/v1.37.2%2Bk3s1",
		owner: "FelixTJDietrich",
		expires: "2027-02-08T00:00:00Z",
	});
});
