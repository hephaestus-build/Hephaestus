/**
 * The server image carries the built web app inside its application jar (ADR 0100). The buildpack's CDS
 * extraction leaves the application classes in `/workspace/runner.jar`, and neither Syft nor Trivy catalogs an npm
 * package inside a jar. So every scan of the server image also scans the web app, taken from that jar of the same
 * image: the inventory and the vulnerability scan stay bound to the image digest that ships.
 */
import { existsSync } from "node:fs";
import { mkdir } from "node:fs/promises";
import path from "node:path";

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
 * Extracts the web app of an image into `destination` and returns its directory, or `undefined` when the image
 * carries none, as a server image built before the web app moved into it.
 */
export async function extractWebApp(
	reference: string,
	platform: string,
	destination: string,
): Promise<string | undefined> {
	await mkdir(destination, { recursive: true });
	const jar = path.join(destination, "runner.jar");
	// `create` pulls the platform's image and runs nothing.
	const container = (await output("docker", ["create", "--platform", platform, reference])).trim();
	try {
		await run("docker", ["cp", `${container}:${RUNNER_JAR}`, jar]);
	} finally {
		await run("docker", ["rm", container], { stdout: "ignore" });
	}
	const classes = path.join(destination, "classes");
	await run("unzip", ["-q", "-o", jar, "-d", classes]);
	const webApp = path.join(classes, "web-app");
	return existsSync(path.join(webApp, "index.html")) ? webApp : undefined;
}

/** Syft over the extracted web app. The package cataloger reads installed `package.json` files, as in an image. */
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
 * Trivy over the extracted web app, as a root file system so that it reads installed `package.json` files as it does
 * in an image. `flags` are the caller's, as for its image scan.
 */
export function webAppTrivyArguments(
	directory: string,
	report: string,
	flags: readonly string[],
): string[] {
	return ["rootfs", ...flags, "--format", "json", "--output", report, directory];
}
