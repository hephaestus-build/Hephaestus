import { afterEach, describe, expect, it, vi } from "vitest";

const record = {
	version: "v1.2.3",
	clientUrl: "https://app.example.test",
	serverUrl: "https://app.example.test/api",
	workspaceSubdomains: { enabled: false, baseDomain: "app.example.test" },
	xsrfCookieName: "__Host-XSRF-TOKEN",
	deploymentEnvironment: "staging",
	commit: "a".repeat(40),
	browserFaultReports: true,
	publicUsage: true,
};

/** The module reads the config record once, at import, so each case loads it afresh. */
async function environmentFor(config: unknown) {
	vi.resetModules();
	document.head.replaceChildren();
	if (config !== undefined) {
		const element = document.createElement("script");
		element.type = "application/json";
		element.id = "hephaestus-config";
		element.textContent = JSON.stringify(config);
		document.head.append(element);
	}
	const { default: environment } = await import("./index");
	return environment;
}

afterEach(() => {
	vi.unstubAllEnvs();
	document.head.replaceChildren();
});

describe("config record", () => {
	it("takes every value from the record that the server writes into the page", async () => {
		const environment = await environmentFor(record);

		expect(environment.version).toBe("1.2.3");
		expect(environment.clientUrl).toBe("https://app.example.test");
		expect(environment.serverUrl).toBe("https://app.example.test/api");
		expect(environment.deployment.name).toBe("Staging");
		expect(environment.buildInfo.commit).toBe("a".repeat(40));
		expect(environment.browserFaultReports).toBe(true);
		expect(environment.publicUsage).toBe(true);
	});

	it("refuses a record that does not have the server's shape", async () => {
		await expect(environmentFor({ ...record, serverUrl: 42 })).rejects.toThrow(/serverUrl/u);
	});
});

describe("deployment name", () => {
	it.each([
		["preview", "Preview"],
		["capacity-bench", "Capacity-bench"],
	])("names the %s deployment %s, never Local", async (deploymentEnvironment, name) => {
		const { deployment } = await environmentFor({ ...record, deploymentEnvironment });

		expect(deployment.name).toBe(name);
	});
});

describe("CSRF cookie name", () => {
	it("keeps the name the server sets, which local http E2E needs without the __Host- prefix", async () => {
		const environment = await environmentFor({ ...record, xsrfCookieName: "XSRF-TOKEN" });

		expect(environment.xsrfCookieName).toBe("XSRF-TOKEN");
	});
});

describe("without a config record", () => {
	it("keeps a production build on its own origin", async () => {
		vi.stubEnv("DEV", false);
		const environment = await environmentFor(undefined);

		expect(environment.clientUrl).toBe(window.location.origin);
		expect(environment.serverUrl).toBe(`${window.location.origin}/api`);
		expect(environment.xsrfCookieName).toBe("__Host-XSRF-TOKEN");
		expect(environment.deployment.name).toBe("Unknown");
	});

	it("names the development server's run Local", async () => {
		vi.stubEnv("DEV", true);
		const environment = await environmentFor(undefined);

		expect(environment.deployment.name).toBe("Local");
		expect(environment.version).toBe("DEV");
	});
});
