import { Link } from "@tanstack/react-router";
import { type ReactElement, useId } from "react";

import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import { WORKSPACE_SETUP_STATUS_DEFS } from "@/components/practice-vocabulary/workspace-setup-status-defs";
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";

import {
	type WorkspaceSetupFacts,
	type WorkspaceSetupRow,
	type WorkspaceSetupRowId,
	workspaceSetupRows,
} from "./workspace-setup";

/** The heading's id, so the list and the section are named by it. */
const SETUP_SUMMARY_HEADING_ID = "setup-summary-heading";

export type WorkspaceSetupSummaryState = PanelState<{ facts: WorkspaceSetupFacts }>;

export interface WorkspaceSetupSummaryProps {
	workspaceSlug: string;
	state: WorkspaceSetupSummaryState;
}

/** The page that changes each fact. */
function rowLink(id: WorkspaceSetupRowId, workspaceSlug: string): ReactElement {
	const params = { workspaceSlug };
	switch (id) {
		case "repositories": {
			return <Link to="/w/$workspaceSlug/admin/integrations/scm" params={params} />;
		}
		case "practiceReviews": {
			return (
				<Link
					to="/w/$workspaceSlug/admin/practices/review"
					params={params}
					search={{ section: "automated-review" }}
				/>
			);
		}
		case "practices": {
			return <Link to="/w/$workspaceSlug/admin/practices" params={params} />;
		}
		case "reviewModel": {
			return (
				<Link
					to="/w/$workspaceSlug/admin/models"
					params={params}
					search={{ purpose: "PRACTICE_REVIEW" }}
				/>
			);
		}
		case "feedbackMode": {
			return <Link to="/w/$workspaceSlug/admin/practices/review" params={params} />;
		}
		case "firstReview": {
			return <Link to="/w/$workspaceSlug/admin/practices/reviews/overview" params={params} />;
		}
	}
}

/**
 * Whether members get value from this workspace, as a task list: one row for each fact, with its
 * status and a link to the page that changes it. Every row is derived from the server's facts, so
 * nothing here is stored or dismissed.
 */
export function WorkspaceSetupSummary({ workspaceSlug, state }: WorkspaceSetupSummaryProps) {
	return (
		<section aria-labelledby={SETUP_SUMMARY_HEADING_ID}>
			<h2 id={SETUP_SUMMARY_HEADING_ID} className="text-lg font-semibold">
				Setup summary
			</h2>
			<p className="mb-4 text-sm text-muted-foreground">
				What members get from this workspace now. Each row links to the page that changes it.
			</p>
			{state.status === "error" ? (
				<QueryErrorAlert
					error={state.error}
					title="We could not load the setup summary"
					onRetry={state.onRetry}
				/>
			) : (
				<ul
					aria-labelledby={SETUP_SUMMARY_HEADING_ID}
					aria-busy={state.status === "loading"}
					className="rounded-xl border bg-card"
				>
					{state.status === "loading"
						? Array.from({ length: SETUP_ROW_COUNT }, (_, index) => (
								<SetupRowSkeleton key={index} />
							))
						: workspaceSetupRows(state.facts).map((setupRow) => (
								<SetupRow key={setupRow.id} row={setupRow} workspaceSlug={workspaceSlug} />
							))}
				</ul>
			)}
		</section>
	);
}

const SETUP_ROW_COUNT = 6;

function SetupRow({ row, workspaceSlug }: { row: WorkspaceSetupRow; workspaceSlug: string }) {
	const summaryId = useId();
	const statusId = useId();
	return (
		<Item render={<li />} variant="row">
			<ItemContent>
				<ItemTitle>
					<InlineLink
						render={rowLink(row.id, workspaceSlug)}
						aria-describedby={`${summaryId} ${statusId}`}
					>
						{row.title}
					</InlineLink>
				</ItemTitle>
				<ItemDescription id={summaryId} className="line-clamp-none">
					{row.summary}
				</ItemDescription>
				{row.id === "firstReview" && row.status === "to-do" && (
					<RecentWorkNote workspaceSlug={workspaceSlug} />
				)}
			</ItemContent>
			<ItemActions>
				<StatusBadge id={statusId} def={WORKSPACE_SETUP_STATUS_DEFS[row.status]} />
			</ItemActions>
		</Item>
	);
}

/** The one next step before the first review, and what it does and does not do. */
function RecentWorkNote({ workspaceSlug }: { workspaceSlug: string }) {
	return (
		<ItemDescription className="line-clamp-none">
			<InlineLink
				render={
					<Link
						to="/w/$workspaceSlug/admin/practices/review"
						params={{ workspaceSlug }}
						search={{ section: "past-work" }}
					/>
				}
				className="font-medium"
			>
				Review recent work
			</InlineLink>{" "}
			is started by an admin. Hephaestus never starts it by itself. Its results appear on members’
			Practice profiles. Feedback on merged work is withheld.
		</ItemDescription>
	);
}

function SetupRowSkeleton() {
	return (
		<Item render={<li />} variant="row">
			<ItemContent>
				<Skeleton className="h-4 w-32" />
				<Skeleton className="h-4 w-64 max-w-full" />
			</ItemContent>
			<ItemActions>
				<Skeleton className="h-5 w-20 rounded-full" />
			</ItemActions>
		</Item>
	);
}
