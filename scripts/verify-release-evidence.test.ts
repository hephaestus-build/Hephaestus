import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, writeFile, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, it } from "node:test";

import { advisoryFile, componentAdvisories } from "./check-release-vulnerabilities.ts";

import {
	attestationContainsSbom,
	indexContainsSubject,
	requiresSignedEvidence,
	validateLicenseReport,
	validateManifest,
} from "./verify-release-evidence.ts";

const digest = `sha256:${"a".repeat(64)}`;
const namespace = "ghcr.io/hephaestus-build";
const inventory = { schemaVersion: 1, images: ["server"], upstream: [] };
const subject = (platform: "linux/amd64" | "linux/arm64") => ({
	digest,
	image: "server",
	indexDigest: digest,
	platform,
	provenance: "first-party" as const,
	repository: "ghcr.io/hephaestus-build/server",
});

void describe("release evidence manifest", () => {
	void it("accepts exactly one canonical subject for each supported platform", () => {
		const result = validateManifest(
			{ schemaVersion: 1, subjects: [subject("linux/amd64"), subject("linux/arm64")] },
			inventory,
			namespace,
		);
		assert.equal(result.subjects.length, 2);
	});

	void it("fails closed on missing, reordered, and duplicate platforms", () => {
		assert.throws(
			() =>
				validateManifest(
					{ schemaVersion: 1, subjects: [subject("linux/amd64")] },
					inventory,
					namespace,
				),
			/canonical order/u,
		);
		assert.throws(
			() =>
				validateManifest(
					{ schemaVersion: 1, subjects: [subject("linux/arm64"), subject("linux/amd64")] },
					inventory,
					namespace,
				),
			/canonical order/u,
		);
		assert.throws(
			() =>
				validateManifest(
					{ schemaVersion: 1, subjects: [subject("linux/amd64"), subject("linux/amd64")] },
					inventory,
					namespace,
				),
			/duplicate image platforms/u,
		);
	});

	void it("rejects path traversal, mutable identities, wrong repositories, and invalid provenance", () => {
		const valid = subject("linux/amd64");
		for (const invalid of [
			{ ...valid, image: "../manifest" },
			{ ...valid, digest: "latest" },
			{ ...valid, repository: "ghcr.io/attacker/server" },
			{ ...valid, provenance: "upstream" },
			{ ...valid, provenance: "unknown" },
		]) {
			assert.throws(
				() =>
					validateManifest(
						{ schemaVersion: 1, subjects: [invalid, subject("linux/arm64")] },
						inventory,
						namespace,
					),
				/malformed|does not match/u,
			);
		}
	});

	void it("rejects duplicate or malformed inventory entries", () => {
		const subjects = [subject("linux/amd64"), subject("linux/arm64")];
		assert.throws(
			() =>
				validateManifest(
					{ schemaVersion: 1, subjects },
					{ schemaVersion: 1, images: ["server", "server"], upstream: [] },
					namespace,
				),
			/duplicate release image/u,
		);
		assert.throws(
			() =>
				validateManifest(
					{ schemaVersion: 1, subjects },
					{ schemaVersion: 1, images: ["../server"], upstream: [] },
					namespace,
				),
			/invalid image/u,
		);
	});

	void it("derives provenance and index identity from the inventory", () => {
		const upstreamDigest = `sha256:${"b".repeat(64)}`;
		const upstreamInventory = {
			schemaVersion: 1,
			images: [],
			upstream: [
				{
					digest: upstreamDigest,
					name: "postgres",
					repository: "docker.io/library/postgres",
				},
			],
		};
		const upstreamSubject = (platform: "linux/amd64" | "linux/arm64") => ({
			digest,
			image: "postgres",
			indexDigest: upstreamDigest,
			platform,
			provenance: "upstream" as const,
			repository: "docker.io/library/postgres",
		});
		const valid = [upstreamSubject("linux/amd64"), upstreamSubject("linux/arm64")];
		assert.doesNotThrow(() =>
			validateManifest({ schemaVersion: 1, subjects: valid }, upstreamInventory, namespace),
		);
		assert.throws(
			() =>
				validateManifest(
					{ schemaVersion: 1, subjects: [{ ...valid[0], provenance: "first-party" }, valid[1]] },
					upstreamInventory,
					namespace,
				),
			/does not match its inventory/u,
		);
		assert.throws(
			() =>
				validateManifest(
					{ schemaVersion: 1, subjects: [{ ...valid[0], indexDigest: digest }, valid[1]] },
					upstreamInventory,
					namespace,
				),
			/does not match its inventory/u,
		);
		assert.throws(
			() =>
				validateManifest(
					{
						schemaVersion: 1,
						subjects: [
							subject("linux/amd64"),
							{ ...subject("linux/arm64"), indexDigest: upstreamDigest },
						],
					},
					inventory,
					namespace,
				),
			/one index digest/u,
		);
	});
});

/**
 * Real `cosign verify-attestation` output, captured from cosign v3.0.6 — the version
 * `setup-release-security-tools` installs — attesting `--type spdxjson` over a local registry and
 * verifying it back. The published release framing is identical: verifying
 * `ghcr.io/hephaestus-build/webapp@sha256:ff26dd20…` against the release identity prints one
 * unwrapped 1.4 MB envelope on stdout and its banner on stderr, which is the shape the v0.75.0
 * release read as an array and rejected. The predicates are `attested("one")` and `attested("two")`.
 */
const ONE_ATTESTATION = `{"payload":"eyJfdHlwZSI6Imh0dHBzOi8vaW4tdG90by5pby9TdGF0ZW1lbnQvdjAuMSIsICJzdWJqZWN0IjpbeyJuYW1lIjoiMTI3LjAuMC4xOjU3MTEvc2luZ2xlIiwgImRpZ2VzdCI6eyJzaGEyNTYiOiI5MmIxZDFjYWU1ZjIzNTgxMjE4NDQxNWU2M2Q5YjI0NDY0MTE2YzU4ZDNiYTNjNDYwYjFlYjAyNDdmMGY0NmUzIn19XSwgInByZWRpY2F0ZVR5cGUiOiJodHRwczovL3NwZHguZGV2L0RvY3VtZW50IiwgInByZWRpY2F0ZSI6eyJTUERYSUQiOiJTUERYUmVmLURPQ1VNRU5UIiwgIm5hbWUiOiJvbmUiLCAic3BkeFZlcnNpb24iOiJTUERYLTIuMyJ9fQ==","payloadType":"application/vnd.in-toto+json","signatures":[{"sig":"MEUCIQCighBWsxdqA3466CSv1Pz4ny3CAAVUk1L8eBM/95AR/wIgSB1yUick8Sa98l+0ANTS+nI77MX0JPbZrqLupZEpqB4="}]}
`;

const TWO_ATTESTATIONS = `{"payload":"eyJfdHlwZSI6Imh0dHBzOi8vaW4tdG90by5pby9TdGF0ZW1lbnQvdjAuMSIsICJzdWJqZWN0IjpbeyJuYW1lIjoiMTI3LjAuMC4xOjU3MTEvbGFiIiwgImRpZ2VzdCI6eyJzaGEyNTYiOiI5MmIxZDFjYWU1ZjIzNTgxMjE4NDQxNWU2M2Q5YjI0NDY0MTE2YzU4ZDNiYTNjNDYwYjFlYjAyNDdmMGY0NmUzIn19XSwgInByZWRpY2F0ZVR5cGUiOiJodHRwczovL3NwZHguZGV2L0RvY3VtZW50IiwgInByZWRpY2F0ZSI6eyJTUERYSUQiOiJTUERYUmVmLURPQ1VNRU5UIiwgIm5hbWUiOiJvbmUiLCAic3BkeFZlcnNpb24iOiJTUERYLTIuMyJ9fQ==","payloadType":"application/vnd.in-toto+json","signatures":[{"sig":"MEUCIQC1HEpwfNfuYsx4Bu6KFtT3vWy/rxoRMBIGcVaF8Li1LAIgKI+2OWmCsLqK1ROi7EWyKdaJkaMCpt4CYimpqpRrQ40="}]}
{"payload":"eyJfdHlwZSI6Imh0dHBzOi8vaW4tdG90by5pby9TdGF0ZW1lbnQvdjAuMSIsICJzdWJqZWN0IjpbeyJuYW1lIjoiMTI3LjAuMC4xOjU3MTEvbGFiIiwgImRpZ2VzdCI6eyJzaGEyNTYiOiI5MmIxZDFjYWU1ZjIzNTgxMjE4NDQxNWU2M2Q5YjI0NDY0MTE2YzU4ZDNiYTNjNDYwYjFlYjAyNDdmMGY0NmUzIn19XSwgInByZWRpY2F0ZVR5cGUiOiJodHRwczovL3NwZHguZGV2L0RvY3VtZW50IiwgInByZWRpY2F0ZSI6eyJTUERYSUQiOiJTUERYUmVmLURPQ1VNRU5UIiwgIm5hbWUiOiJ0d28iLCAic3BkeFZlcnNpb24iOiJTUERYLTIuMyJ9fQ==","payloadType":"application/vnd.in-toto+json","signatures":[{"sig":"MEUCIHwf0z545E7IF1LwfbK5UujrpJWZm8K1LtmX9aAUfg2EAiEAgUIsdk5MALmyjqjj4zzns6P3ae7B/EgFTJIwwdQd2eA="}]}
`;

/** The durable SBOM those attestations were made from, in the key order it was written in. */
const attested = (name: string) => ({
	SPDXID: "SPDXRef-DOCUMENT",
	spdxVersion: "SPDX-2.3",
	name,
});

void describe("release evidence bindings", () => {
	void it("binds license and OCI index evidence to the immutable subject", () => {
		const amd64 = subject("linux/amd64");
		const reference = `${amd64.repository}@${amd64.digest}`;
		assert.doesNotThrow(() =>
			validateLicenseReport({ ArtifactName: reference, Results: [] }, reference),
		);
		assert.throws(() => validateLicenseReport({ ArtifactName: "wrong", Results: [] }, reference));
		assert.equal(
			indexContainsSubject(
				{ manifests: [{ digest, platform: { os: "linux", architecture: "amd64" } }] },
				amd64,
			),
			true,
		);
		assert.equal(
			indexContainsSubject(
				{ manifests: [{ digest, platform: { os: "linux", architecture: "arm64" } }] },
				amd64,
			),
			false,
		);
	});

	void it("requires a Cosign payload whose predicate exactly matches the durable SBOM", () => {
		assert.equal(attestationContainsSbom(ONE_ATTESTATION, attested("one")), true);
		assert.equal(attestationContainsSbom(ONE_ATTESTATION, attested("two")), false);
		// Cosign re-serializes the predicate with its keys sorted, so the durable document and the
		// attested one differ byte for byte and match value for value.
		assert.notEqual(
			JSON.stringify(attested("one")),
			JSON.stringify({ SPDXID: "SPDXRef-DOCUMENT", name: "one", spdxVersion: "SPDX-2.3" }),
		);
	});

	void it("reads every framing cosign prints its verified attestations in", () => {
		// The framing that failed the release: one attestation is one bare envelope, not an array.
		assert.equal(attestationContainsSbom(ONE_ATTESTATION, attested("one")), true);
		// Two attestations on one subject are newline-delimited, still unwrapped.
		assert.equal(attestationContainsSbom(TWO_ATTESTATIONS, attested("two")), true);
		// And a future cosign that wraps them in an array, pretty-printed or not, reads the same.
		const envelopes: unknown[] = TWO_ATTESTATIONS.trim()
			.split("\n")
			.map((line) => JSON.parse(line) as unknown);
		assert.equal(attestationContainsSbom(JSON.stringify(envelopes), attested("two")), true);
		assert.equal(
			attestationContainsSbom(JSON.stringify(envelopes, null, 2), attested("two")),
			true,
		);
	});

	void it("refuses to pass or to skip an attestation it cannot read", () => {
		for (const unreadable of ["", "   \n", '{"payload":"not-base64-json"}', "[]", '["envelope"]']) {
			assert.throws(() => attestationContainsSbom(unreadable, attested("one")));
		}
	});
});

void describe("archived evidence authentication", { skip: process.platform === "win32" }, () => {
	for (const failure of ["missing-signature", "wrong-signature", "tampered-bytes"] as const) {
		void it(`${failure} fails before archived component or policy data is read`, async () => {
			const directory = await mkdtemp(path.join(tmpdir(), "release-authentication-"));
			const bin = path.join(directory, "bin");
			await mkdir(bin);
			const manifest = JSON.stringify({ release: "v0.86.1" });
			await writeFile(path.join(directory, "manifest.json"), manifest);
			await writeFile(path.join(directory, "vulnerability-policy.json"), "invalid policy JSON");
			await writeFile(
				path.join(directory, "SHA256SUMS"),
				`${createHash("sha256").update(manifest).digest("hex")}  manifest.json\n`,
			);
			if (failure !== "missing-signature") {
				await writeFile(path.join(directory, "SHA256SUMS.sigstore.json"), "synthetic bundle");
			}
			if (failure === "tampered-bytes") {
				await writeFile(
					path.join(directory, "manifest.json"),
					JSON.stringify({ release: "v0.86.1", changed: true }),
				);
			}
			await writeFile(
				path.join(bin, "cosign"),
				`#!/bin/sh
printf '%s\\n' "$@" > '${directory}/signature-command'
${failure === "wrong-signature" ? String.raw`printf '%s\n' 'signature refused' >&2; exit 1` : "exit 0"}
`,
				{ mode: 0o755 },
			);
			const child = spawnSync(
				process.execPath,
				[new URL("verify-release-evidence.ts", import.meta.url).pathname, directory],
				{
					encoding: "utf8",
					env: { ...process.env, PATH: `${bin}:${process.env.PATH ?? ""}` },
				},
			);
			assert.notEqual(child.status, 0);
			assert.match(
				child.stderr,
				{
					"missing-signature": /no SHA256SUMS signature/u,
					"wrong-signature": /signature refused/u,
					"tampered-bytes": /checksum did NOT match/u,
				}[failure],
			);
			assert.doesNotMatch(child.stderr, /invalid policy JSON|Unexpected token/u);
			if (failure !== "missing-signature") {
				assert.match(
					await readFile(path.join(directory, "signature-command"), "utf8"),
					/verify-blob[\s\S]*SHA256SUMS.sigstore.json[\s\S]*--certificate-identity[\s\S]*release.yml@refs\/heads\/main[\s\S]*SHA256SUMS/u,
				);
			}
		});
	}
});

void describe("complete archived checksum coverage", { skip: process.platform === "win32" }, () => {
	for (const omission of ["policy", "syft", "advisory"] as const) {
		void it(`a signed checksum list missing ${omission} cannot trust the unlisted forged file`, async () => {
			const directory = await mkdtemp(path.join(tmpdir(), "release-coverage-"));
			const bin = path.join(directory, "bin");
			await mkdir(bin);
			const policyText = await readFile(
				new URL("../security/vulnerability-policy.json", import.meta.url),
				"utf8",
			);
			const policyValue: unknown = JSON.parse(policyText);
			const advisories = componentAdvisories(policyValue).map(advisoryFile);
			assert.ok(advisories.length > 0);
			const files = new Map([
				[
					"manifest.json",
					JSON.stringify({
						schemaVersion: 1,
						release: "v0.86.1",
						subjects: [subject("linux/amd64"), subject("linux/arm64")],
					}),
				],
				["release-images.json", JSON.stringify(inventory)],
				["vulnerability-policy.json", policyText],
			]);
			for (const platform of ["linux-amd64", "linux-arm64"]) {
				for (const kind of [
					"syft",
					"spdx",
					"cdx",
					"license",
					"trivy",
					"sbom-validation",
					"policy",
				]) {
					files.set(`server-${platform}.${kind}.json`, "unlisted forged component data");
				}
			}
			for (const advisory of advisories) {
				files.set(advisory, "unlisted forged advisory data");
			}
			const missing = {
				policy: "vulnerability-policy.json",
				syft: "server-linux-amd64.syft.json",
				advisory: advisories[0],
			}[omission];
			assert.ok(missing !== undefined);
			if (omission === "policy") {
				files.set(missing, "unlisted forged policy data");
			}
			let checksums = "";
			for (const [name, contents] of files) {
				await writeFile(path.join(directory, name), contents);
				if (name !== missing) {
					checksums += `${createHash("sha256").update(contents).digest("hex")}  ${name}\n`;
				}
			}
			await writeFile(path.join(directory, "SHA256SUMS"), checksums);
			await writeFile(
				path.join(directory, "SHA256SUMS.sigstore.json"),
				"synthetic authenticated-list bundle",
			);
			await writeFile(path.join(bin, "cosign"), "#!/bin/sh\nexit 0\n", { mode: 0o755 });
			const child = spawnSync(
				process.execPath,
				[new URL("verify-release-evidence.ts", import.meta.url).pathname, directory],
				{ encoding: "utf8", env: { ...process.env, PATH: `${bin}:${process.env.PATH ?? ""}` } },
			);
			assert.notEqual(child.status, 0);
			assert.ok(child.stderr.includes(`SHA256SUMS does not cover ${missing}`));
			assert.doesNotMatch(child.stderr, /Unexpected token|unlisted forged/u);
		});
	}
});

void it("signed evidence is required from the owned release boundary, including its prereleases", () => {
	for (const tag of ["v0.85.1", "v0.86.0", "v0.86.1"]) {
		assert.equal(requiresSignedEvidence(tag), false);
	}
	for (const tag of ["v0.86.2", "v0.86.2-rc.1", "v0.86.10", "v1.0.0"]) {
		assert.equal(requiresSignedEvidence(tag), true);
	}
});
