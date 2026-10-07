import assert from "node:assert/strict";
import { test } from "node:test";

import { directArchiveSources } from "./install-browser-deps.ts";

const DIRECT = "URIs: https://archive.ubuntu.com/ubuntu/";

const stanza = (uris: string, suites: string) =>
	[
		"Types: deb",
		uris,
		`Suites: ${suites}`,
		"Components: main universe restricted multiverse",
		"Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg",
	].join("\n");

void test("points every runner mirror stanza at the archive and keeps its other fields", () => {
	const mirror = "URIs: mirror+file:/etc/apt/apt-mirrors.txt";

	assert.equal(
		directArchiveSources(
			`${stanza(mirror, "noble noble-updates noble-backports")}\n\n${stanza(mirror, "noble-security")}\n`,
		),
		`${stanza(DIRECT, "noble noble-updates noble-backports")}\n\n${stanza(DIRECT, "noble-security")}\n`,
	);
});

void test("keeps comments, disabled stanzas and inline signing keys byte for byte", () => {
	const key = [
		"Signed-By:",
		" -----BEGIN PGP PUBLIC KEY BLOCK-----",
		" .",
		" mQINBFufwdoBEADv/Gxytx/LcSXYuM0MwKojbBye81s0G1nEx+lz6VAUpIUZnbkq",
		" =2Z5t",
		" -----END PGP PUBLIC KEY BLOCK-----",
	].join("\n");
	const before = (uris: string) =>
		[
			"# Ubuntu sources have moved to this file",
			"Types: deb deb-src",
			uris,
			"Suites: noble noble-updates",
			"Components: main",
			"Architectures: amd64",
			"Enabled: no",
			key,
			"",
		].join("\n");

	assert.equal(
		directArchiveSources(before("URIs: http://azure.archive.ubuntu.com/ubuntu/")),
		before(DIRECT),
	);
});

void test("replaces a URIs field that continues onto further lines, and only that field", () => {
	const sources = [
		"Types: deb",
		"URIs: http://azure.archive.ubuntu.com/ubuntu/",
		"\thttp://security.ubuntu.com/ubuntu/",
		"Suites: noble",
		"Signed-By:",
		" -----BEGIN PGP PUBLIC KEY BLOCK-----",
		" -----END PGP PUBLIC KEY BLOCK-----",
	].join("\n");

	assert.equal(
		directArchiveSources(sources),
		[
			"Types: deb",
			DIRECT,
			"Suites: noble",
			"Signed-By:",
			" -----BEGIN PGP PUBLIC KEY BLOCK-----",
			" -----END PGP PUBLIC KEY BLOCK-----",
		].join("\n"),
	);
});

void test("refuses sources it cannot point at the archive instead of using the original mirrors", () => {
	for (const sources of [
		"",
		"# only a comment\n",
		"Types: deb\nSuites: noble\n",
		`Types: deb\n${DIRECT}\nSuites: noble\n\nTypes: deb\nSuites: noble-security\n`,
	]) {
		assert.throws(() => directArchiveSources(sources), Error);
	}
});

void test("leaves sources that already name the archive unchanged", () => {
	const direct = `Types: deb\n${DIRECT}\nSuites: noble noble-security\nComponents: main\n`;

	assert.equal(directArchiveSources(direct), direct);
	assert.equal(directArchiveSources(directArchiveSources(direct)), direct);
});
