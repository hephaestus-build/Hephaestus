/**
 * The pinned ShellCheck: one version for `gate:shell` and for the run scripts of the workflow check,
 * installed from the upstream release and verified at each use.
 */
import { execFileSync } from "node:child_process";
import path from "node:path";

import { installPinnedBinary } from "./pinned-binary.ts";
import { CAPTURE_LIMIT_BYTES } from "./process.ts";

const VERSION = "0.11.0";
const RELEASE = `https://github.com/koalaman/shellcheck/releases/download/v${VERSION}/`;
const MEMBER = `shellcheck-v${VERSION}/shellcheck`;

// Each SHA-256 is the digest that GitHub records for the release asset, and a download matched it.
const ASSETS: Readonly<Record<string, { name: string; sha256: string; member: string }>> = {
	"darwin-arm64": {
		name: `shellcheck-v${VERSION}.darwin.aarch64.tar.gz`,
		sha256: "339b930feb1ea764467013cc1f72d09cd6b869ebf1013296ba9055ab2ffbd26f",
		member: MEMBER,
	},
	"darwin-x64": {
		name: `shellcheck-v${VERSION}.darwin.x86_64.tar.gz`,
		sha256: "c2c15e08df0e8fbc374c335b230a7ee958c313fa5714817a59aa59f1aa594f51",
		member: MEMBER,
	},
	"linux-arm64": {
		name: `shellcheck-v${VERSION}.linux.aarch64.tar.gz`,
		sha256: "68a8133197a50beb8803f8d42f9908d1af1c5540d4bb05fdfca8c1fa47decefc",
		member: MEMBER,
	},
	"linux-x64": {
		name: `shellcheck-v${VERSION}.linux.x86_64.tar.gz`,
		sha256: "b7af85e41cc99489dcc21d66c6d5f3685138f06d34651e6d34b42ec6d54fe6f6",
		member: MEMBER,
	},
	"win32-x64": {
		name: `shellcheck-v${VERSION}.zip`,
		sha256: "8a4e35ab0b331c85d73567b12f2a444df187f483e5079ceffa6bda1faa2e740e",
		member: "shellcheck.exe",
	},
};

/** The path of the pinned ShellCheck for this platform, installed into the repository cache. */
export async function prepareShellCheck(): Promise<string> {
	const key = `${process.platform}-${process.arch}`;
	const asset = ASSETS[key];
	if (asset === undefined) {
		throw new Error(
			`No pinned ShellCheck for ${key}. Add its release asset to scripts/lib/shellcheck.ts`,
		);
	}
	const binary = await installPinnedBinary({
		cache: path.join(import.meta.dirname, "..", "..", ".cache", "shellcheck"),
		version: VERSION,
		asset: { name: asset.name, sha256: asset.sha256 },
		url: RELEASE,
		executable: process.platform === "win32" ? "shellcheck.exe" : "shellcheck",
		member: asset.member,
	});
	const reported = execFileSync(binary, ["--version"], {
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
	if (!reported.includes(`version: ${VERSION}`)) {
		throw new Error(`Unexpected ShellCheck version: ${reported.trim()}. Use the pinned archive.`);
	}
	return binary;
}
