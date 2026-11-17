/**
 * The server image carries the built web app inside its application jar (ADR 0100). The buildpack's CDS
 * extraction leaves the application classes in `/workspace/runner.jar`, and neither Syft nor Trivy catalogs an npm
 * package inside a jar. So every scan of the server image also scans the web app, taken from that jar of the same
 * image: the inventory and the vulnerability scan stay bound to the image digest that ships.
 */
import { existsSync } from "node:fs";
import { mkdir } from "node:fs/promises";
import path from "node:path";

import { asRecord, isRecord } from "./json.ts";
import { output, run } from "./process.ts";

/** The image that carries the web app. */
export const WEB_APP_IMAGE = "server";

/** Where the buildpack leaves the application classes, the web app among them. */
export const RUNNER_JAR = "/workspace/runner.jar";

/** One `package.json` per bundled npm package, which `webapp/tools/bundle-packages.ts` writes into the build. */
export const WEB_APP_PACKAGES = "/.vite/packages/";

/**
 * Packages that every build of the web app bundles. An inventory without them has lost the web app, so a scan that
 * stops seeing the bundle fails instead of passing with fewer packages.
 */
export const KNOWN_BUNDLED_PACKAGES = ["react", "react-dom"] as const;

/** The stem suffix of every web app document of a server subject, such as `server-linux-amd64.web-app.syft.json`. */
export const WEB_APP_STEM = "web-app";

/**
 * The name under which a scan of the web app reports it: the image reference and the directory in its jar. Scans run in
 * the scratch directory against this relative path, so Trivy's `ArtifactName` names the image digest that the web app
 * came from, and the policy evaluator checks it as it checks an image scan.
 */
export function webAppArtifact(reference: string): string {
	return `${reference}/${WEB_APP_STEM}`;
}

/**
 * Extracts the web app of an image below `scratch`, at {@link webAppArtifact}, and returns that relative path, or
 * `undefined` when the image carries none, as a server image built before the web app moved into it.
 */
export async function extractWebApp(
	reference: string,
	platform: string,
	scratch: string,
): Promise<string | undefined> {
	await mkdir(scratch, { recursive: true });
	const jar = path.join(scratch, "runner.jar");
	// `create` pulls the platform's image and runs nothing.
	const created = await output("docker", ["create", "--platform", platform, reference]);
	const container = created.trim();
	try {
		await run("docker", ["cp", `${container}:${RUNNER_JAR}`, jar]);
	} finally {
		await run("docker", ["rm", container], { stdout: "ignore" });
	}
	await run("unzip", ["-q", "-o", jar, "-d", path.join(scratch, reference)]);
	const artifact = webAppArtifact(reference);
	return existsSync(path.join(scratch, artifact, "index.html")) ? artifact : undefined;
}

/**
 * Syft over the extracted web app, run in the scratch directory. The package cataloger reads installed `package.json`
 * files, as in an image.
 */
export function webAppSyftArguments(directory: string, config: string): string[] {
	return [
		`dir:${directory}`,
		"--select-catalogers",
		"+javascript-package-cataloger",
		"--config",
		config,
	];
}

/**
 * Trivy over the extracted web app, run in the scratch directory, as a root file system so that it reads installed
 * `package.json` files as it does in an image. `flags` are the caller's, as for its image scan.
 */
export function webAppTrivyArguments(
	directory: string,
	report: string,
	flags: readonly string[],
): string[] {
	return ["rootfs", ...flags, "--format", "json", "--output", report, directory];
}

/**
 * A scan of the web app that does not list the packages every build bundles has not read the bundle. It fails rather
 * than passing with fewer packages.
 */
export function assertKnownPackagesScanned(report: unknown): void {
	const results: unknown = asRecord(report, "Trivy report").Results;
	const scanned = new Set<unknown>();
	for (const result of Array.isArray(results) ? (results as unknown[]) : []) {
		if (isRecord(result) && result.Type === "node-pkg" && Array.isArray(result.Packages)) {
			for (const item of result.Packages as unknown[]) {
				scanned.add(isRecord(item) ? item.Name : undefined);
			}
		}
	}
	for (const name of KNOWN_BUNDLED_PACKAGES) {
		if (!scanned.has(name)) {
			throw new Error(`the scan of the web app lists no bundled ${name}`);
		}
	}
}
