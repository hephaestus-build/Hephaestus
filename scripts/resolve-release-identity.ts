/**
 * Resolves the GHCR namespace and cosign certificate identity a release was
 * published under (`security/release-identities.json`, issue #1599).
 *
 * usage: node scripts/resolve-release-identity.ts <vX.Y.Z | X.Y.Z> [field]
 *
 * With a field (`namespace` | `certificate-identity`) it prints just that value,
 * for command substitution. Without one it prints both as `key=value` lines and
 * appends them to `GITHUB_OUTPUT` when set, for use as a workflow step.
 */
import { appendFileSync } from "node:fs";
import process from "node:process";

import { isSet } from "./lib/env.ts";
import { releaseCertificateIdentity, releaseIdentityFor } from "./lib/release-identities.ts";

const [release = "", field] = process.argv.slice(2);
if (release === "") {
	throw new Error(
		"usage: resolve-release-identity <vX.Y.Z | X.Y.Z> [namespace|certificate-identity]",
	);
}

const values = {
	namespace: releaseIdentityFor(release).namespace,
	"certificate-identity": releaseCertificateIdentity(release, process.env),
};

if (field !== undefined && field !== "namespace" && field !== "certificate-identity") {
	throw new Error(`unknown field '${field}' (expected namespace or certificate-identity)`);
}
if (field === undefined) {
	const lines = Object.entries(values)
		.map(([key, value]) => `${key}=${value}\n`)
		.join("");
	process.stdout.write(lines);
	const githubOutput = process.env.GITHUB_OUTPUT;
	if (isSet(githubOutput)) {
		appendFileSync(githubOutput, lines);
	}
} else {
	console.log(values[field]);
}
