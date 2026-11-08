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

async function deploymentFor(runtime: Window["__ENV__"]) {
	const { deployment } = await environmentFor(runtime);
	return deployment;
}

afterEach(() => {
	vi.unstubAllEnvs();
	delete window.__ENV__;
});

describe("preview pull request", () => {
	it.each([
		["pr2042.hephaestus.example.com", 2042],
		["pr-2042.hephaestus.example.com", 2042],
	])("reads the number out of %s, which is the only place it appears", async (host, expected) => {
		const deployment = await deploymentFor({
			DEPLOYMENT_ENVIRONMENT: "preview",
			APPLICATION_CLIENT_URL: `https://${host}`,
		});

		expect(deployment.pullRequest).toBe(expected);
	});

	it("gives a deployment that is not a preview no pull request, whatever it is named", async () => {
		const deployment = await deploymentFor({
			DEPLOYMENT_ENVIRONMENT: "staging",
			APPLICATION_CLIENT_URL: "https://pr2042.hephaestus.example.com",
		});

		expect(deployment.pullRequest).toBeUndefined();
	});

	it.each([
		"https://hephaestus.example.com",
		"https://pr.example.com",
		"https://pr2042x.example.com",
		"not a url",
		"",
	])("leaves the pull request unset for %s", async (clientUrl) => {
		const deployment = await deploymentFor({
			DEPLOYMENT_ENVIRONMENT: "preview",
			APPLICATION_CLIENT_URL: clientUrl,
		});

		expect(deployment.pullRequest).toBeUndefined();
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
		expect(environment.devtools.tanstack).toBe(false);
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

	it("keeps separate development origins for the local webapp and server", async () => {
		vi.stubEnv("DEV", true);
		const environment = await environmentFor(undefined);
		expect(environment.clientUrl).toBe("http://localhost:4200");
		expect(environment.serverUrl).toBe("http://localhost:8080");
		expect(environment.devtools.tanstack).toBe(true);
	});
});
