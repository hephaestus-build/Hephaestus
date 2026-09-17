/**
 * Whether an optional string carries anything; `""` counts as absent. The API client emits `null`,
 * so this reading takes it. The Node trees carry their own copies — `scripts/lib/env.ts`'s `isSet`
 * for environment variables and arguments, which are never `null`, and the runner's `pi-text.ts`,
 * which ships into the sandbox where no alias reaches this file.
 */
export function hasText(value: string | null | undefined): value is string {
	return value != null && value !== "";
}

/** The first of `values` that carries text — `""` counts as absent, which is how names arrive. */
export function firstNonBlank(...values: (string | null | undefined)[]): string | undefined {
	return values.find((value) => hasText(value));
}
