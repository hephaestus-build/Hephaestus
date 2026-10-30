import { afterEach, describe, expect, it, vi } from "vitest";

import { publicApi } from "~/background/api";
import { resolveInstanceOrigin } from "./instance";

vi.mock("~/background/api", () => ({ publicApi: { runtimeConfig: vi.fn() } }));
afterEach(() => vi.resetAllMocks());

const config = {
	apexOrigin: "https://hephaestus.example.com",
	baseDomain: "hephaestus.example.com",
	workspaceSubdomainsEnabled: true,
};

describe("workspace address discovery", () => {
	it("maps a pasted tenant address to the configured apex, not the public suffix", async () => {
		vi.mocked(publicApi.runtimeConfig).mockResolvedValue(config);
		await expect(
			resolveInstanceOrigin("https://acme.hephaestus.example.com/activity?range=1y", false),
		).resolves.toBe("https://hephaestus.example.com");
		expect(publicApi.runtimeConfig).toHaveBeenCalledWith("https://acme.hephaestus.example.com");
	});
	it.each([
		{ ...config, workspaceSubdomainsEnabled: false },
		{ ...config, apexOrigin: "https://evil.test" },
		{ ...config, apexOrigin: "https://hephaestus.example.com/path" },
		{ ...config, baseDomain: "example.com" },
	])("does not accept inconsistent runtime configuration %j", async (metadata) => {
		vi.mocked(publicApi.runtimeConfig).mockResolvedValue(metadata);
		await expect(resolveInstanceOrigin("https://acme.hephaestus.example.com", false)).resolves.toBe(
			"https://acme.hephaestus.example.com",
		);
	});
	it("preserves an apex with no discovery document", async () => {
		vi.mocked(publicApi.runtimeConfig).mockRejectedValue(new Error("not found"));
		await expect(resolveInstanceOrigin("https://hephaestus.example.com", false)).resolves.toBe(
			"https://hephaestus.example.com",
		);
	});
	it("rejects an insecure pasted address before discovery", async () => {
		await expect(
			resolveInstanceOrigin("http://acme.hephaestus.example.com", false),
		).rejects.toThrow("https://");
		expect(publicApi.runtimeConfig).not.toHaveBeenCalled();
	});
});
