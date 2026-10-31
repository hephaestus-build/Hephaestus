import { queryOptions, useQuery } from "@tanstack/react-query";
import { z } from "zod";

import { getUserFeaturesQueryKey } from "@/api/@tanstack/react-query.gen";
import { getUserFeatures } from "@/api/sdk.gen";
import type { FeatureFlags } from "@/api/types.gen";
import { useAuth } from "@/runtime/auth/AuthContext";
import { retryQuery } from "@/runtime/tanstack-query/query-defaults";

export type FeatureFlagName = keyof Required<FeatureFlags>;

type FeatureFlagsResponse = Record<FeatureFlagName, boolean>;

/**
 * Every flag is optional on the wire — an older server omits one it has never heard of — so each is
 * parsed to a definite boolean with absent reading as off. Spelling them out rather than deriving
 * them is what makes that safe: omit one and `fetchFeatureFlags` stops satisfying its return type.
 */
const featureFlagsSchema = z.object({
	ADMIN: z.boolean().catch(false),
	GITLAB_WORKSPACE_CREATION: z.boolean().catch(false),
	NOTIFICATION_ACCESS: z.boolean().catch(false),
	PAGE_EXPERIENCE_METRICS: z.boolean().catch(false),
});

async function fetchFeatureFlags(): Promise<FeatureFlagsResponse> {
	const { data } = await getUserFeatures({ throwOnError: true });
	const parsed = featureFlagsSchema.safeParse(data);
	if (!parsed.success) {
		throw new Error("Failed to fetch feature flags");
	}
	return parsed.data;
}

// The administrator's flags never apply in a view.
const USER_VIEW_FLAGS: FeatureFlagsResponse = {
	ADMIN: false,
	GITLAB_WORKSPACE_CREATION: false,
	NOTIFICATION_ACCESS: false,
	PAGE_EXPERIENCE_METRICS: false,
};

/** The signed-in reader's flags. Outside React, read them through the query client with these. */
export const featureFlagsQueryOptions = queryOptions({
	queryKey: getUserFeaturesQueryKey(),
	queryFn: fetchFeatureFlags,
	staleTime: 60_000,
	retry: retryQuery,
});

function useFeatureFlagsQuery(): {
	data: FeatureFlagsResponse | undefined;
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

export function useFeatureFlag(flag: FeatureFlagName) {
	const { data, isLoading, isError } = useFeatureFlagsQuery();

	return {
		enabled: data?.[flag] ?? false,
		isLoading,
		isError,
	};
}

export function useFeatureFlags() {
	const { data, isLoading, isError } = useFeatureFlagsQuery();

	return {
		flags: data,
		isLoading,
		isError,
	};
}

export function useAllFeatureFlags(...flags: FeatureFlagName[]) {
	const { data, isLoading } = useFeatureFlagsQuery();

	return {
		enabled: data !== undefined && flags.every((f) => data[f]),
		isLoading,
	};
}

export function useAnyFeatureFlags(...flags: FeatureFlagName[]) {
	const { data, isLoading } = useFeatureFlagsQuery();

	return {
		enabled: data !== undefined && flags.some((f) => data[f]),
		isLoading,
	};
}
