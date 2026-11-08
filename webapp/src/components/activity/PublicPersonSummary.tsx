import { cn } from "cn";
import { ChevronRightIcon } from "lucide-react";
import { FOCUS_RING } from "@/components/common/focus";
import { STAT_TILE_GRID, StatTile } from "@/components/common/StatTile";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { formatCalendarDate } from "@/lib/dates";
import { ITEM_KIND, itemKindNoun } from "@/lib/item-kinds";
import { formatNumber } from "@/lib/numbers";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { capitalise } from "@/lib/text";
import {
	ACTIVITY_CATEGORY_DEFS,
	ACTIVITY_KIND_DEFS,
	type ActivityCategory,
	type ActivityCategoryDef,
} from "./activity-kind-defs";
import { contributionsNote, type PeopleRow } from "./activity-people-rows";
import { categoryLevel } from "./activity-search";
import { contributionWeekRows } from "./activity-tally";
import { ACTIVITY_TONES } from "./activity-tones";
import { BucketBars } from "./BucketBars";
import { publicActivitySpanLabel } from "./public-person-activity";

/** A published total as a tile: the registry's glyph where one means the same thing, else none. */
interface Total {
	title: string;
	value: number;
	qualifier: string;
	secondary?: { count: number; label: string };
	category: ActivityCategory;
	mark?: Pick<ActivityCategoryDef, "icon" | "tone">;
}

/** The seven published totals and exact weekly contribution values, with work-kind drilldowns. */
export function PublicPersonSummary({
	row,
	providerType,
	stale,
	span,
}: {
	row: Pick<PeopleRow, "counts" | "weeks">;
	providerType: ProviderType;
	stale: boolean;
	span: { from: Date; to: Date };
}) {
	const pullRequests = itemKindNoun(ITEM_KIND.pullRequest, 2, providerType);
	const { counts } = row;
	const spanLabel = publicActivitySpanLabel(span.from, span.to);
	const others: Total[] = [
		{
			title: capitalise(pullRequests),
			category: "pull-requests",
			value: counts.pullRequestsMerged,
			qualifier: ACTIVITY_CATEGORY_DEFS["pull-requests"].headline.qualifier,
			secondary: { count: counts.pullRequestsOpened, label: "opened" },
			mark: ACTIVITY_CATEGORY_DEFS["pull-requests"],
		},
		{
			title: "Reviews",
			category: "reviews",
			value: counts.pullRequestsReviewed,
			qualifier: `distinct ${pullRequests}`,
			secondary: {
				count: counts.peopleHelped,
				label: `${counts.peopleHelped === 1 ? "person" : "people"} helped`,
			},
			mark: ACTIVITY_CATEGORY_DEFS.reviews,
		},
		{
			title: "Issues",
			category: "issues",
			value: counts.issuesOpened,
			qualifier: "opened",
			mark: ACTIVITY_KIND_DEFS.ISSUE_OPENED,
		},
	];
	return (
		<>
			{stale && (
				<p className="text-sm text-muted-foreground">
					{spanLabel === undefined
						? "These figures are updating."
						: `These figures are for ${spanLabel} while activity updates.`}
				</p>
			)}
			<div className="@container">
				<ul aria-label="Published totals" className={STAT_TILE_GRID}>
					<li className="flex">
						<StatTile
							variant={counts.contributions > 0 || counts.activeWeeks > 0 ? undefined : "muted"}
							icon={null}
							title="Contributions"
							value={formatNumber(counts.contributions)}
							detail={
								<p className="text-xs text-muted-foreground">
									{formatNumber(counts.activeWeeks)} active{" "}
									{counts.activeWeeks === 1 ? "week" : "weeks"}
								</p>
							}
						>
							{counts.contributions > 0 && (
								<BucketBars
									rows={contributionWeekRows(row.weeks, span)}
									bucket="WEEK"
									utcWeeks
									span={span}
									name="Contributions"
									fill={ACTIVITY_TONES.accent.fill}
								/>
							)}
						</StatTile>
					</li>
					{others.map((total) => (
						<li key={total.title} className="flex">
							<TotalTile total={total} providerType={providerType} />
						</li>
					))}
				</ul>
				<p className="text-xs text-muted-foreground">{contributionsNote(providerType)}</p>
			</div>
			<Collapsible>
				<CollapsibleTrigger render={<Button variant="ghost" size="sm" />}>
					<ChevronRightIcon
						data-slot="icon"
						className="transition-transform in-data-open:rotate-90"
					/>
					Show weekly contributions
				</CollapsibleTrigger>
				<CollapsibleContent className="pt-3">
					{row.weeks.length === 0 ? (
						<p className="text-sm text-muted-foreground">No weekly activity in this range.</p>
					) : (
						<Table aria-label="Contributions by week">
							<TableHeader>
								<TableRow variant="static">
									<TableHead>Week of (UTC)</TableHead>
									<TableHead numeric className="text-right">
										Contributions
									</TableHead>
								</TableRow>
							</TableHeader>
							<TableBody>
								{row.weeks.map((week) => (
									<TableRow key={week.start.getTime()} variant="static">
										<TableCell>{formatCalendarDate(week.start)}</TableCell>
										<TableCell numeric className="text-right">
											{formatNumber(week.contributions)}
										</TableCell>
									</TableRow>
								))}
							</TableBody>
						</Table>
					)}
					<p className="text-xs text-muted-foreground">
						Weeks with activity are listed. Each week counts its distinct reviews on its own, so the
						weeks can add up to more than the range’s total.
					</p>
				</CollapsibleContent>
			</Collapsible>
		</>
	);
}

/** One total's tile, muted at zero as a member's tile with nothing behind it is. */
function TotalTile({ total, providerType }: { total: Total; providerType: ProviderType }) {
	const { title, value, qualifier, secondary, mark, category } = total;
	const active = value > 0 || (secondary !== undefined && secondary.count > 0);
	const Icon = mark?.icon(providerType);
	const card = (
		<StatTile
			variant={active ? "interactive" : "muted"}
			icon={
				Icon !== undefined &&
				mark !== undefined && (
					<Icon
						size={16}
						className={cn(
							"shrink-0",
							active ? ACTIVITY_TONES[mark.tone].text : "text-muted-foreground",
						)}
					/>
				)
			}
			title={title}
			value={formatNumber(value)}
			qualifier={qualifier}
			detail={
				secondary === undefined ? undefined : (
					<p className="text-xs text-muted-foreground">
						{formatNumber(secondary.count)} {secondary.label}
					</p>
				)
			}
		/>
	);
	return active ? (
		<DetailStackLink
			entry={categoryLevel(category)}
			className={cn("flex w-full rounded-xl", FOCUS_RING)}
		>
			{card}
		</DetailStackLink>
	) : (
		card
	);
}
