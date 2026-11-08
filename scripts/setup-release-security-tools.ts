import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { appendFile, chmod, mkdir, mkdtemp, rename, writeFile } from "node:fs/promises";
import path from "node:path";

import { requiredEnv } from "./lib/env.ts";
import { downloadReleaseAsset } from "./lib/release-asset.ts";

const BOOTSTRAP_VERSION = "v3.1.3";
const BOOTSTRAP_SHA = {
	X64: "4629c757b7618056f8ddd7e2625ae9fdd94c0372a65049520bc7d9df9efc7f71",
	ARM64: "c5d324e091826b0d7a78eb16fef316450b4eb9aaec045611c08ba06f5e73220a",
};
const KEY_SHA = "f4cea466e5e887a45da5031757fa1d32655d83420639dc1758749b744179f126";

export function verifyChecksum(bytes: Uint8Array, expected: string): void {
	if (
		!/^[a-f0-9]{64}$/u.test(expected) ||
		createHash("sha256").update(bytes).digest("hex") !== expected
	) {
		throw new Error("Release security tool checksum differs");
	}
}

export function checksumFor(text: string, name: string): string {
	const entries = text
		.split(/\r?\n/u)
		.map((line) => /^(?<sha256>[a-f0-9]{64})\s+\*?(?<name>.+)$/u.exec(line))
		.filter((entry) => entry?.groups?.name === name);
	if (entries.length !== 1 || entries[0]?.groups?.sha256 === undefined) {
		throw new Error(`No unique checksum for ${name}`);
	}
	return entries[0].groups.sha256;
}

function requiredVersion(name: string): string {
	const value = process.env[name];
	if (value === undefined || !/^v?\d+\.\d+\.\d+$/u.test(value)) {
		throw new Error(`${name} must name an explicit version`);
	}
	return value;
}

export async function installReleaseSecurityTools(): Promise<void> {
	const arch = process.env.RUNNER_ARCH;
	if (process.platform !== "linux" || (arch !== "X64" && arch !== "ARM64")) {
		throw new Error("Release security tools require a Linux X64 or ARM64 runner");
	}
	const cosignVersion = requiredVersion("COSIGN_VERSION");
	const onlyCosign = process.env.INSTALL_ONLY_COSIGN === "true";
	if (!onlyCosign && process.env.INSTALL_SYFT !== "true" && process.env.INSTALL_SYFT !== "false") {
		throw new Error("install-syft must be true or false");
	}
	const runnerTemp = requiredEnv(process.env, "RUNNER_TEMP");
	const githubPath = requiredEnv(process.env, "GITHUB_PATH");
	const directory = await mkdtemp(path.join(runnerTemp, "release-security-tools-"));
	const bin = path.join(directory, "bin");
	await mkdir(bin);
	const save = async (url: string, name: string) => {
		const bytes = await downloadReleaseAsset(url);
		await writeFile(path.join(directory, name), bytes);
		return bytes;
	};
	const cpu = arch === "X64" ? "amd64" : "arm64";
	const cosignName = `cosign-linux-${cpu}`;
	const cosignRelease = `https://github.com/sigstore/cosign/releases/download`;
	const bootstrap = await save(
		`${cosignRelease}/${BOOTSTRAP_VERSION}/${cosignName}`,
		"cosign-bootstrap",
	);
	verifyChecksum(bootstrap, BOOTSTRAP_SHA[arch]);
	const bootstrapPath = path.join(directory, "cosign-bootstrap");
	await chmod(bootstrapPath, 0o700);
	await save(`${cosignRelease}/${cosignVersion}/${cosignName}`, "cosign-candidate");
	const key = await save(
		`https://raw.githubusercontent.com/sigstore/cosign/${cosignVersion}/release/release-cosign.pub`,
		"public.key",
	);
	verifyChecksum(key, KEY_SHA);
	await save(
		`${cosignRelease}/${cosignVersion}/${cosignName}.sigstore.json`,
		"keyless.sigstore.json",
	);
	await save(
		`${cosignRelease}/${cosignVersion}/${cosignName}-kms.sigstore.json`,
		"kms.sigstore.json",
	);
	const run = (program: string, args: string[]) =>
		execFileSync(program, args, { cwd: directory, stdio: "inherit" });
	run(bootstrapPath, [
		"verify-blob",
		"--certificate-identity=keyless@projectsigstore.iam.gserviceaccount.com",
		"--certificate-oidc-issuer=https://accounts.google.com",
		"--bundle",
		"keyless.sigstore.json",
		"cosign-candidate",
	]);
	run(bootstrapPath, [
		"verify-blob",
		"--key",
		"public.key",
		"--bundle",
		"kms.sigstore.json",
		"cosign-candidate",
	]);
	await chmod(path.join(directory, "cosign-candidate"), 0o700);
	await rename(path.join(directory, "cosign-candidate"), path.join(bin, "cosign"));
	run(path.join(bin, "cosign"), ["version"]);
	if (onlyCosign) {
		await appendFile(githubPath, `${bin}\n`);
		return;
	}
	for (const tool of ["syft", "trivy"] as const) {
		if (tool === "syft" && process.env.INSTALL_SYFT === "false") {
			continue;
		}
		const selectedVersion = requiredVersion(tool === "syft" ? "SYFT_VERSION" : "TRIVY_VERSION");
		const archive =
			tool === "syft"
				? `syft_${selectedVersion}_linux_${cpu}.tar.gz`
				: `trivy_${selectedVersion}_Linux-${arch === "X64" ? "64bit" : "ARM64"}.tar.gz`;
		const base = `https://github.com/${tool === "syft" ? "anchore/syft" : "aquasecurity/trivy"}/releases/download/v${selectedVersion}`;
		const bytes = await save(`${base}/${archive}`, archive);
		const checksums = await downloadReleaseAsset(
			`${base}/${tool}_${selectedVersion}_checksums.txt`,
		);
		verifyChecksum(bytes, checksumFor(new TextDecoder().decode(checksums), archive));
		run("tar", ["-xzf", path.join(directory, archive), "-C", bin, tool]);
		run(path.join(bin, tool), [tool === "syft" ? "version" : "--version"]);
	}
	await appendFile(githubPath, `${bin}\n`);
}

if (import.meta.main) {
	await installReleaseSecurityTools();
}
