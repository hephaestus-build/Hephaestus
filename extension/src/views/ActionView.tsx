import { useMutation, useQuery } from "@tanstack/react-query";
import { useSyncExternalStore } from "react";

import {
	ActionConfirmation,
	type ActionConfirmationState,
} from "~/components/action/ActionConfirmation";
import { actionIntentSchema } from "~/shared/review-actions";
import { ask, RpcClientError } from "~/ui/rpc-client";
import { useGeneration } from "~/ui/worker-state";

/**
 * The intent this window was sent to, from its address. The worker opens the window blank, binds the
 * intent to it, and only then adds the intent to the address, so it arrives as a fragment change.
 */
function useIntent(): string | undefined {
	const hash = useSyncExternalStore(
		(changed) => {
			window.addEventListener("hashchange", changed);
			return () => window.removeEventListener("hashchange", changed);
		},
		() => location.hash.slice(1),
	);
	const parsed = actionIntentSchema.safeParse(hash);
	return parsed.success ? parsed.data : undefined;
}

/** A refusal the worker or the server stated; anything else leaves the outcome unknown. */
function definite(error: unknown): string | undefined {
	return error instanceof RpcClientError &&
		(error.code === "forbidden" || error.code === "conflict" || error.code === "expired")
		? error.message
		: undefined;
}

function ActionContent({ intent, generation }: { intent: string | undefined; generation: number }) {
	const preview = useQuery({
		queryKey: ["action", generation, intent],
		queryFn: async () => ask({ type: "get-action", intent: intent ?? "" }),
		enabled: intent !== undefined,
		retry: false,
		refetchOnWindowFocus: false,
		staleTime: Number.POSITIVE_INFINITY,
	});
	const confirm = useMutation({
		mutationFn: async () => ask({ type: "confirm-action", intent: intent ?? "" }),
	});
	const close = () => {
		const done = async () => {
			if (intent !== undefined && confirm.isIdle) {
				await ask({ type: "discard-action", intent }).catch(() => undefined);
			}
			window.close();
		};
		void done();
	};

	let state: ActionConfirmationState = { status: "loading" };
	if (preview.isError) {
		state = { status: "invalid", message: preview.error.message };
	} else if (preview.data !== undefined) {
		const current = preview.data;
		if (confirm.isPending) {
			state = { status: "sending", preview: current };
		} else if (confirm.isSuccess) {
			state = { status: "done", preview: current, outcome: confirm.data };
		} else if (confirm.isError) {
			const message = definite(confirm.error);
			state =
				message === undefined
					? { status: "unknown", preview: current }
					: { status: "refused", preview: current, message };
		} else {
			state = { status: "ready", preview: current };
		}
	}
	return <ActionConfirmation state={state} onConfirm={() => confirm.mutate()} onClose={close} />;
}

/** Session changes discard the preview and local mutation state together, including late replies. */
export function ActionView() {
	const intent = useIntent();
	const generation = useGeneration();
	return (
		<ActionContent key={`${generation}:${intent ?? ""}`} intent={intent} generation={generation} />
	);
}
