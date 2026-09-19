import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
	attestationContainsSbom,
	indexContainsSubject,
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
