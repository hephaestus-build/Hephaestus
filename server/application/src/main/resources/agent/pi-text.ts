/**
 * Whether an optional string carries anything — `""` counts as absent, which is how env and model
 * fields arrive. The same reading as `webapp/src/lib/text.ts`; this copy ships into the sandbox
 * with the runner, which no path alias reaches from there.
 */
export function hasText(value: string | null | undefined): value is string {
	return value != null && value !== "";
}

/**
 * Whether an optional string is empty once trimmed — the reading for a field a model wrote, where
 * whitespace alone says as little as `""`. The runner's own reading: `webapp/src/lib/text.ts` carries
 * `hasText` alone.
 */
export function isBlank(value: string | null | undefined): boolean {
	return !hasText(value?.trim());
}
