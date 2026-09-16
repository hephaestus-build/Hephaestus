/**
 * Whether an optional string carries anything — `""` counts as absent, which is how env and model
 * fields arrive. The same reading as `webapp/src/lib/text.ts`; this copy ships into the sandbox
 * with the runner, which no path alias reaches from there.
 */
export function hasText(value: string | null | undefined): value is string {
	return value != null && value !== "";
}
