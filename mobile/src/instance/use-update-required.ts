import { useQuery } from "@tanstack/react-query";

import { getNativeClientConfigurationOptions } from "@/api/@tanstack/react-query.gen";

import { APP_VERSION, publicClient } from "./instance";
import { isOlderThan } from "./instance-url";

/**
 * Whether the signed-in instance has since retired this app version. A server raises its minimum
 * after the app signed in, so this is asked again on every launch and return to the foreground;
 * without an answer, offline or otherwise, the app carries on.
 */
export function useUpdateRequired(apiBaseUrl: string): boolean {
	const { data } = useQuery({
		...getNativeClientConfigurationOptions({ client: publicClient(apiBaseUrl) }),
		enabled: apiBaseUrl !== "",
	});
	return data !== undefined && isOlderThan(APP_VERSION, data.minimumAppVersion);
}
