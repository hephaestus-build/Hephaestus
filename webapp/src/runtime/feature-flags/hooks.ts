import { useQuery } from "@tanstack/react-query";
import { z } from "zod";

import { getUserFeatures } from "@/api/sdk.gen";
import type { FeatureFlags } from "@/api/types.gen";
import { useAuth } from "@/runtime/auth/AuthContext";

export type FeatureFlagName = keyof Required<FeatureFlags>;

type FeatureFlagsResponse = Record<FeatureFlagName, boolean>;

const FEATURE_FLAGS_QUERY_KEY = ["user", "features"] as const;

/**
 * Every flag is optional on the wire — an older server omits one it has never heard of — so each is
 * parsed to a definite boolean with absent reading as off. Spelling them out rather than deriving
 * them is what makes that safe: omit one and `fetchFeatureFlags` stops satisfying its return type.
 */
const featureFlagsSchema = z.object({
	ADMIN: z.boolean().catch(false),
	GITLAB_WORKSPACE_CREATION: z.boolean().catch(false),
	MENTOR_ACCESS: z.boolean().catch(false),
	NOTIFICATION_ACCESS: z.boolean().catch(false),
});

async function fetchFeatureFlags(): Promise<FeatureFlagsResponse> {
	const { data } = await getUserFeatures();
	const parsed = featureFlagsSchema.safeParse(data);
	if (!parsed.success) {
		throw new Error("Failed to fetch feature flags");
	}
	return parsed.data;
}

function useFeatureFlagsQuery() {
	const { isAuthenticated } = useAuth();

	return useQuery<FeatureFlagsResponse>({
		queryKey: FEATURE_FLAGS_QUERY_KEY,
		queryFn: fetchFeatureFlags,
		enabled: isAuthenticated,
		staleTime: 60_000,
		retry: 3,
	});
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
