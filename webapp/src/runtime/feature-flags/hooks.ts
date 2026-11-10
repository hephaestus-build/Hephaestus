import { queryOptions, useQuery } from "@tanstack/react-query";
import { ZodError, z } from "zod";

import { getUserFeaturesQueryKey } from "@/api/@tanstack/react-query.gen";
import { getUserFeatures } from "@/api/sdk.gen";
import type { FeatureFlags } from "@/api/types.gen";
import { useAuth } from "@/runtime/auth/AuthContext";
import { retryQuery } from "@/runtime/tanstack-query/query-defaults";

export type FeatureFlagName = keyof FeatureFlags;

/**
 * Spelled out rather than imported from `@/api/zod.gen`: this module is in the entry bundle, and a
 * lazy route also imports schemas from that one generated module. Rolldown cannot split a module
 * across chunks, so the import would move every schema that any route uses into the entry.
 * `satisfies` fails the build when `FeatureFlags` gains a flag that this list lacks. A flag the
 * server adds later is dropped, not rejected.
 */
const featureFlagsSchema = z.object({
	ADMIN: z.boolean(),
	PAGE_EXPERIENCE_METRICS: z.boolean(),
}) satisfies z.ZodType<FeatureFlags>;

/** A malformed answer is a failed read, never a set of closed gates. */
async function fetchFeatureFlags(): Promise<FeatureFlags> {
	const { data } = await getUserFeatures({ throwOnError: true });
	return featureFlagsSchema.parse(data);
}

// The administrator's flags never apply in a view.
const USER_VIEW_FLAGS: FeatureFlags = {
	ADMIN: false,
	PAGE_EXPERIENCE_METRICS: false,
};

/** The signed-in reader's flags. Outside React, read them through the query client with these. */
export const featureFlagsQueryOptions = queryOptions({
	queryKey: getUserFeaturesQueryKey(),
	queryFn: fetchFeatureFlags,
	staleTime: 60_000,
	// A malformed answer reads the same on every retry.
	retry: (failureCount, error) => !(error instanceof ZodError) && retryQuery(failureCount, error),
});

function useFeatureFlagsQuery(): {
	data: FeatureFlags | undefined;
	isLoading: boolean;
	isError: boolean;
} {
	const { isAuthenticated, userView } = useAuth();

	const query = useQuery({
		...featureFlagsQueryOptions,
		enabled: isAuthenticated && !userView,
	});
	return userView ? { data: USER_VIEW_FLAGS, isLoading: false, isError: false } : query;
}

export function useFeatureFlags() {
	const { data, isLoading, isError } = useFeatureFlagsQuery();

	return {
		flags: data,
		isLoading,
		isError,
	};
}
