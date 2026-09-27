import { cn } from "cn";
import type { WorkItem } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { MetaRow } from "@/components/common/MetaRow";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/common/StatusBadge";
import { getIssueStateIcon, getPullRequestStateIcon } from "@/components/icons/provider-icons";
import { Item, ItemContent, ItemDescription, ItemMedia, ItemTitle } from "@/components/ui/item";
import { type ProviderType, workReference } from "@/lib/provider/provider-terms";

import { WORK_SIGNAL_DEFS, workSignals } from "./work-signal-defs";

/**
 * One pull request or issue that is open: where it lives, who opened it when that is someone else,
 * when it last moved and what it waits on. The title opens it on the provider, where the work is done.
 */
export function WorkItemRow({
	work,
	providerType,
	login,
}: {
	work: WorkItem;
	providerType: ProviderType;
	/** Whose list the row is in; the author is named only when it is someone else. */
	login: string | undefined;
}) {
	const { icon: StateIcon, colorClass } =
		work.type === "PULL_REQUEST"
			? getPullRequestStateIcon(providerType, work.state, work.isDraft)
			: getIssueStateIcon(work.state);
	const signals = workSignals(work);
	return (
		<Item render={<li />} variant="row" size="sm" className="items-start">
			<ItemMedia className={cn("mt-0.5", colorClass)} aria-hidden>
				<StateIcon size={16} />
			</ItemMedia>
			<ItemContent className="min-w-0 gap-1">
				<ItemTitle className="w-full min-w-0 font-medium">
					<InlineLink href={work.htmlUrl} external className="min-w-0 break-words">
						{work.title}
					</InlineLink>
				</ItemTitle>
				<ItemDescription className="line-clamp-none">
					<MetaRow
						captions={[
							workReference(providerType, work),
							work.author && work.author.login !== login && `by ${work.author.name}`,
							work.updatedAt && (
								<span key="updated">
									updated <RelativeTime value={work.updatedAt} />
								</span>
							),
						]}
						badges={
							signals.length > 0 ? (
								<>
									{signals.map((signal) => (
										<StatusBadge key={signal} def={WORK_SIGNAL_DEFS[signal]} />
									))}
								</>
							) : undefined
						}
					/>
				</ItemDescription>
			</ItemContent>
		</Item>
	);
}
