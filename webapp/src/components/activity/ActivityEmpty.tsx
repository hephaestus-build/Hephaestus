import { HistoryIcon, PeopleIcon } from "@primer/octicons-react";
import type { ReactElement } from "react";

import type { ProviderType } from "@/lib/provider/provider-terms";

import { GitLabClockIcon, GitLabUsersIcon } from "@/components/icons/gitlab-icons";
import { Empty, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";

import { providerIcon } from "./activity-tones";

export interface ActivityEmptyProps {
	icon: ReactElement;
	/** What is missing, in five words or fewer and no closing period: "No activity in this range". */
	title: string;
}

/** An empty list on an activity surface, in the place and at the width of the list it stands for. */
export function ActivityEmpty({ icon, title }: ActivityEmptyProps) {
	return (
		<Empty variant="outlined">
			<EmptyHeader>
				<EmptyMedia variant="icon">{icon}</EmptyMedia>
				<EmptyTitle>{title}</EmptyTitle>
			</EmptyHeader>
		</Empty>
	);
}

/** The empty state's mark for a list of people, in the provider's icons. */
export const PEOPLE_EMPTY_ICON = providerIcon(PeopleIcon, GitLabUsersIcon);

/** The empty state's mark for a list of work over time, in the provider's icons. */
export const HISTORY_EMPTY_ICON = providerIcon(HistoryIcon, GitLabClockIcon);

/** The history mark as an element, for a caller that has the provider and not the icon. */
export function HistoryMark({ providerType }: { providerType: ProviderType }) {
	const Icon = HISTORY_EMPTY_ICON(providerType);
	return <Icon />;
}
