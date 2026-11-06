import { type QueryFunctionContext, useQuery } from "@tanstack/react-query";
import { useEffect } from "react";
import { z } from "zod";

import { createClient } from "@/api/client/client.gen";
import environment from "@/environment";
import { toast } from "@/lib/toast";

const discoverySchema = z.object({ version: z.string().trim().min(1).max(64) });

async function readVersion({ signal }: QueryFunctionContext): Promise<string | null> {
	const discovery = createClient({
		baseUrl: window.location.origin,
		cache: "no-store",
		credentials: "omit",
	});
	const { data } = await discovery.get({
		url: "/.well-known/hephaestus.json",
		signal: AbortSignal.any([signal, AbortSignal.timeout(5000)]),
		throwOnError: true,
	});
	const parsed = discoverySchema.safeParse(data);
	return parsed.success ? parsed.data.version.replace(/^v/u, "") : null;
}

/** Discovery belongs to the webapp origin. A reload stays the reader's choice so drafts remain intact. */
export function useUpdateNotice(): void {
	const { version } = environment;
	const { data: latest } = useQuery({
		// oxlint-disable-next-line hephaestus/no-manual-query-key -- Public nginx discovery has no generated API operation.
		queryKey: ["webapp-discovery-version"],
		queryFn: readVersion,
		enabled: version !== "DEV",
		refetchInterval: 60_000,
		refetchOnWindowFocus: "always",
		refetchOnReconnect: "always",
		retry: false,
	});
	useEffect(() => {
		if (latest == null || latest === "DEV" || latest === version) {
			return;
		}
		const id = toast("Hephaestus was updated", {
			id: "deployment-update",
			description: "You can keep working. Save any text you need before you reload.",
			duration: Number.POSITIVE_INFINITY,
			action: { label: "Reload page", onClick: () => window.location.reload() },
		});
		return () => {
			toast.dismiss(id);
		};
	}, [latest, version]);
}
