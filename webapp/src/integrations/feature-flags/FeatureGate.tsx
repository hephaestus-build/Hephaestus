import type { ReactNode } from "react";

import { type FeatureFlagName, useFeatureFlag } from "./hooks";

interface FeatureGateProps {
	/** The feature flag to check */
	flag: FeatureFlagName;
	/** Content to render when the flag is enabled */
	children: ReactNode;
	/** Optional content to render when the flag is disabled (default: null) */
	fallback?: ReactNode;
	/** Optional content to render while loading (default: null) */
	loading?: ReactNode;
}

interface FeatureGateDisplayProps {
	/** Whether the flag is enabled */
	enabled: boolean;
	/** Whether the flag state is loading */
	isLoading: boolean;
	/** Content to render when the flag is enabled */
	children: ReactNode;
	/** Optional content to render when the flag is disabled (default: null) */
	fallback?: ReactNode;
	/** Optional content to render while loading (default: null) */
	loading?: ReactNode;
}

/**
 * Pure presentational gate that conditionally renders based on flag state.
 * Exported for Storybook — use {@link FeatureGate} in application code.
 */
export function FeatureGateDisplay({
	enabled,
	isLoading,
	children,
	fallback = null,
	loading: loadingContent = null,
}: FeatureGateDisplayProps) {
	if (isLoading) {
		return loadingContent;
	}
	if (!enabled) {
		return fallback;
	}
	return children;
}

/**
 * Conditionally renders children based on a feature flag.
 *
 * @example
 * ```tsx
 * <FeatureGate flag="GITLAB_WORKSPACE_CREATION">
 *   <CreateGitLabWorkspaceButton />
 * </FeatureGate>
 *
 * <FeatureGate
 *   flag="MENTOR_ACCESS"
 *   fallback={<UpgradePrompt />}
 *   loading={<Spinner />}
 * >
 *   <MentorChat />
 * </FeatureGate>
 * ```
 */
export function FeatureGate({ flag, children, fallback = null, loading = null }: FeatureGateProps) {
	const { enabled, isLoading } = useFeatureFlag(flag);

	if (isLoading) {
		return loading;
	}
	if (!enabled) {
		return fallback;
	}
	return children;
}
