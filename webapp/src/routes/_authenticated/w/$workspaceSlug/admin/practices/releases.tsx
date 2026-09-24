import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { Inbox } from "lucide-react";
import { type ReactNode, useState } from "react";
import { toast } from "sonner";

import {
	acceptPracticeReleaseMutation,
	declinePracticeReleaseMutation,
	listPracticeReleasesOptions,
	listPracticeReleasesQueryKey,
	listPracticesQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { PracticeReleaseReview } from "@/components/admin/practices/PracticeReleaseReview";
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
import { Spinner } from "@/components/ui/spinner";
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
	const refresh = () => {
		void queryClient.invalidateQueries({
			queryKey: listPracticeReleasesQueryKey({ path: { workspaceSlug } }),
		});
		void queryClient.invalidateQueries({
			queryKey: listPracticesQueryKey({ path: { workspaceSlug } }),
		});
	};
	const accept = useMutation({
		...acceptPracticeReleaseMutation(),
		onSuccess: () => {
			refresh();
			toast.success("Practice update accepted");
		},
		onError: (error) => {
			if (problemStatusOf(error) === 412) {
				refresh();
			}
			toast.error("Couldn't accept the update", { description: problemDetailOf(error) });
		},
	});
	const decline = useMutation({
		...declinePracticeReleaseMutation(),
		onSuccess: () => {
			refresh();
			toast.success("This update was declined");
		},
		onError: (error) => {
			if (problemStatusOf(error) === 412) {
				refresh();
			}
			toast.error("Couldn't decline the update", { description: problemDetailOf(error) });
		},
	});
	const proposals = query.data ?? [];
	const selected = proposals.find((proposal) => proposal.slug === selectedSlug) ?? proposals[0];
	const pending = accept.isPending || decline.isPending;
	let content: ReactNode;
	if (query.isPending) {
		content = (
			<div className="flex items-center gap-2 text-sm text-muted-foreground">
				<Spinner /> Loading updates…
			</div>
		);
	} else if (query.isError) {
		content = (
			<QueryErrorAlert
				error={query.error}
				title="Couldn't load practice updates"
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
						Accepted and declined versions do not appear here again unless the catalogue changes.
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
							key={proposal.slug}
							type="button"
							aria-current={proposal.slug === selected.slug ? "true" : undefined}
							className="w-full rounded-md border p-3 text-left text-sm hover:bg-muted aria-current:bg-muted"
							onClick={() => setSelectedSlug(proposal.slug)}
						>
							<span className="block font-medium">{proposal.offered.name}</span>
							<span className="text-muted-foreground">{proposal.fields.length} changed fields</span>
						</button>
					))}
				</nav>
				<PracticeReleaseReview
					key={selected.etag}
					proposal={selected}
					pending={pending}
					onAccept={(choices) =>
						accept.mutate({
							path: { workspaceSlug, slug: selected.slug },
							headers: { "If-Match": `"${selected.etag}"` },
							body: { choices },
						})
					}
					onDecline={() =>
						decline.mutate({
							path: { workspaceSlug, slug: selected.slug },
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
				description="Review catalogue changes before they affect this workspace. Each workspace decides for itself."
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
