import type { ActivityPerson } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Badge } from "@/components/ui/badge";
import { Item, ItemContent, ItemDescription, ItemMedia, ItemTitle } from "@/components/ui/item";
import { nameOrder } from "@/lib/text";

import { personLevel } from "./activity-search";
import { MemberAvatar } from "./MemberAvatar";

export interface ActivityAutomationListProps {
	/** Bot accounts, and accounts a workspace admin treats as automation. */
	automation: readonly ActivityPerson[];
}

/** Automation accounts in name order, with links to their activity. */
export function ActivityAutomationList({ automation }: ActivityAutomationListProps) {
	const sorted = [...automation].sort((a, b) => nameOrder.compare(a.person.name, b.person.name));
	return (
		<ul className="overflow-hidden rounded-xl border bg-card">
			{sorted.map(({ person, counts, kind }) => (
				<Item key={person.id} render={<li />} variant="row" size="sm" className="relative">
					<ItemMedia>
						<MemberAvatar user={person} />
					</ItemMedia>
					<ItemContent className="min-w-0">
						<ItemTitle className="min-w-0">
							<InlineLink
								render={<DetailStackLink entry={personLevel(person.login)} />}
								className="truncate after:absolute after:inset-0"
							>
								{person.name}
							</InlineLink>
							<Badge variant="muted">{kind === "BOT" ? "Bot" : "Treated as automation"}</Badge>
						</ItemTitle>
						<ItemDescription className="text-xs tabular-nums">
							{counts.contributions} {counts.contributions === 1 ? "contribution" : "contributions"}
						</ItemDescription>
					</ItemContent>
				</Item>
			))}
		</ul>
	);
}
