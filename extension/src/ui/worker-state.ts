import { QueryClient, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";

import { knownGeneration, onWorkerEvent, RpcClientError } from "~/ui/rpc-client";

export function createQueryClient(): QueryClient {
	return new QueryClient({
		defaultOptions: {
			queries: {
				// A stale answer is refetched by the event that made it stale; any other failure is shown.
				retry: (count, error) =>
					error instanceof RpcClientError && error.code === "stale" && count < 2,
				refetchOnWindowFocus: true,
				staleTime: 5000,
			},
			mutations: { retry: false },
		},
	});
}

/**
 * The generation this view renders under. When the worker announces a new one — sign-out, a new
 * account or instance, a workspace switch, a revoked site — every cached answer is reset in the same
 * tick and the caller re-keys its content on the returned value, so local state (a confirmation, a
 * request result) is dropped with it. Nothing from before the change stays on screen.
 */
export function useGeneration(): number {
	const queryClient = useQueryClient();
	const [generation, setGeneration] = useState(knownGeneration);
	useEffect(
		() =>
			onWorkerEvent((event) => {
				if (event.type === "state-changed") {
					setGeneration(event.generation);
					void queryClient.resetQueries();
				}
				if (event.type === "site-access-changed") {
					void queryClient.invalidateQueries();
				}
			}),
		[queryClient],
	);
	return generation;
}
