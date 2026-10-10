import { HeartIcon, SparklesIcon } from "lucide-react";
import type { ReactElement } from "react";

import type { ActivityPerson } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

import { personLevel } from "./activity-search";
import { MemberAvatar } from "./MemberAvatar";

export interface ActivityHighlightsProps {
	/** People whose first contribution to the workspace is in the period. */
	firstContributors: readonly ActivityPerson[];
	/** The people whose reviews helped the most other people; more than one on a tie. */
	mostPeopleHelped: readonly ActivityPerson[];
}

/**
 * Two things worth a thank-you in the period: who contributed for the first time, and whose reviews
 * reached the most people. A card shows only when the server named someone for it.
 */
export function ActivityHighlights({
	firstContributors,
	mostPeopleHelped,
}: ActivityHighlightsProps) {
	const helped = mostPeopleHelped[0]?.counts.peopleHelped ?? 0;
	return (
		<div className="grid gap-3 sm:grid-cols-2">
			{firstContributors.length > 0 && (
				<Highlight
					icon={<SparklesIcon aria-hidden className="size-4 text-muted-foreground" />}
					title="First contributions"
					description={
						firstContributors.length === 1
							? "Contributed for the first time in this range."
							: `${firstContributors.length} people contributed for the first time in this range.`
					}
					people={firstContributors}
				/>
			)}
			{mostPeopleHelped.length > 0 && (
				<Highlight
					icon={<HeartIcon aria-hidden className="size-4 text-muted-foreground" />}
					title="Most people helped"
					description={`Reviewed the work of ${helped} ${helped === 1 ? "person" : "people"}.`}
					people={mostPeopleHelped}
				/>
			)}
		</div>
	);
}

function Highlight({
	icon,
	title,
	description,
	people,
}: {
	icon: ReactElement;
	title: string;
	description: string;
	people: readonly ActivityPerson[];
}) {
	return (
		<Card size="sm">
			<CardHeader>
				<CardTitle className="flex items-center gap-2">
					{icon}
					{title}
				</CardTitle>
				<CardDescription>{description}</CardDescription>
			</CardHeader>
			<CardContent>
				<ul className="flex flex-wrap gap-x-4 gap-y-2">
					{people.map(({ person }) => (
						<li key={person.id} className="flex min-w-0 items-center gap-2">
							<MemberAvatar user={person} size="sm" />
							<InlineLink
								render={<DetailStackLink entry={personLevel(person.login)} />}
								className="truncate"
							>
								{person.name}
							</InlineLink>
						</li>
					))}
				</ul>
			</CardContent>
		</Card>
	);
}
