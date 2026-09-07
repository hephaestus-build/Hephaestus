import assert from "node:assert/strict";
import { test } from "node:test";

import { maskOptionalRuntimePeerMetadata } from "./lib/optional-runtime-peer.ts";

const runtime = ["b", "un"].join("");
const peer = `${runtime}-types-no-globals`;
const forbidden = new RegExp(`\\b${runtime}\\b`, "i");
const metadata = `packages:
  unplugin@3.3.0:
    peerDependencies:
      ${peer}: '*'
    peerDependenciesMeta:
      ${peer}:
        optional: true
snapshots:
  router-plugin@1.0.0:
    transitivePeerDependencies:
      - ${peer}
`;

const mask = (text: string) =>
	maskOptionalRuntimePeerMetadata(text, (name) => forbidden.test(name));

const rejects = (text: string) => assert.match(mask(text), forbidden);

void test("allows the unused optional type peer and its transitive metadata", () => {
	assert.doesNotMatch(mask(metadata), forbidden);
});

void test("allows optional peers independent of package name, version and version range", () => {
	assert.doesNotMatch(
		mask(metadata.replace("unplugin@3.3.0", "other@4.0.0").replace("'*'", "'^1.0.0'")),
		forbidden,
	);
	assert.doesNotMatch(mask(metadata.replaceAll(peer, runtime)), forbidden);
});

void test("does not allow required peers even when another package declares that peer optional", () => {
	rejects(metadata.replace("optional: true", "optional: false"));
	rejects(
		metadata.replace(
			"packages:\n",
			`packages:\n  required@1:\n    peerDependencies:\n      ${peer}: '*'\n`,
		),
	);
});

void test("does not allow an installed package or dependency hidden beside optional metadata", () => {
	rejects(metadata.replace("packages:\n", `packages:\n  ${peer}@1.0.0: {}\n`));
	rejects(`${metadata}  app@1.0.0:\n    dependencies:\n      ${peer}: 1.0.0\n`);
	rejects(
		`${metadata}importers:\n  .:\n    dependencies:\n      ${runtime}:\n        specifier: '1'\n        version: 1.0.0\n`,
	);
});

void test("keeps comments, unrelated values and other retired-tool references visible", () => {
	rejects(`${metadata}# Run ${runtime} install\n`);
	rejects(`${metadata}settings:\n  command: ${runtime} run build\n`);
	rejects(metadata.replace("'*'", `'npm:${runtime}@1'`));
});

void test("does not allow a transitive name without its reviewed optional declaration", () => {
	rejects(`packages: {}\nsnapshots:\n  x@1:\n    transitivePeerDependencies:\n      - ${peer}\n`);
});

void test("rejects malformed lockfiles rather than weakening the scan", () => {
	assert.throws(() => mask("packages: ["));
});

void test("handles package-manager and application lock documents without exempting either", () => {
	const lock = `---\nlockfileVersion: '9.0'\npackages: {}\n---\n${metadata}`;
	assert.doesNotMatch(mask(lock), forbidden);
	rejects(lock.replace("packages: {}", `packages:\n  ${runtime}@1.0.0: {}`));
});

void test("does not carry optional-peer permissions across lockfile documents", () => {
	rejects(
		`${metadata}---\npackages: {}\nsnapshots:\n  x@1:\n    transitivePeerDependencies:\n      - ${peer}\n`,
	);
});
