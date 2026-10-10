import { PeopleIcon } from "@primer/octicons-react";
import type { ActivityCounts } from "@/api/types.gen";
import { GitLabUsersIcon } from "@/components/icons/gitlab-icons";
import type { IconComponent } from "@/components/icons/provider-icons";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { capitalise } from "@/lib/text";

import { CountChip, NoneMark } from "./ActionChip";
import {
	ACTIVITY_CATEGORY_DEFS,
	ACTIVITY_KIND_DEFS,
	type ActivityKind,
	countPhrase,
} from "./activity-kind-defs";
import { type ActivityTone, providerIcon } from "./activity-tones";

/** The table columns that count one category, each with the parts its cell shows. */
export type CountedCategory = "pull-requests" | "reviews" | "issues";

export interface ActivityCountCellProps {
	category: CountedCategory;
	counts: ActivityCounts;
	providerType: ProviderType;
}

const PEOPLE_ICON = providerIcon(PeopleIcon, GitLabUsersIcon);

/**
 * One category's figures in a table row, in the provider's icons and colours: what the column sorts
 * by first, then what it holds — pull requests opened and merged, pull requests reviewed and whose
 * work, issues opened. Each part has a slot of its own width, right-aligned, so a column of rows
 * lines up part by part, and a part with nothing in it is a dash.
 */
export function ActivityCountCell({ category, counts, providerType }: ActivityCountCellProps) {
	const parts = PARTS[category](counts, providerType);
	return (
		<span className="inline-flex items-center justify-end gap-2 align-middle">
			{parts.map((part, index) => (
				<span key={part.key} className="inline-flex min-w-9 justify-end">
					{part.count > 0 ? (
						<CountChip icon={part.icon} tone={part.tone} phrase={part.phrase}>
							<span aria-hidden className="font-medium tabular-nums">
								{part.count.toLocaleString("en-GB")}
							</span>
						</CountChip>
					) : (
						<NoneMark phrase={part.phrase} />
					)}
					{index < parts.length - 1 && <span className="sr-only">, </span>}
				</span>
			))}
		</span>
	);
}

/** A column's icon and name: the provider's icon for its category, in the header's own colour. */
export function ActivityCountHeader({
	category,
	providerType,
}: {
	category: CountedCategory;
	providerType: ProviderType;
}) {
	const Icon = HEADER_ICONS[category](providerType);
	return (
		<span className="inline-flex items-center gap-1.5">
			<Icon size={16} className="shrink-0" />
			{ACTIVITY_CATEGORY_DEFS[category].label(providerType)}
		</span>
	);
}

/** Each column's icon is that of what it sorts by: a pull request opened, not a merge. */
const HEADER_ICONS: Record<CountedCategory, (provider: ProviderType) => IconComponent> = {
	"pull-requests": ACTIVITY_KIND_DEFS.PULL_REQUEST_OPENED.icon,
	reviews: ACTIVITY_CATEGORY_DEFS.reviews.icon,
	issues: ACTIVITY_KIND_DEFS.ISSUE_OPENED.icon,
};

interface Part {
	key: string;
	count: number;
	icon: IconComponent;
	tone: ActivityTone;
	phrase: string;
}

function kindPart(kind: ActivityKind, count: number, provider: ProviderType): Part {
	const def = ACTIVITY_KIND_DEFS[kind];
	return {
		key: kind,
		count,
		icon: def.icon(provider),
		tone: def.tone,
		phrase: capitalise(countPhrase(kind, count, provider)),
	};
}

const PARTS: Record<CountedCategory, (counts: ActivityCounts, provider: ProviderType) => Part[]> = {
	"pull-requests": (counts, provider) => [
		kindPart("PULL_REQUEST_OPENED", counts.pullRequestsOpened, provider),
		kindPart("PULL_REQUEST_MERGED", counts.pullRequestsMerged, provider),
	],
	reviews: (counts, provider) => {
		const def = ACTIVITY_CATEGORY_DEFS.reviews;
		const { one, many } = def.headline.noun(provider);
		const reviewed = counts.pullRequestsReviewed;
		const helped = counts.peopleHelped;
		return [
			{
				key: "reviewed",
				count: reviewed,
				icon: def.icon(provider),
				tone: def.tone,
				phrase: capitalise(`${reviewed} ${reviewed === 1 ? one : many}`),
			},
			{
				key: "helped",
				count: helped,
				icon: PEOPLE_ICON(provider),
				tone: "muted",
				phrase: `Reviewed the work of ${helped} ${helped === 1 ? "person" : "people"}`,
			},
		];
	},
	issues: (counts, provider) => [kindPart("ISSUE_OPENED", counts.issuesOpened, provider)],
};
