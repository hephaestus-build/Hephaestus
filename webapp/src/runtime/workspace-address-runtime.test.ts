import { afterEach, expect, it, vi } from "vitest";

import environment from "@/environment";
import { workspaceAddressConfig, workspaceReturnTo } from "./workspace-address";

const originalClientUrl = environment.clientUrl;
const originalEnabled = workspaceAddressConfig.enabled;
afterEach(() => {
	workspaceAddressConfig.enabled = originalEnabled;
	environment.clientUrl = originalClientUrl;
	vi.restoreAllMocks();
});

it("does not require an apex URL when workspace addresses are off", async () => {
	vi.resetModules();
	const addresses = await import("./workspace-address");
	expect(addresses.workspaceAddressConfig.apexOrigin).toBe("");
	expect(addresses.workspaceReturnTo("/settings")).toBe("/settings");
});

it("rejects a return path that becomes an authority after URL normalization", () => {
	workspaceAddressConfig.enabled = true;
	expect(workspaceReturnTo("/x/..//evil.com")).toBe("/");
});
