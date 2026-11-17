import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * The module reads `window.__ENV__` once, at import, so each case loads it afresh.
 */
async function environmentFor(runtime: Window["__ENV__"]) {
	vi.resetModules();
	window.__ENV__ = runtime;
	const { default: environment } = await import("./index");
	return environment;
}

afterEach(() => {
	vi.unstubAllEnvs();
	delete window.__ENV__;
});

describe("deployment name", () => {
	it.each([
		["staging", "Staging"],
		["preview", "Preview"],
		["capacity-bench", "Capacity-bench"],
	])("names the %s deployment %s, never Local", async (deploymentEnvironment, name) => {
		const { deployment } = await environmentFor({ DEPLOYMENT_ENVIRONMENT: deploymentEnvironment });

		expect(deployment.name).toBe(name);
	});
});

describe("CSRF cookie name", () => {
	it("falls back to the secure cookie the server sets when the deployment leaves it unset", async () => {
		// The entrypoint writes every key of RuntimeEnvVars into window.__ENV__ whether the container
		// has it or not, so an unset variable arrives as "", which the `??` default cannot rescue.
		const environment = await environmentFor({
			DEPLOYMENT_ENVIRONMENT: "production",
			XSRF_COOKIE_NAME: "",
		});

		expect(environment.xsrfCookieName).toBe("__Host-XSRF-TOKEN");
	});

	it("keeps a name the deployment does set, which local http E2E needs without the __Host- prefix", async () => {
		const environment = await environmentFor({
			DEPLOYMENT_ENVIRONMENT: "local",
			XSRF_COOKIE_NAME: "XSRF-TOKEN",
		});

		expect(environment.xsrfCookieName).toBe("XSRF-TOKEN");
	});
});

describe("runtime configuration defaults", () => {
	it("keeps a production build on its own origin when the runtime script is unavailable", async () => {
		vi.stubEnv("DEV", false);
		const environment = await environmentFor(undefined);
		expect(environment.clientUrl).toBe(window.location.origin);
		expect(environment.serverUrl).toBe(`${window.location.origin}/api`);
		expect(environment.deployment.environment).toBe("unknown");
		expect(environment.deployment.name).toBe("Unknown");
	});

	it("uses the explicit runtime URLs when the script is available", async () => {
		vi.stubEnv("DEV", false);
		const environment = await environmentFor({
			APPLICATION_CLIENT_URL: "https://app.example.test",
			APPLICATION_SERVER_URL: "https://app.example.test/api",
			DEPLOYMENT_ENVIRONMENT: "staging",
		});
		expect(environment.clientUrl).toBe("https://app.example.test");
		expect(environment.serverUrl).toBe("https://app.example.test/api");
		expect(environment.deployment.environment).toBe("staging");
	});

	it("reaches the API through the development server's own origin", async () => {
		vi.stubEnv("DEV", true);
		const environment = await environmentFor(undefined);
		expect(environment.clientUrl).toBe("http://localhost:4200");
		expect(environment.serverUrl).toBe(`${window.location.origin}/api`);
	});
});
