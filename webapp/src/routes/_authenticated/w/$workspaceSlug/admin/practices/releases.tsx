import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { toast } from "@/lib/toast";

import {
	acceptPracticeReleaseMutation,
	declinePracticeReleaseMutation,
	getPracticeQueryKey,
	listPracticeReleasesOptions,
	listPracticeReleasesQueryKey,
	listPracticesQueryKey,
	listPracticeScriptNeedsQueryKey,
	listPracticeVersionsQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { CatalogUpdatesPage } from "@/components/admin/practices/CatalogUpdatesPage";
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
			queryClient.invalidateQueries({
				queryKey: listPracticeScriptNeedsQueryKey({ path: { workspaceSlug } }),
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
			// A stale offer is refused whole; the inbox reloads with the comparison as it now stands.
			if (problemStatusOf(error) === 412) {
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
	return (
		<CatalogUpdatesPage
			workspaceSlug={workspaceSlug}
			state={panelState(query, (proposals) => ({ status: "ready" as const, proposals }))}
			pending={pending}
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
