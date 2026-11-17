import type { BrowserContext } from "@playwright/test";

const ELEMENT = /<script type="application\/json" id="hephaestus-config">.*?<\/script>/su;

/**
 * Points the page at a fixture API: every page of `appOrigin` gets a config record whose `serverUrl` is `api`, in
 * place of the one the server wrote, or added when a static preview serves the page without one.
 */
export async function useFixtureApi(
	context: BrowserContext,
	appOrigin: string,
	api: string,
): Promise<void> {
	const record = {
		version: "",
		clientUrl: appOrigin,
		serverUrl: api,
		workspaceSubdomains: { enabled: false, baseDomain: "" },
		xsrfCookieName: "XSRF-TOKEN",
		// A build without a deployment name shows "Unknown" in the header, which reads as a fault.
		deploymentEnvironment: "local",
		commit: "",
		browserFaultReports: false,
	};
	const element = `<script type="application/json" id="hephaestus-config">${JSON.stringify(record).replaceAll("<", "\\u003c")}</script>`;
	await context.route(
		(url) => url.origin === appOrigin,
		async (route) => {
			if (route.request().resourceType() !== "document") {
				return route.fallback();
			}
			const response = await route.fetch();
			const page = (await response.text())
				.replace(ELEMENT, "")
				.replace("</head>", `${element}</head>`);
			return route.fulfill({ response, body: page });
		},
	);
}
