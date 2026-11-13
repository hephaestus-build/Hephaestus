import environment from "@/environment";

import { DOC_TARGETS, type DocKey, type DocTarget } from "./doc-targets";

/** The public docs site. Its root serves the latest release, and `next/` serves `main` (docs/README.md § Versions). */
const PUBLIC_DOCS_URL = "https://docs.hephaestus.build/";

/** A version that the release train publishes: `1.2.3` or `1.2.3-rc.4` (`scripts/lib/release-version.ts`). */
const RELEASE_VERSION = /^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-rc\.(?:0|[1-9]\d*))?$/u;

/**
 * The docs site for a build of this version. A release reads the docs of the latest release. Any
 * other build, such as a local `DEV` build or an image of `main`, runs unreleased code, so it reads
 * the docs of `main`.
 */
export function docsBaseUrl(version: string): string {
	return RELEASE_VERSION.test(version) ? PUBLIC_DOCS_URL : `${PUBLIC_DOCS_URL}next/`;
}

/** The address of a registry entry on the docs site for this build: the latest release, or the next one. */
export function docUrl(key: DocKey, base: string = docsBaseUrl(environment.version)): string {
	const target: DocTarget = DOC_TARGETS[key];
	const url = new URL(target.path, base);
	if (target.anchor !== undefined) {
		url.hash = target.anchor;
	}
	return url.href;
}
