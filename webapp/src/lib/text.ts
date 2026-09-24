/**
 * Whether an optional string carries anything; `""` counts as absent. The API client emits `null`,
 * so this reading takes it. The Node trees carry their own copies — `scripts/lib/env.ts`'s `isSet`,
 * because `import/no-relative-parent-imports` keeps `scripts/` inside its tree, and the runner's
 * `pi-text.ts`, which ships into the sandbox where no alias reaches this file.
 */
export function hasText(value: string | null | undefined): value is string {
	return value != null && value !== "";
}

/** The first of `values` that carries text — `""` counts as absent, which is how names arrive. */
export function firstNonBlank(...values: (string | null | undefined)[]): string | undefined {
	return values.find((value) => hasText(value));
}

/**
 * A sentence opening with a capital: the first letter upper-cased and nothing else touched, so a
 * sentence that opens with a digit, a symbol or a code identifier stays exactly as it was written.
 * Every surface that shows a written sentence — a feedback card's next step, an observation row's,
 * the profile's composed prose — opens it through this one rule.
 */
export function capitalise(value: string): string {
	const index = value.search(/\S/u);
	const char = value[index];
	if (index === -1 || char === undefined || !/\p{L}/u.test(char)) {
		return value;
	}
	return value.slice(0, index) + char.toUpperCase() + value.slice(index + 1);
}
