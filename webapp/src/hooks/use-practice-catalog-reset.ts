import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "@/lib/toast";

import {
	getPracticeReleaseOptions,
	previewGroupAdoptionOptions,
} from "@/api/@tanstack/react-query.gen";
import type { Practice, PracticeGroup } from "@/api/types.gen";
import { parseGroupAdoptionPreview } from "@/components/admin/practices/catalog-adoption";
import { currentDefinition } from "@/components/admin/practices/practice-definition-fields";
import type { ResetToCatalogState } from "@/components/admin/practices/ResetToCatalogDialog";
import { problemStatusOf } from "@/lib/problem-detail";

import {
	practiceGroupResponseOptions,
	type usePracticeCatalogMutations,
} from "./use-practice-catalog-mutations";

interface ResetTarget {
	kind: "practice" | "group";
	slug: string;
}

export function usePracticeCatalogReset({
	workspaceSlug,
	groups,
	practices,
	mutations,
}: {
	workspaceSlug: string;
	groups: PracticeGroup[] | undefined;
	practices: Practice[] | undefined;
	mutations: Pick<ReturnType<typeof usePracticeCatalogMutations>, "resetGroup" | "resetPractice">;
}) {
	const [resetTarget, setResetTarget] = useState<ResetTarget | null>(null);
	const [resetStale, setResetStale] = useState(false);
	const resetGroupQuery = useQuery({
		...practiceGroupResponseOptions({ workspaceSlug, groupSlug: resetTarget?.slug ?? "" }),
		enabled: resetTarget?.kind === "group",
	});
	const resetGroup = resetTarget?.kind === "group" ? resetGroupQuery.data?.group : undefined;
	const resetPracticeQuery = useQuery({
		...getPracticeReleaseOptions({ path: { workspaceSlug, slug: resetTarget?.slug ?? "" } }),
		enabled: resetTarget?.kind === "practice",
	});
	const resetGroupOfferQuery = useQuery({
		...previewGroupAdoptionOptions({
			path: { workspaceSlug, slug: resetGroup?.catalogOrigin.catalogSlug ?? "" },
		}),
		select: parseGroupAdoptionPreview,
		enabled: resetGroup?.catalogOrigin.catalogSlug != null,
	});
	let resetState: ResetToCatalogState = { status: "loading" };
	const resetQuery = resetTarget?.kind === "group" ? resetGroupOfferQuery : resetPracticeQuery;
	if (resetTarget?.kind === "group" && resetGroupQuery.isError) {
		resetState = {
			status: "error",
			error: resetGroupQuery.error,
			onRetry: () => {
				void resetGroupQuery.refetch();
			},
		};
	} else if (resetQuery.isError) {
		resetState = {
			status: "error",
			error: resetQuery.error,
			onRetry: () => {
				void resetQuery.refetch();
			},
		};
	} else if (resetTarget?.kind === "practice" && resetPracticeQuery.data) {
		const { current, offered } = resetPracticeQuery.data;
		resetState = { status: "ready", comparison: { kind: "practice", current, offered } };
	} else if (resetGroup && resetGroupOfferQuery.data) {
		const { name, description, icon, color } = resetGroup;
		resetState = {
			status: "ready",
			comparison: {
				kind: "group",
				current: { name, description, icon, color },
				offered: resetGroupOfferQuery.data.definition,
			},
		};
	}
	const resetPractice = practices?.find(({ slug }) => slug === resetTarget?.slug);
	let resetName = "";
	if (resetTarget?.kind === "group") {
		resetName =
			resetGroup?.name ?? groups?.find(({ slug }) => slug === resetTarget.slug)?.name ?? "";
	} else if (resetPractice !== undefined) {
		resetName = currentDefinition(resetPractice).name;
	}
	const closeReset = () => {
		setResetTarget(null);
		setResetStale(false);
	};
	const openReset = (target: ResetTarget) => {
		setResetStale(false);
		setResetTarget(target);
	};
	const reloadComparison = async () => {
		await Promise.all([
			resetQuery.refetch(),
			...(resetTarget?.kind === "group" ? [resetGroupQuery.refetch()] : []),
		]);
		setResetStale(true);
	};
	const resetRefused = (error: unknown) => {
		if (problemStatusOf(error) !== 412) {
			toast.error("We could not reset to the catalog. Nothing was changed. Try again.");
			return;
		}
		void reloadComparison();
	};
	const confirmReset = () => {
		if (resetTarget?.kind === "practice" && resetPracticeQuery.data) {
			mutations.resetPractice.mutate(
				{
					path: { workspaceSlug, practiceSlug: resetTarget.slug },
					headers: { "If-Match": `"${resetPracticeQuery.data.etag}"` },
				},
				{
					onSuccess: closeReset,
					onError: resetRefused,
				},
			);
		} else if (resetGroup && resetGroupQuery.data) {
			mutations.resetGroup.mutate(
				{
					path: { workspaceSlug, groupSlug: resetGroup.slug },
					headers: { "If-Match": resetGroupQuery.data.etag },
				},
				{
					onSuccess: closeReset,
					onError: resetRefused,
				},
			);
		}
	};

	return {
		target: resetTarget,
		name: resetName,
		state: resetState,
		stale: resetStale,
		open: openReset,
		close: closeReset,
		confirm: confirmReset,
	};
}
