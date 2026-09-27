import { readFileSync } from "node:fs";

import { defineConfig } from "wxt";
import rootPackage from "../package.json" with { type: "json" };

import { extensionAliases, extensionVitePlugins } from "./vite.shared.ts";

/**
 * Three builds from one source. `production` is what ships: HTTPS only, no manifest `key`, no host
 * permission until the user grants one. `development` adds the fixed development identity and
 * loopback HTTP so a local server works. `e2e` is `development` plus pre-granted fixture origins,
 * because Playwright cannot answer Chrome's permission prompt.
 */
const DEVELOPMENT_MODES = new Set(["development", "e2e"]);
const LOOPBACK_ORIGINS = ["http://localhost/*", "http://127.0.0.1/*"];
const E2E_FIXTURE_ORIGINS = ["https://github.com/*", "https://gitlab.example.test/*"];

// The public half of the development key pins the extension id to ijkajblcbajjpjbknfgdiiiljipafiko,
// the id the development and e2e server profiles allowlist. It is not a secret.
const developmentKey = readFileSync(new URL("dev-key.txt", import.meta.url), "utf8").trim();

export default defineConfig({
	srcDir: "src",
	outDir: ".output",
	imports: false,
	webExt: { disabled: true },
	hooks: {
		"build:manifestGenerated": (wxt, manifest) => {
			if (wxt.config.command === "serve" && manifest.manifest_version === 3) {
				// WXT grants its dev-server origin for HMR. Chrome warns if that same host is optional.
				manifest.optional_host_permissions = manifest.optional_host_permissions?.filter(
					(origin) => !(manifest.host_permissions ?? []).includes(origin),
				);
			}
		},
	},
	alias: extensionAliases,
	vite: () => ({ plugins: extensionVitePlugins() }),
	zip: {
		artifactTemplate: "hephaestus-{{version}}-{{mode}}-chrome.zip",
		includeSources: [],
	},
	manifest: ({ mode }) => {
		const development = DEVELOPMENT_MODES.has(mode);
		const webOrigins = development ? ["https://*/*", ...LOOPBACK_ORIGINS] : ["https://*/*"];
		return {
			name: "Hephaestus",
			description:
				"See Hephaestus practice reviews for the pull request, merge request or issue you are viewing on GitHub or GitLab.",
			version: rootPackage.version,
			version_name: development ? `${rootPackage.version}-${mode}` : rootPackage.version,
			// Chrome 130 serves `use_dynamic_url` resources through `runtime.getURL`.
			minimum_chrome_version: "130",
			permissions: ["storage", "identity", "scripting"],
			optional_host_permissions: webOrigins,
			...(mode === "e2e"
				? { host_permissions: [...E2E_FIXTURE_ORIGINS, ...LOOPBACK_ORIGINS] }
				: {}),
			...(development ? { key: developmentKey } : {}),
			action: { default_title: "Hephaestus" },
			web_accessible_resources: [
				{ resources: ["inline.html"], matches: webOrigins, use_dynamic_url: true },
			],
		};
	},
});
