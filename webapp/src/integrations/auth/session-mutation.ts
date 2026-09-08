import type { UseMutationOptions } from "@tanstack/react-query";

import { withSessionLock } from "./session-lock";

export function withSessionMutationLock<TData, TError, TVariables>(
	options: UseMutationOptions<TData, TError, TVariables>,
): UseMutationOptions<TData, TError, TVariables> {
	const { mutationFn } = options;
	return {
		...options,
		mutationFn: mutationFn ? (...args) => withSessionLock(() => mutationFn(...args)) : undefined,
	};
}
