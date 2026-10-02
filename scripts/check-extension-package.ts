/**
 * Judges the Chrome extension zip a store submission would upload, not the tree it was built from.
 *
 * The browser suite runs the `e2e` build, which carries the development key, loopback access and
 * pre-granted fixture origins by design. None of that may reach the production package, and a
 * mistake there is invisible to every test that exercises a working extension. So the production zip
 * is opened and its manifest held to the release: the root `package.json` version, no `key`, the
 * permission set the store listing justifies, HTTPS-only optional hosts, and no widening of the
 * default Manifest V3 content security policy.
 *
 * Usage: node scripts/check-extension-package.ts [zip]
 * Without an argument it takes the one production zip under `extension/.output/`.
 */
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { unzipSync } from "fflate";

import { asRecord, asString, isRecord, parseJson, readJsonFileSync } from "./lib/json.ts";

const REPO_ROOT = path.resolve(import.meta.dirname, "..");
const OUTPUT = path.join(REPO_ROOT, "extension/.output");

/** What the privacy practices form justifies; a new permission is a new store review. */
export const PERMISSIONS = ["identity", "scripting", "storage"];
export const OPTIONAL_HOSTS = ["https://*/*"];
const ICON_SIZES = [16, 32, 48, 128];

/** Read only the requested entry; archive parsing belongs to the ZIP library. */
export function readZipEntry(archive: Buffer, name: string): Buffer | undefined {
	const data = unzipSync(archive, { filter: (entry) => entry.name === name })[name];
	return data === undefined ? undefined : Buffer.from(data);
}

const sameSet = (actual: unknown, expected: readonly string[]): boolean =>
	Array.isArray(actual) &&
	actual.length === expected.length &&
	actual.map(String).toSorted().join("\n") === [...expected].toSorted().join("\n");

type Manifest = Record<string, unknown>;

/** The release identity: Manifest V3, the root version, and no development key. */
function identityProblems(manifest: Manifest, releaseVersion: string): string[] {
	const problems: string[] = [];
	if (manifest.manifest_version !== 3) {
		problems.push("manifest_version must be 3");
	}
	if (manifest.version !== releaseVersion) {
		problems.push(
			`version must be the release version ${releaseVersion}, not ${JSON.stringify(manifest.version)}`,
		);
	}
	if (manifest.version_name !== undefined && manifest.version_name !== releaseVersion) {
		problems.push(
			`version_name must be absent or ${releaseVersion}, not ${JSON.stringify(manifest.version_name)}`,
		);
	}
	if ("key" in manifest) {
		problems.push("key must be absent: the store assigns the production id");
	}
	const { icons } = manifest;
	if (!isRecord(icons) || ICON_SIZES.some((size) => icons[String(size)] !== `icon/${size}.png`)) {
		problems.push("icons must declare the generated 16, 32, 48 and 128px brand assets");
	}
	return problems;
}

/** What the extension may reach: the justified permissions and user-granted HTTPS sites only. */
function permissionProblems(manifest: Manifest): string[] {
	const problems: string[] = [];
	if (!sameSet(manifest.permissions, PERMISSIONS)) {
		problems.push(`permissions must be exactly ${PERMISSIONS.join(", ")}`);
	}
	if ("optional_permissions" in manifest) {
		problems.push("optional_permissions must be absent: only optional host grants are justified");
	}
	if ("host_permissions" in manifest) {
		problems.push("host_permissions must be absent: every site is granted by the user at runtime");
	}
	if (!sameSet(manifest.optional_host_permissions, OPTIONAL_HOSTS)) {
		problems.push(`optional_host_permissions must be exactly ${OPTIONAL_HOSTS.join(", ")}`);
	}
	for (const key of ["externally_connectable", "content_scripts", "update_url", "sandbox"]) {
		if (key in manifest) {
			problems.push(`${key} must be absent`);
		}
	}
	return problems;
}

/** What pages may load of it, and what it may execute. */
function surfaceProblems(manifest: Manifest): string[] {
	const problems: string[] = [];
	if (!isRecord(manifest.background) || manifest.background.service_worker !== "background.js") {
		problems.push("background.service_worker must be background.js");
	}
	if (!isRecord(manifest.options_ui) || manifest.options_ui.page !== "options.html") {
		problems.push("options_ui.page must be options.html");
	}
	if ("side_panel" in manifest) {
		problems.push("side_panel must be absent: review context belongs on the provider page");
	}
	const minimum = manifest.minimum_chrome_version;
	if (
		typeof minimum !== "string" ||
		!/^\d+(?:\.\d+){0,3}$/u.test(minimum) ||
		Number(minimum.split(".")[0]) < 130
	) {
		problems.push("minimum_chrome_version must be at least 130 for dynamic inline resource URLs");
	}
	const resources = manifest.web_accessible_resources;
	const narrow = (entry: unknown): boolean =>
		isRecord(entry) &&
		sameSet(entry.resources, ["inline.html"]) &&
		sameSet(entry.matches, OPTIONAL_HOSTS) &&
		entry.use_dynamic_url === true &&
		!("extension_ids" in entry);
	if (!Array.isArray(resources) || resources.length !== 1 || !resources.every(narrow)) {
		problems.push(
			"web_accessible_resources must expose only inline.html to HTTPS pages through one dynamic URL entry",
		);
	}
	const policy = manifest.content_security_policy;
	if (policy === undefined) {
		return problems;
	}
	const pages = isRecord(policy) ? policy.extension_pages : undefined;
	if (
		typeof pages !== "string" ||
		!/^\s*script-src\s+'self'\s*;\s*object-src\s+'self'\s*;?\s*$/u.test(pages)
	) {
		problems.push(
			"content_security_policy.extension_pages must keep the default script-src and object-src policy",
		);
	}
	if (isRecord(policy) && "sandbox" in policy) {
		problems.push("content_security_policy.sandbox must be absent");
	}
	return problems;
}

/** Every way the manifest departs from the release, as sentences; empty when it does not. */
export function manifestProblems(value: unknown, releaseVersion: string): string[] {
	if (!isRecord(value)) {
		return ["manifest.json is not a JSON object"];
	}
	return [
		...identityProblems(value, releaseVersion),
		...permissionProblems(value),
		...surfaceProblems(value),
	];
}

/** Required surfaces and icons must be in the uploaded archive, not just the build directory. */
export function missingPackageAssets(archive: Buffer): string[] {
	return [
		"background.js",
		"content-scripts/provider.js",
		"options.html",
		"inline.html",
		"action.html",
		...ICON_SIZES.map((size) => `icon/${size}.png`),
	].filter((entry) => (readZipEntry(archive, entry)?.length ?? 0) === 0);
}

/** Obsolete privileged surfaces must not survive in a release archive after their UI is removed. */
export function unexpectedPackageAssets(archive: Buffer): string[] {
	return ["sidepanel.html"].filter((entry) => readZipEntry(archive, entry) !== undefined);
}

function productionZip(): string {
	const zips = readdirSync(OUTPUT).filter((file) => file.endsWith("-production-chrome.zip"));
	if (zips.length !== 1) {
		throw new Error(
			`Expected one production zip in extension/.output, found ${zips.length}; run vp run build:extension`,
		);
	}
	return path.join(OUTPUT, zips[0] ?? "");
}

function main(): void {
	const zip = process.argv[2] ?? productionZip();
	const archive = readFileSync(zip);
	const manifest = readZipEntry(archive, "manifest.json");
	if (manifest === undefined) {
		throw new Error(`${zip} has no manifest.json at its root`);
	}
	const releaseVersion = asString(
		asRecord(readJsonFileSync(path.join(REPO_ROOT, "package.json")), "package.json").version,
		"package.json#version",
	);
	const problems = [
		...manifestProblems(parseJson(manifest.toString("utf8")), releaseVersion),
		...missingPackageAssets(archive).map(
			(entry) => `required package asset is missing or empty: ${entry}`,
		),
		...unexpectedPackageAssets(archive).map((entry) => `unexpected package asset: ${entry}`),
	];
	if (problems.length > 0) {
		for (const problem of problems) {
			console.error(`${path.relative(REPO_ROOT, zip)}: ${problem}`);
		}
		process.exit(1);
	}
	console.log(
		`${path.relative(REPO_ROOT, zip)}: production manifest and required assets meet the ${releaseVersion} package contract.`,
	);
}

if (import.meta.main) {
	main();
}
