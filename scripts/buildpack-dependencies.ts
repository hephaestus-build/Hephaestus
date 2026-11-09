/**
 * Resolves the pinned files that the App Server image's buildpacks would otherwise download, and
 * writes a Paketo `dependency-mapping` binding that maps each buildpack checksum to its verified
 * file. The buildpack verifies the file against the same checksum again before it uses it.
 */
import { appendFile, mkdir, readdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { ciToolPin } from "./lib/ci-tool-catalog.ts";
import { resolveCiTool } from "./lib/ci-tool-resolver.ts";
import { requiredEnv } from "./lib/env.ts";

// The Spring Boot buildpack records the mapped URIs in the image metadata, so the path is fixed.
const root = path.join(requiredEnv(process.env, "RUNNER_TEMP"), "buildpack-dependencies");
const files = path.join(root, "files");
const binding = path.join(root, "bindings", "dependency-mapping");
await rm(root, { recursive: true, force: true });
await mkdir(files, { recursive: true });
await mkdir(binding, { recursive: true });
await writeFile(path.join(binding, "type"), "dependency-mapping");
const pins = await readdir(new URL("../.github/ci-tools/", import.meta.url));
const ids = pins
	.filter((name) => /^buildpack-[a-z0-9-]+\.json$/u.test(name))
	.map((name) => name.slice(0, -".json".length))
	.toSorted();
for (const id of ids) {
	const pin = await ciToolPin(id);
	const file = path.join(files, pin.name);
	const { bytes } = await resolveCiTool(pin);
	await writeFile(file, bytes);
	// The build mounts `files` at its host path, so this URI is valid inside the build container.
	await writeFile(path.join(binding, pin.sha256), pathToFileURL(file).href);
}
await appendFile(requiredEnv(process.env, "GITHUB_OUTPUT"), `directory=${root}\n`);
