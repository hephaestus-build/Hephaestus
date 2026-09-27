import { z } from "zod";

import { publicApi } from "~/background/api";
import { WorkerError } from "~/background/errors";
import type { InstanceConfig } from "~/background/storage";
import {
	INSTANCE_ORIGIN_MESSAGES,
	isLoopbackHost,
	parseInstanceOrigin,
} from "~/shared/instance-url";

// Just enough of `/identity-providers` to tell the API from a web app's HTML fallback page.
const providerListSchema = z.array(z.object({ registrationId: z.string().optional() }));

async function answersAsApi(apiBase: string): Promise<boolean> {
	try {
		return providerListSchema.safeParse(await publicApi.identityProviders(apiBase)).success;
	} catch {
		return false;
	}
}

/**
 * Finds where an instance's API answers. A deployed instance serves the web app at its origin and
 * the API under `/api`; a local development server is the API alone at a loopback origin, with the
 * web app somewhere else — which is why the web app origin is its own fact, typed by the developer,
 * and every link out goes there, never to the API server.
 */
export async function discoverInstance(
	input: { origin: string; webAppOrigin?: string },
	developmentBuild: boolean,
): Promise<InstanceConfig> {
	const parsed = parseInstanceOrigin(input.origin, { allowLoopbackHttp: developmentBuild });
	if (!parsed.ok) {
		throw new WorkerError("invalid", INSTANCE_ORIGIN_MESSAGES[parsed.reason]);
	}
	const { origin } = parsed;
	if (await answersAsApi(`${origin}/api`)) {
		return { origin, apiBase: `${origin}/api`, webAppOrigin: origin };
	}
	const loopback = isLoopbackHost(new URL(origin).hostname);
	if (developmentBuild && loopback && (await answersAsApi(origin))) {
		const webApp =
			input.webAppOrigin === undefined
				? undefined
				: parseInstanceOrigin(input.webAppOrigin, { allowLoopbackHttp: true });
		if (webApp?.ok !== true) {
			throw new WorkerError(
				"invalid",
				"This is a local API server. Enter the address the web app runs at as well.",
			);
		}
		return { origin, apiBase: origin, webAppOrigin: webApp.origin };
	}
	throw new WorkerError(
		"invalid",
		"No Hephaestus instance answered at that address. Check it and that access was granted.",
	);
}
