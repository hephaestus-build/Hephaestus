import { execFileSync } from "node:child_process";
import { appendFile, chmod, mkdtemp, rm, writeFile } from "node:fs/promises";
import path from "node:path";

import { ciToolPin } from "./lib/ci-tool-catalog.ts";
import { resolveCiTool } from "./lib/ci-tool-resolver.ts";
import { requiredEnv } from "./lib/env.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

if (process.platform !== "linux") {
	throw new Error("Kubernetes CI tools require Linux");
}
const directory = await mkdtemp(
	path.join(requiredEnv(process.env, "RUNNER_TEMP"), "kubernetes-tools-"),
);
for (const id of ["k3d", "kind", "kubeconform"]) {
	const pin = await ciToolPin(id);
	const resolved = await resolveCiTool(pin);
	const binary = path.join(directory, id);
	if (pin.member === undefined) {
		await writeFile(binary, resolved.bytes);
	} else {
		const archive = path.join(directory, pin.name);
		await writeFile(archive, resolved.bytes);
		execFileSync("tar", ["-xzf", archive, "-C", directory, pin.member], { stdio: "inherit" });
		await rm(archive);
	}
	await chmod(binary, 0o755);
	if (id !== "kubeconform") {
		const version = execFileSync(binary, ["version"], {
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
		});
		if (!version.includes(`${id === "k3d" ? "k3d version" : "kind"} v${pin.version}`)) {
			throw new Error(`Unexpected ${id} version`);
		}
	}
}
await appendFile(requiredEnv(process.env, "GITHUB_PATH"), `${directory}\n`);
