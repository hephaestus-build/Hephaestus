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

// The administrator's flags never apply in a view, and the server does not gate a view's mentor reads.
const USER_VIEW_FLAGS: FeatureFlagsResponse = {
	ADMIN: false,
	GITLAB_WORKSPACE_CREATION: false,
	MENTOR_ACCESS: true,
	NOTIFICATION_ACCESS: false,
};

function useFeatureFlagsQuery(): {
	data: FeatureFlagsResponse | undefined;
	isLoading: boolean;
	isError: boolean;
} {
	const { isAuthenticated, userView } = useAuth();

	const query = useQuery<FeatureFlagsResponse>({
		queryKey: FEATURE_FLAGS_QUERY_KEY,
		queryFn: fetchFeatureFlags,
		enabled: isAuthenticated && !userView,
		staleTime: 60_000,
		retry: 3,
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
