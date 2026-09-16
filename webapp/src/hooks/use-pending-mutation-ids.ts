import { type MutationKey, useMutationState } from "@tanstack/react-query";

import { isRecord } from "@/lib/is-record";

export function filedUnder<TOptions extends object>(
	mutationKey: MutationKey,
	options: TOptions,
): TOptions & { mutationKey: MutationKey } {
	return { ...options, mutationKey };
}

/**
 * The mutation cache holds every mutation the app has fired, so it types `variables` as `unknown`:
 * filtering by key narrows which mutations come back, not what they carry.
 */
function pathParam(variables: unknown, field: string): unknown {
	if (!isRecord(variables) || !isRecord(variables.path)) {
		return undefined;
	}
	return variables.path[field];
}

export function pathNumber(variables: unknown, field: string): number | undefined {
	const value = pathParam(variables, field);
	return typeof value === "number" ? value : undefined;
}

export function pathString(variables: unknown, field: string): string | undefined {
	const value = pathParam(variables, field);
	return typeof value === "string" ? value : undefined;
}

/** For disabling the rows in-flight mutations are about; `pathNumber`/`pathString` supply `idOf`. */
export function usePendingMutationIds<TId extends string | number>(
	mutationKey: MutationKey,
	idOf: (variables: unknown) => TId | undefined,
): ReadonlySet<TId> {
	const ids = useMutationState({
		filters: { mutationKey, status: "pending" },
		select: (mutation) => idOf(mutation.state.variables),
	});
	return new Set(ids.filter((id): id is TId => id != null));
}
