import { z } from "zod";

/**
 * The config record that the server writes into `index.html` (ADR 0100; server `core.webapp.WebAppConfig`). The
 * development server serves the page without one, so every value has a default for a run on this origin.
 */
const configRecord = z.object({
	version: z.string(),
	clientUrl: z.string(),
	serverUrl: z.string(),
	workspaceSubdomains: z.object({ enabled: z.boolean(), baseDomain: z.string() }),
	xsrfCookieName: z.string(),
	deploymentEnvironment: z.string(),
	commit: z.string(),
	browserFaultReports: z.boolean(),
});

/** The element's text is JSON from this deployment's server. A record that does not parse is a packaging fault. */
function readConfigRecord(): z.infer<typeof configRecord> | undefined {
	const text = document.querySelector("script#hephaestus-config")?.textContent ?? "";
	return text === "" ? undefined : configRecord.parse(JSON.parse(text));
}

const record = readConfigRecord();

/** The CSRF double-submit cookie of a deployment, which the `prod` profile requires to be secure. */
const SECURE_XSRF_COOKIE_NAME = "__Host-XSRF-TOKEN";

const DEPLOYMENT_NAMES: Record<string, string> = {
	unknown: "Unknown",
	production: "Production",
	staging: "Staging",
	local: "Local",
};
const deploymentEnvironment =
	record?.deploymentEnvironment ?? (import.meta.env.DEV ? "local" : "unknown");
const version = record?.version.replace(/^v/u, "") ?? "";

const environment = {
	version: version === "" ? "DEV" : version,
	deployment: {
		environment: deploymentEnvironment,
		// An operator can name a deployment anything, such as `dev`: the header shows that name, never a wrong one.
		name:
			DEPLOYMENT_NAMES[deploymentEnvironment] ??
			deploymentEnvironment.charAt(0).toUpperCase() + deploymentEnvironment.slice(1),
		isProduction: deploymentEnvironment === "production",
	},
	workspaceSubdomains: {
		enabled: record?.workspaceSubdomains.enabled ?? false,
		baseDomain: record?.workspaceSubdomains.baseDomain ?? "",
		get apexOrigin(): string {
			return this.enabled ? new URL(environment.clientUrl).origin : "";
		},
	},
	clientUrl: record?.clientUrl ?? window.location.origin,
	// The development server proxies `/api` to this worktree's server.
	serverUrl: record?.serverUrl ?? `${window.location.origin}/api`,
	// `__Host-`-prefixed in production; dropped for local http E2E, where the browser rejects `__Host-` cookies.
	xsrfCookieName: record?.xsrfCookieName ?? SECURE_XSRF_COOKIE_NAME,

	buildInfo: {
		commit: record?.commit ?? "",
	},

	/** The operator's switch for browser fault reports. Anonymous pages read it here, not from the reader's flags. */
	browserFaultReports: record?.browserFaultReports ?? false,
};

export default environment;
