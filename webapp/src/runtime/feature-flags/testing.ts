import type { FeatureFlagName } from "./hooks";

type FeatureFlags = Record<FeatureFlagName, boolean>;

/**
 * Creates a feature flags response with all flags defaulting to `false`,
 * then applies the given overrides.
 *
 * @example
 * ```ts
 * const flags = mockFeatureFlags({ ADMIN: true });
 * ```
 */
export function mockFeatureFlags(overrides?: Partial<FeatureFlags>): FeatureFlags {
	return {
		ADMIN: false,
		PAGE_EXPERIENCE_METRICS: false,
		...overrides,
	};
}
