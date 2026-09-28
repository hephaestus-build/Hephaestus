import { cn } from "cn";
import type { WorkItem } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { MetaRow } from "@/components/common/MetaRow";
import { RelativeTime } from "@/components/common/RelativeTime";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import { type ProviderType, workReference } from "@/lib/provider/provider-terms";
import { andList } from "@/lib/text";

import { ACTIVITY_TONES } from "./activity-tones";
import { OPEN_WORK_GROUP_DEFS } from "./open-work-groups";
import { PeopleStack } from "./PeopleStack";
import { FAILING_CHECKS, workStateVisual } from "./work-state-defs";

export interface WorkItemRowProps {
	work: WorkItem;
	providerType: ProviderType;
	/** Whose list the row is in; the author is named only when it is someone else. */
	login: string | undefined;
}

/**
 * One open pull request or issue as the provider's own lists draw it: the state icon, the title
 * that opens it there, where it lives — and which of the person's teams it asks, when it asks a team
 * rather than them — and when it last moved, and — at the end — who reviews it and
 * where each review stands, and whether its checks fail.
 */
export function WorkItemRow({ work, providerType, login }: WorkItemRowProps) {
	const state = workStateVisual(work, providerType);
	const reviewers = work.reviewers ?? [];
	const failing = work.checks === "FAILURE";
	const FailingIcon = FAILING_CHECKS.icon(providerType);
	const teams = work.requestedTeams ?? [];
	const TeamIcon = OPEN_WORK_GROUP_DEFS["team-requested"].icon(providerType);
	return (
		<Item render={<li />} variant="row" size="sm" className="items-start">
			<ItemMedia className={cn("mt-0.5", state.colorClass)}>
				<state.icon size={16} aria-label={state.label} />
			</ItemMedia>
			<ItemContent className="min-w-0 basis-56 gap-1">
				<ItemTitle className="w-full min-w-0 font-medium">
					<InlineLink href={work.htmlUrl} external className="min-w-0 break-words">
						{work.title}
					</InlineLink>
				</ItemTitle>
				<ItemDescription className="line-clamp-none text-xs">
					<MetaRow
						captions={[
							workReference(providerType, work),
							teams.length > 0 && (
								<span key="teams" className="inline-flex items-center gap-1">
									<TeamIcon size={12} className="shrink-0" />
									via {andList.format(teams.map((team) => team.name))}
								</span>
							),
							work.author && work.author.login !== login && `by ${work.author.name}`,
							work.updatedAt && (
								<span key="updated">
									updated <RelativeTime value={work.updatedAt} />
								</span>
							),
						]}
					/>
				</ItemDescription>
			</ItemContent>
			{(failing || reviewers.length > 0) && (
				<ItemActions className="gap-3">
					{failing && (
						<span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
							<FailingIcon
								size={14}
								className={cn("shrink-0", ACTIVITY_TONES[FAILING_CHECKS.tone].text)}
							/>
							{FAILING_CHECKS.label}
						</span>
					)}
					{reviewers.length > 0 && (
						<PeopleStack people={reviewers} providerType={providerType} aria-label="Reviewers" />
					)}
				</ItemActions>
			)}
		</Item>
	);
}
