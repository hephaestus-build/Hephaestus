import assert from "node:assert/strict";
import { test } from "node:test";
import { zipSync } from "fflate";

import {
	OPTIONAL_HOSTS,
	PERMISSIONS,
	manifestProblems,
	missingPackageAssets,
	readZipEntry,
	unexpectedPackageAssets,
} from "./check-extension-package.ts";

const releasable = {
	manifest_version: 3,
	name: "Hephaestus",
	version: "0.80.0",
	version_name: "0.80.0",
	minimum_chrome_version: "130",
	background: { service_worker: "background.js" },
	options_ui: { page: "options.html", open_in_tab: true },
	icons: { "16": "icon/16.png", "32": "icon/32.png", "48": "icon/48.png", "128": "icon/128.png" },
	permissions: [...PERMISSIONS].toReversed(),
	optional_host_permissions: [...OPTIONAL_HOSTS],
	web_accessible_resources: [
		{ resources: ["inline.html"], matches: [...OPTIONAL_HOSTS], use_dynamic_url: true },
	],
};

void test("reads a stored and a deflated entry, and nothing that is not there", () => {
	const archive = Buffer.from(
		zipSync({
			"background.js": [Buffer.from("worker"), { level: 0 }],
			"manifest.json": [Buffer.from(JSON.stringify(releasable)), { level: 6 }],
		}),
	);
	assert.equal(readZipEntry(archive, "background.js")?.toString(), "worker");
	assert.deepEqual(JSON.parse(String(readZipEntry(archive, "manifest.json"))), releasable);
	assert.equal(readZipEntry(archive, "missing.json"), undefined);
	assert.throws(() => readZipEntry(Buffer.from("not a zip"), "manifest.json"));
});

void test("accepts the production manifest of the release version", () => {
	assert.deepEqual(manifestProblems(releasable, "0.80.0"), []);
	const { version_name: _, ...withoutName } = releasable;
	assert.deepEqual(manifestProblems(withoutName, "0.80.0"), []);
	assert.deepEqual(
		manifestProblems(
			{
				...releasable,
				content_security_policy: { extension_pages: "script-src 'self'; object-src 'self';" },
			},
			"0.80.0",
		),
		[],
	);
});

void test("rejects what a development or e2e build carries", () => {
	const cases: [string, Record<string, unknown>][] = [
		["version", { version: "0.0.0" }],
		["version_name", { version_name: "0.80.0-development" }],
		["key", { key: "MIIBIjAN" }],
		["icons", { icons: { "128": "remote-or-missing.png" } }],
		["host_permissions", { host_permissions: ["https://github.com/*"] }],
		[
			"optional_host_permissions",
			{ optional_host_permissions: ["https://*/*", "http://localhost/*"] },
		],
		["permissions", { permissions: [...PERMISSIONS, "cookies"] }],
		["optional_permissions", { optional_permissions: ["cookies"] }],
		["background", { background: { service_worker: "missing.js" } }],
		["options_ui", { options_ui: { page: "other.html" } }],
		["side_panel", { side_panel: { default_path: "other.html" } }],
		["minimum_chrome_version", { minimum_chrome_version: "129" }],
		["externally_connectable", { externally_connectable: { matches: ["https://*/*"] } }],
		[
			"web_accessible_resources",
			{ web_accessible_resources: [{ resources: ["inline.html"], matches: ["<all_urls>"] }] },
		],
		[
			"content_security_policy",
			{ content_security_policy: { extension_pages: "script-src 'self' 'wasm-unsafe-eval'" } },
		],
		[
			"content_security_policy",
			{
				content_security_policy: {
					extension_pages: "script-src https://remote.test; script-src 'self'; object-src 'self'",
				},
			},
		],
		[
			"content_security_policy",
			{
				content_security_policy: {
					extension_pages:
						"script-src 'self'; object-src 'self'; script-src-elem https://remote.test",
				},
			},
		],
	];
	for (const [field, change] of cases) {
		const problems = manifestProblems({ ...releasable, ...change }, "0.80.0");
		assert.equal(problems.length, 1, `${field}: ${problems.join("; ")}`);
		assert.ok((problems[0] ?? "").startsWith(field), `${field}: ${problems.join("; ")}`);
	}
	assert.deepEqual(manifestProblems([], "0.80.0"), ["manifest.json is not a JSON object"]);
});

void test("rejects missing packaged icons and empty extension pages", () => {
	const assets = {
		"background.js": Buffer.from("worker"),
		"content-scripts/provider.js": Buffer.from("provider"),
		"options.html": Buffer.from("options"),
		"inline.html": Buffer.from("inline"),
		"action.html": Buffer.from("confirmation"),
		"icon/16.png": Buffer.from("icon"),
		"icon/32.png": Buffer.from("icon"),
		"icon/48.png": Buffer.from("icon"),
		"icon/128.png": Buffer.from("icon"),
	};
	assert.deepEqual(missingPackageAssets(Buffer.from(zipSync(assets))), []);
	assert.deepEqual(unexpectedPackageAssets(Buffer.from(zipSync(assets))), []);
	assert.deepEqual(
		unexpectedPackageAssets(
			Buffer.from(zipSync({ ...assets, "sidepanel.html": Buffer.from("obsolete") })),
		),
		["sidepanel.html"],
	);
	const { "content-scripts/provider.js": _provider, ...withoutProviderScript } = assets;
	assert.deepEqual(missingPackageAssets(Buffer.from(zipSync(withoutProviderScript))), [
		"content-scripts/provider.js",
	]);
	const { "icon/128.png": _, ...withoutStoreIcon } = assets;
	const { "action.html": _action, ...withoutConfirmation } = assets;
	assert.deepEqual(missingPackageAssets(Buffer.from(zipSync(withoutConfirmation))), [
		"action.html",
	]);
	assert.deepEqual(
		missingPackageAssets(
			Buffer.from(zipSync({ ...withoutStoreIcon, "inline.html": Buffer.alloc(0) })),
		),
		["inline.html", "icon/128.png"],
	);
});

void test("rejects public resources that expose privileged pages or broaden access", () => {
	const inline = {
		resources: ["inline.html"],
		matches: [...OPTIONAL_HOSTS],
		use_dynamic_url: true,
	};
	const unsafe: unknown[] = [
		undefined,
		[],
		[inline, inline],
		[{ ...inline, resources: ["*"] }],
		[{ ...inline, resources: ["inline.html", "options.html"] }],
		[{ ...inline, resources: ["inline.html", "action.html"] }],
		[{ ...inline, resources: ["sidepanel.html"] }],
		[{ ...inline, resources: undefined }],
		[{ ...inline, extension_ids: ["*"] }],
	];
	for (const resources of unsafe) {
		assert.match(
			manifestProblems({ ...releasable, web_accessible_resources: resources }, "0.80.0").join("; "),
			/web_accessible_resources/u,
		);
	}
});
