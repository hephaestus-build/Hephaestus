import { z } from "zod";

import { publicApi } from "~/background/api";
import type { InstanceConfig } from "~/background/storage";
import type { ServerTooOld } from "~/shared/rpc";
import { isOlderThanSupported, OLDEST_SERVER_VERSION } from "~/shared/server-version";

const discoverySchema = z.object({ version: z.string().max(64).optional() });

const READ_TIMEOUT_MS = 3000;

/**
 * Reads whether the extension supports this server, from the discovery file that every release of
 * the web app serves. The file names its version from the first release after 1.0.0-rc.1, so a file
 * without one is that prerelease or older, and neither is supported. A file that cannot be read in
 * time says nothing about age, and neither does a version longer than any release: the calls after
 * this one report what is wrong. The wait is short because sign-in and the report wait for it.
 */
export async function serverTooOld(
	instance: InstanceConfig,
	timeoutMs = READ_TIMEOUT_MS,
): Promise<ServerTooOld | undefined> {
	let body: unknown;
	try {
		body = await publicApi.runtimeConfig(instance.origin, AbortSignal.timeout(timeoutMs));
	} catch {
		return undefined;
	}
	const discovery = discoverySchema.safeParse(body);
	if (!discovery.success) {
		return undefined;
	}
	const { version } = discovery.data;
	if (version === undefined) {
		return { oldestServer: OLDEST_SERVER_VERSION };
	}
	return isOlderThanSupported(version)
		? { serverVersion: version, oldestServer: OLDEST_SERVER_VERSION }
		: undefined;
}
