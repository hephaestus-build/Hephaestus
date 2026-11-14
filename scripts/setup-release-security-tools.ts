import { execFileSync } from "node:child_process";
import { appendFile, chmod, mkdir, mkdtemp, rename, writeFile } from "node:fs/promises";
import path from "node:path";

import { ciToolPin } from "./lib/ci-tool-catalog.ts";
import { resolveCiTool } from "./lib/ci-tool-resolver.ts";
import { requiredEnv } from "./lib/env.ts";

export async function installReleaseSecurityTools(): Promise<void> {
	const arch = process.env.RUNNER_ARCH;
	if (process.platform !== "linux" || (arch !== "X64" && arch !== "ARM64")) {
		throw new Error("Release security tools require a Linux X64 or ARM64 runner");
	}
	const onlyCosign = process.env.INSTALL_ONLY_COSIGN === "true";
	if (!onlyCosign && process.env.INSTALL_SYFT !== "true" && process.env.INSTALL_SYFT !== "false") {
		throw new Error("install-syft must be true or false");
	}
	const runnerTemp = requiredEnv(process.env, "RUNNER_TEMP");
	const githubPath = requiredEnv(process.env, "GITHUB_PATH");
	const directory = await mkdtemp(path.join(runnerTemp, "release-security-tools-"));
	const bin = path.join(directory, "bin");
	await mkdir(bin);
	const platform = `${process.platform}-${process.arch}`;
	const save = async (id: string, name: string, variant = platform) => {
		const pin = await ciToolPin(id, variant);
		const { bytes } = await resolveCiTool(pin);
		await writeFile(path.join(directory, name), bytes);
		return bytes;
	};
	await save("cosign-bootstrap", "cosign-bootstrap");
	const bootstrapPath = path.join(directory, "cosign-bootstrap");
	await chmod(bootstrapPath, 0o700);
	await save("cosign", "cosign-candidate");
	await save("cosign", "public.key", "key");
	await save("cosign", "keyless.sigstore.json", `${platform}-keyless`);
	await save("cosign", "kms.sigstore.json", `${platform}-kms`);
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
		// The resolver verifies the archive against its pinned SHA-256.
		const pin = await ciToolPin(tool);
		await save(tool, pin.name);
		run("tar", ["-xzf", path.join(directory, pin.name), "-C", bin, tool]);
		run(path.join(bin, tool), [tool === "syft" ? "version" : "--version"]);
	}
	await appendFile(githubPath, `${bin}\n`);
}

if (import.meta.main) {
	await installReleaseSecurityTools();
}
