import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { Inbox } from "lucide-react";
import { type ReactNode, useState } from "react";
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
import { PracticeReleaseReview } from "@/components/admin/practices/PracticeReleaseReview";
import {
	PracticeListSkeleton,
	PracticeReleaseSkeleton,
} from "@/components/admin/practices/PracticeSkeletons";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { refreshPracticeGuidance } from "@/hooks/practice-guidance-cache";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/practices/releases")({
	head: workspaceAdminHead("Practice updates"),
	component: PracticeReleaseInbox,
});

function PracticeReleaseInbox() {
	const { workspaceSlug } = Route.useParams();
	const [selectedSlug, setSelectedSlug] = useState<string | null>(null);
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
	const proposals = query.data ?? [];
	const selected =
		proposals.find((proposal) => proposal.practiceSlug === selectedSlug) ?? proposals[0];
	const pending = (accept.isPending ? "accept" : null) ?? (decline.isPending ? "decline" : null);
	let content: ReactNode;
	if (query.isPending) {
		content = (
			<div className="grid gap-6 lg:grid-cols-[15rem_minmax(0,1fr)]">
				<PracticeListSkeleton rows={2} />
				<PracticeReleaseSkeleton rows={3} />
			</div>
		);
	} else if (query.isError) {
		content = (
			<QueryErrorAlert
				error={query.error}
				title="We could not load practice updates"
				onRetry={() => {
					void query.refetch();
				}}
			/>
		);
	} else if (selected === undefined) {
		content = (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<Inbox />
					</EmptyMedia>
					<EmptyTitle>No updates to review</EmptyTitle>
					<EmptyDescription>
						Catalog changes to your practices appear here. Accepted and declined versions do not
						return unless the catalog changes again. Reset to catalog stays available on each
						practice.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	} else {
		content = (
			<div className="grid gap-6 lg:grid-cols-[15rem_minmax(0,1fr)]">
				<nav aria-label="Pending practice updates" className="space-y-2">
					{proposals.map((proposal) => (
						<button
							key={proposal.practiceSlug}
							type="button"
							aria-current={proposal.practiceSlug === selected.practiceSlug ? "true" : undefined}
							className="w-full rounded-md border p-3 text-left text-sm hover:bg-muted aria-current:bg-muted"
							onClick={() => setSelectedSlug(proposal.practiceSlug)}
						>
							<span className="block font-medium">{proposal.offered.name}</span>
							<span className="text-muted-foreground">
								{proposal.fields.length} changed {proposal.fields.length === 1 ? "field" : "fields"}
							</span>
						</button>
					))}
				</nav>
				<PracticeReleaseReview
					key={selected.etag}
					proposal={selected}
					pending={pending}
					declineNote="Declining keeps your practice as it is until the catalog changes again. Reset to catalog stays available on the practice."
					onAccept={(choices) =>
						accept.mutate({
							path: { workspaceSlug, slug: selected.practiceSlug },
							headers: { "If-Match": `"${selected.etag}"` },
							body: { choices },
						})
					}
					onDecline={() =>
						decline.mutate({
							path: { workspaceSlug, slug: selected.practiceSlug },
							headers: { "If-Match": `"${selected.etag}"` },
						})
					}
				/>
			</div>
		);
	}

	return (
		<PageLayout>
			<PageHeader
				icon={<Inbox />}
				title="Practice updates"
				description="Review catalog changes before they affect this workspace. Each workspace decides for itself."
				actions={
					<Button
						variant="outline"
						nativeButton={false}
						render={<Link to="/w/$workspaceSlug/admin/practices" params={{ workspaceSlug }} />}
					>
						Back to practices
					</Button>
				}
			/>
			{content}
		</PageLayout>
	);
}
