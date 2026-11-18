import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { toast } from "@/lib/toast";

import {
	acceptPracticeReleaseMutation,
	declinePracticeReleaseMutation,
	getPracticeQueryKey,
	listPracticeReleasesOptions,
	listPracticeReleasesQueryKey,
	listPracticesOptions,
	listPracticesQueryKey,
	listPracticeVersionsQueryKey,
} from "@/api/@tanstack/react-query.gen";
import {
	WorkspaceCatalogUpdatesPage,
	type WorkspaceCatalogUpdatesPageProps,
} from "@/components/admin/practices/WorkspaceCatalogUpdatesPage";
import { panelState } from "@/components/common/panel-state";
import { refreshPracticeGuidance } from "@/hooks/practice-guidance-cache";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/practices/releases")({
	head: workspaceAdminHead("Catalog updates"),
	component: CatalogUpdatesRoute,
});

function CatalogUpdatesRoute() {
	const { workspaceSlug } = Route.useParams();
	const queryClient = useQueryClient();
	const query = useQuery(listPracticeReleasesOptions({ path: { workspaceSlug } }));
	// The server refuses to accept an update for a practice edited in the practice repository, so the
	// updates wait for the practices that say which ones those are. A failed read leaves Accept offered,
	// and the server's refusal then explains itself.
	const practicesQuery = useQuery(listPracticesOptions({ path: { workspaceSlug } }));
	const editedInRepositorySlugs = new Set(
		practicesQuery.data
			?.filter((practice) => practice.editedIn === "REPOSITORY")
			.map((practice) => practice.slug),
	);
	const refresh = async (practiceSlug: string) => {
		const path = { workspaceSlug, practiceSlug };
		await Promise.all([
			queryClient.invalidateQueries({ queryKey: getPracticeQueryKey({ path }) }),
			queryClient.invalidateQueries({ queryKey: listPracticeVersionsQueryKey({ path }) }),
			queryClient.invalidateQueries({
				queryKey: listPracticeReleasesQueryKey({ path: { workspaceSlug } }),
			}),
			queryClient.invalidateQueries({
				queryKey: listPracticesQueryKey({ path: { workspaceSlug } }),
			}),
		]);
	};
	const accept = useMutation({
		...acceptPracticeReleaseMutation(),
		onSuccess: async (updated) => {
			await Promise.all([
				refresh(updated.slug),
				refreshPracticeGuidance(queryClient, workspaceSlug, updated.slug),
			]);
			toast.success("Practice update accepted");
		},
		onError: (error, variables) => {
			// A stale offer is refused whole, and a practice moved to the practice repository meanwhile
			// is refused as a conflict. Either way the inbox reloads as it now stands.
			const status = problemStatusOf(error);
			if (status === 412 || status === 409) {
				void refresh(variables.path.slug);
			}
			toast.error("We could not accept the update", { description: problemDetailOf(error) });
		},
	});
	const decline = useMutation({
		...declinePracticeReleaseMutation(),
		onSuccess: async (_data, variables) => {
			await refresh(variables.path.slug);
			toast.success("Practice update declined");
		},
		onError: (error, variables) => {
			// A stale offer is refused whole; the inbox reloads with the comparison as it now stands.
			if (problemStatusOf(error) === 412) {
				void refresh(variables.path.slug);
			}
			toast.error("We could not decline the update", { description: problemDetailOf(error) });
		},
	});
	const pending = (accept.isPending ? "accept" : null) ?? (decline.isPending ? "decline" : null);
	let state: WorkspaceCatalogUpdatesPageProps["state"] = panelState(query, (proposals) => ({
		status: "ready" as const,
		proposals,
	}));
	if (state.status === "ready" && practicesQuery.isPending) {
		state = { status: "loading" };
	}
	return (
		<WorkspaceCatalogUpdatesPage
			workspaceSlug={workspaceSlug}
			state={state}
			pending={pending}
			editedInRepositorySlugs={editedInRepositorySlugs}
			onAccept={(proposal, choices) =>
				accept.mutate({
					path: { workspaceSlug, slug: proposal.practiceSlug },
					headers: { "If-Match": `"${proposal.etag}"` },
					body: { choices },
				})
			}
			onDecline={(proposal) =>
				decline.mutate({
					path: { workspaceSlug, slug: proposal.practiceSlug },
					headers: { "If-Match": `"${proposal.etag}"` },
				})
			}
		/>
	);
}
