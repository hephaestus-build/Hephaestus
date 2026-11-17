import type { FeatureFlagName } from "./hooks";

type FeatureFlags = Record<FeatureFlagName, boolean>;

/**
 * Creates a feature flags response with all flags defaulting to `false`,
 * then applies the given overrides.
 *
 * @example
 * ```ts
 * const flags = mockFeatureFlags({ PAGE_EXPERIENCE_METRICS: true });
 * ```
 */
export function mockFeatureFlags(overrides?: Partial<FeatureFlags>): FeatureFlags {
	return {
		PAGE_EXPERIENCE_METRICS: false,
		...overrides,
	};
}
