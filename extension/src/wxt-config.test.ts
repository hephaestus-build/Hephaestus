import { describe, expect, it } from "vitest";
import type { WxtCommand } from "wxt";

// oxlint-disable-next-line import/no-relative-parent-imports -- This test verifies the actual build configuration outside the browser source tree.
import config from "../wxt.config";

async function manifestFor(mode: string, command: WxtCommand) {
	if (typeof config.manifest !== "function") {
		throw new Error("Expected the mode-specific manifest");
	}
	const manifest = await config.manifest({ mode, command, browser: "chrome", manifestVersion: 3 });
	return { ...manifest, manifest_version: 3 as const };
}

/** WXT calls this after adding its development-server host to the generated manifest. */
function generated(command: WxtCommand, manifest: Awaited<ReturnType<typeof manifestFor>>) {
	const hook: unknown = Reflect.get(config.hooks ?? {}, "build:manifestGenerated");
	if (typeof hook !== "function") {
		throw new Error("Expected the generated-manifest hook");
	}
	// This boundary supplies only the WXT configuration that the hook consumes, not a running server.
	Reflect.apply(hook, undefined, [{ config: { command } }, manifest]);
	return manifest;
}

describe("WXT development-server permissions", () => {
	it.each([
		["http://localhost/*", "http://127.0.0.1/*"],
		["http://127.0.0.1/*", "http://localhost/*"],
	])(
		"keeps %s required once after WXT adds its actual server origin",
		async (origin, otherLoopback) => {
			const manifest = await manifestFor("development", "serve");
			manifest.host_permissions = [origin];
			const result = generated("serve", manifest);
			expect(result.host_permissions).toStrictEqual([origin]);
			expect(result.optional_host_permissions).not.toContain(origin);
			expect(result.optional_host_permissions).toContain("https://*/*");
			expect(result.optional_host_permissions).toContain(otherLoopback);
			expect(result.web_accessible_resources).toStrictEqual([
				{
					resources: ["inline.html"],
					matches: ["https://*/*", "http://localhost/*", "http://127.0.0.1/*"],
					use_dynamic_url: true,
				},
			]);
		},
	);

	const LOOPBACK = ["http://localhost/*", "http://127.0.0.1/*"];
	it.each([
		["development", ["https://*/*", ...LOOPBACK], undefined],
		[
			"e2e",
			["https://*/*", ...LOOPBACK],
			["https://github.com/*", "https://gitlab.example.test/*", ...LOOPBACK],
		],
		["production", ["https://*/*"], undefined],
	])("preserves the complete static %s permission contract", async (mode, optionalHosts, hosts) => {
		const manifest = await manifestFor(mode, "build");
		const before = structuredClone(manifest);
		expect(generated("build", manifest)).toStrictEqual(before);
		expect(manifest.permissions).toStrictEqual(["storage", "identity", "scripting"]);
		expect(manifest.optional_host_permissions).toStrictEqual(optionalHosts);
		expect(manifest.host_permissions).toStrictEqual(hosts);
	});
});
