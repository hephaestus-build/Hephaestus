import { Link } from "@tanstack/react-router";
import { Inbox } from "lucide-react";
import { useState } from "react";

import type { AcceptPracticeReleaseRequest, PracticeReleaseProposal } from "@/api/types.gen";
import { PracticeReleaseReview } from "@/components/admin/practices/PracticeReleaseReview";
import {
	PracticeListSkeleton,
	PracticeReleaseSkeleton,
} from "@/components/admin/practices/PracticeSkeletons";
import type { PanelState } from "@/components/common/panel-state";
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

export interface CatalogUpdatesPageProps {
	workspaceSlug: string;
	state: PanelState<{ proposals: PracticeReleaseProposal[] }>;
	/** The decision being saved, which holds the other one back. */
	pending: "accept" | "decline" | null;
	onAccept: (
		proposal: PracticeReleaseProposal,
		choices: AcceptPracticeReleaseRequest["choices"],
	) => void;
	onDecline: (proposal: PracticeReleaseProposal) => void;
}

export function CatalogUpdatesPage({
	workspaceSlug,
	state,
	pending,
	onAccept,
	onDecline,
}: CatalogUpdatesPageProps) {
	return (
		<PageLayout>
			<PageHeader
				icon={<Inbox />}
				title="Catalog updates"
				description="Review catalog changes before they affect this workspace. Each workspace decides for itself."
				actions={
					<Button
						variant="outline"
						nativeButton={false}
						render={<Link to="/w/$workspaceSlug/admin/practices" params={{ workspaceSlug }} />}
					>
						Back to practice setup
					</Button>
				}
			/>
			{state.status === "loading" && (
				<div className="grid gap-6 lg:grid-cols-[15rem_minmax(0,1fr)]">
					<PracticeListSkeleton rows={2} />
					<PracticeReleaseSkeleton rows={3} />
				</div>
			)}
			{state.status === "error" && (
				<QueryErrorAlert
					error={state.error}
					title="We could not load catalog updates"
					onRetry={state.onRetry}
				/>
			)}
			{state.status === "ready" && (
				<CatalogUpdateList
					proposals={state.proposals}
					pending={pending}
					onAccept={onAccept}
					onDecline={onDecline}
				/>
			)}
		</PageLayout>
	);
}

function CatalogUpdateList({
	proposals,
	pending,
	onAccept,
	onDecline,
}: { proposals: PracticeReleaseProposal[] } & Pick<
	CatalogUpdatesPageProps,
	"pending" | "onAccept" | "onDecline"
>) {
	const [selectedSlug, setSelectedSlug] = useState<string | null>(null);
	const selected =
		proposals.find((proposal) => proposal.practiceSlug === selectedSlug) ?? proposals[0];
	if (selected === undefined) {
		return (
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
	}
	return (
		<div className="grid gap-6 lg:grid-cols-[15rem_minmax(0,1fr)]">
			<nav aria-label="Pending catalog updates" className="space-y-2">
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
				onAccept={(choices) => onAccept(selected, choices)}
				onDecline={() => onDecline(selected)}
			/>
		</div>
	);
}
