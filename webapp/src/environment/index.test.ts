import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * The module reads `window.__ENV__` once, at import, so each case loads it afresh.
 */
async function environmentFor(runtime: Window["__ENV__"]) {
	vi.resetModules();
	window.__ENV__ = runtime;
	return (await import("./index")).default;
}

async function deploymentFor(runtime: Window["__ENV__"]) {
	return (await environmentFor(runtime)).deployment;
}

afterEach(() => {
	delete window.__ENV__;
});

describe("preview pull request", () => {
	it.each([
		["pr2042.hephaestus.example.com", 2042],
		["pr-2042.hephaestus.example.com", 2042],
	])("reads the number out of %s, which is the only place it appears", async (host, expected) => {
		const deployment = await deploymentFor({
			SENTRY_ENVIRONMENT: "preview",
			APPLICATION_CLIENT_URL: `https://${host}`,
		});

		expect(deployment.pullRequest).toBe(expected);
	});

	it("gives a deployment that is not a preview no pull request, whatever it is named", async () => {
		const deployment = await deploymentFor({
			SENTRY_ENVIRONMENT: "staging",
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
			SENTRY_ENVIRONMENT: "preview",
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
			SENTRY_ENVIRONMENT: "production",
			XSRF_COOKIE_NAME: "",
		});

		expect(environment.xsrfCookieName).toBe("__Host-XSRF-TOKEN");
	});

	it("keeps a name the deployment does set, which local http E2E needs without the __Host- prefix", async () => {
		const environment = await environmentFor({
			SENTRY_ENVIRONMENT: "local",
			XSRF_COOKIE_NAME: "XSRF-TOKEN",
		});

		expect(environment.xsrfCookieName).toBe("XSRF-TOKEN");
	});
});
