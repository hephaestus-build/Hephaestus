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
 * Finds an instance's API, which every server serves under `/api` of its origin. A development build on a
 * loopback origin may name a separate web app origin, for a web app served by its own development server; every
 * link out goes there, never to the API server. Otherwise the web app is the instance origin.
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
	if (!(await answersAsApi(`${origin}/api`))) {
		throw new WorkerError(
			"invalid",
			"No Hephaestus instance answered at that address. Check the address and that you allowed access in Chrome.",
		);
	}
	const apiBase = `${origin}/api`;
	if (
		!developmentBuild ||
		!isLoopbackHost(new URL(origin).hostname) ||
		input.webAppOrigin === undefined
	) {
		return { origin, apiBase, webAppOrigin: origin };
	}
	const webApp = parseInstanceOrigin(input.webAppOrigin, { allowLoopbackHttp: true });
	if (!webApp.ok) {
		throw new WorkerError("invalid", INSTANCE_ORIGIN_MESSAGES[webApp.reason]);
	}
	return { origin, apiBase, webAppOrigin: webApp.origin };
}

const addressConfigSchema = z.object({
	apexOrigin: z.url({ protocol: /^https$/u }),
	baseDomain: z.string().min(1),
	workspaceSubdomainsEnabled: z.literal(true),
});

/** Read the served configuration, not a guessed registrable domain. No credentials cross this boundary. */
export async function resolveInstanceOrigin(
	input: string,
	developmentBuild: boolean,
): Promise<string> {
	const parsed = parseInstanceOrigin(input, { allowLoopbackHttp: developmentBuild });
	if (!parsed.ok) {
		throw new WorkerError("invalid", INSTANCE_ORIGIN_MESSAGES[parsed.reason]);
	}
	const { origin } = parsed;
	try {
		const body: unknown = await publicApi.runtimeConfig(origin);
		const config = addressConfigSchema.safeParse(body);
		if (!config.success) {
			return origin;
		}
		const apex = new URL(config.data.apexOrigin);
		const supplied = new URL(origin);
		const suffix = `.${config.data.baseDomain}`;
		const label = supplied.hostname.slice(0, -suffix.length);
		if (
			apex.origin !== config.data.apexOrigin ||
			apex.hostname !== config.data.baseDomain ||
			apex.port !== "" ||
			!supplied.hostname.endsWith(suffix) ||
			!/^(?!.*--)[a-z0-9][a-z0-9-]{1,49}[a-z0-9]$/u.test(label)
		) {
			return origin;
		}
		return apex.origin;
	} catch {
		return origin;
	}
}
