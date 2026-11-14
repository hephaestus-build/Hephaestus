import {
	type QueryKey,
	type UseQueryResult,
	useMutation,
	useQuery,
	useQueryClient,
} from "@tanstack/react-query";
import { useState } from "react";

import {
	listConnectionProviderRepairsOptions,
	listInstanceProviderRepairsOptions,
	releaseConnectionProviderRepairMutation,
	releaseOrphanProviderRepairMutation,
} from "@/api/@tanstack/react-query.gen";
import type { OffsetPageDtoProviderResourceRepair, ProviderResourceRepair } from "@/api/types.gen";
import type {
	ProviderRepairItem,
	ProviderResourceRepairsProps,
} from "@/components/admin/integrations/ProviderResourceRepairs";
import { currentUserQueryOptions, isAppAdmin } from "@/runtime/auth/guard";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

interface ConnectionScope {
	workspaceSlug: string;
	connectionId: number;
}

export function useInstanceProviderResourceRepairs() {
	const [page, setPage] = useState(0);
	const options = listInstanceProviderRepairsOptions({ query: { page, size: 25 } });
	const query = useQuery(options);
	return {
		...useRepairView({ kind: "instance" }, query, options.queryKey),
		page,
		onPageChange: setPage,
	};
}

export function useConnectionProviderResourceRepairs(scope: ConnectionScope) {
	const [page, setPage] = useState(0);
	const options = listConnectionProviderRepairsOptions({ path: scope, query: { page, size: 25 } });
	const query = useQuery(options);
	return {
		...useRepairView({ kind: "workspace", ...scope }, query, options.queryKey),
		page,
		onPageChange: setPage,
	};
}

function useRepairView(
	scope: { kind: "instance" } | ({ kind: "workspace" } & ConnectionScope),
	query: UseQueryResult<OffsetPageDtoProviderResourceRepair>,
	queryKey: QueryKey,
) {
	const client = useQueryClient();
	const currentUser = useQuery(currentUserQueryOptions());
	useQueryReadRefusal(queryKey, query.isError, query.error);
	const onSettled = async () => {
		await client.invalidateQueries({ queryKey });
	};
	const instanceRelease = useMutation({ ...releaseOrphanProviderRepairMutation(), onSettled });
	const connectionRelease = useMutation({
		...releaseConnectionProviderRepairMutation(),
		onSettled,
	});
	const allowed = isAppAdmin(currentUser.isError ? undefined : currentUser.data);
	// Releasing needs an instance admin. The instance console releases a removal with an owning
	// connection from that connection, where its owner can see what the connection still uses.
	const releaseOf = (repair: ProviderResourceRepair): ProviderRepairItem["release"] => {
		if (repair.state === "REGISTERING") {
			return "registering";
		}
		if (!allowed) {
			return "instance-admin";
		}
		return scope.kind === "instance" && repair.owners.length > 0 ? "at-connection" : "available";
	};
	const onRelease = async (repair: ProviderResourceRepair) => {
		const body = { confirmedGone: true, identityHash: repair.identityHash };
		if (scope.kind === "instance") {
			await instanceRelease.mutateAsync({ path: { repairId: repair.id }, body });
		} else {
			await connectionRelease.mutateAsync({
				path: {
					workspaceSlug: scope.workspaceSlug,
					connectionId: scope.connectionId,
					repairId: repair.id,
				},
				body,
			});
		}
	};
	let state: ProviderResourceRepairsProps["state"];
	if (query.isError) {
		state = {
			status: "error",
			error: query.error,
			onRetry: () => {
				void query.refetch();
			},
		};
	} else if (query.isPending) {
		state = { status: "loading" };
	} else {
		state = {
			status: "ready",
			items: query.data.content.map((repair) => ({ repair, release: releaseOf(repair) })),
		};
	}
	return {
		state,
		onRelease,
		isReleasing: instanceRelease.isPending || connectionRelease.isPending,
		totalPages: query.data?.totalPages ?? 0,
	};
}
