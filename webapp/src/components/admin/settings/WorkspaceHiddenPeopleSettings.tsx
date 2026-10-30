import { Link } from "@tanstack/react-router";

import type { UserInfo } from "@/api/types.gen";
import { MemberAvatar } from "@/components/activity/MemberAvatar";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Button } from "@/components/ui/button";
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

export type WorkspaceHiddenPeopleState = PanelState<{
	people: readonly Pick<UserInfo, "id" | "login" | "name" | "avatarUrl">[];
	/** Set when the latest read failed, so `people` are the last known ones. */
	refreshFailure?: RefreshFailure;
}>;

export interface WorkspaceHiddenPeopleSettingsProps {
	workspaceSlug: string;
	state: WorkspaceHiddenPeopleState;
	/** The person being shown again, if any. */
	restoring: number | undefined;
	onShowAgain: (userId: number) => void;
}

/**
 * The one place to show again a person without a membership whom an admin hid: they have no account
 * here to show themselves with, and no row under Members. Members and teams are shown again on the
 * page that hides them, so this names those pages instead of copying their controls.
 */
export function WorkspaceHiddenPeopleSettings({
	workspaceSlug,
	state,
	restoring,
	onShowAgain,
}: WorkspaceHiddenPeopleSettingsProps) {
	return (
		<section aria-labelledby="hidden-people-heading">
			<h2 id="hidden-people-heading" className="text-lg font-semibold">
				Hidden people
			</h2>
			<p className="mb-4 max-w-2xl text-sm text-muted-foreground">
				People without a membership whom an admin hid from activity. They stay out of workspace
				activity and the public activity page until you show them again. To show a member again, use{" "}
				<InlineLink
					render={<Link to="/w/$workspaceSlug/admin/members" params={{ workspaceSlug }} />}
				>
					Members
				</InlineLink>
				. For a team or repository, use{" "}
				<InlineLink render={<Link to="/w/$workspaceSlug/admin/teams" params={{ workspaceSlug }} />}>
					Team setup
				</InlineLink>
				.
			</p>
			<div className="space-y-3">
				{state.status === "loading" && (
					<ul className="rounded-xl border bg-card" aria-busy="true" aria-hidden="true">
						<Item render={<li />} variant="row">
							<Skeleton className="size-8 rounded-full" />
							<ItemContent>
								<Skeleton className="h-4 w-32" />
								<Skeleton className="h-3 w-20" />
							</ItemContent>
							<ItemActions>
								<Skeleton className="h-8 w-24" />
							</ItemActions>
						</Item>
					</ul>
				)}
				{state.status === "error" && (
					<QueryErrorAlert
						error={state.error}
						title="We could not load hidden people"
						onRetry={state.onRetry}
					/>
				)}
				{state.status === "ready" && state.refreshFailure !== undefined && (
					<QueryErrorAlert title="Hidden people may be out of date" {...state.refreshFailure} />
				)}
				{state.status === "ready" && state.people.length === 0 && (
					<p className="text-sm text-muted-foreground">No person without a membership is hidden.</p>
				)}
				{state.status === "ready" && state.people.length > 0 && (
					<ul className="rounded-xl border bg-card">
						{state.people.map((person) => (
							<Item key={person.id} render={<li />} variant="row">
								<MemberAvatar user={person} />
								<ItemContent>
									<ItemTitle>{person.name}</ItemTitle>
									<ItemDescription>{person.login}</ItemDescription>
								</ItemContent>
								<ItemActions>
									<Button
										variant="outline"
										size="sm"
										disabled={restoring !== undefined}
										focusableWhenDisabled
										onClick={() => onShowAgain(person.id)}
									>
										{restoring === person.id && <Spinner />}
										Show again
									</Button>
								</ItemActions>
							</Item>
						))}
					</ul>
				)}
			</div>
		</section>
	);
}
