import { ChevronRight, Library } from "lucide-react";

import type { CatalogPracticeSummary } from "@/api/types.gen";
import { StatusBadge } from "@/components/common/StatusBadge";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Section } from "@/components/layout/Section";
import { CATALOG_AVAILABILITY_DEFS } from "@/components/practice-vocabulary/catalog-availability-defs";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { WorkTypeLabel } from "@/components/practice-vocabulary/WorkTypeLabel";
import { buttonVariants } from "@/components/ui/button";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemGroup,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import { hasText } from "@/lib/text";

export interface AvailablePracticeListProps {
	practices: CatalogPracticeSummary[];
	existingGroupSlugs: ReadonlySet<string>;
}

export function AvailablePracticeList({
	practices,
	existingGroupSlugs,
}: AvailablePracticeListProps) {
	const offered = practices.filter(
		(practice) =>
			practice.availability !== "ADOPTED" ||
			(practice.groupSlug !== undefined && !existingGroupSlugs.has(practice.groupSlug)),
	);

	if (offered.length === 0) {
		const allAdded = practices.length > 0;
		return (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<Library />
					</EmptyMedia>
					<EmptyTitle>{allAdded ? "Everything is already added" : "Nothing to add"}</EmptyTitle>
					<EmptyDescription>
						{allAdded
							? "This workspace already has every practice the catalog currently includes."
							: "The instance catalog includes no practices for this workspace yet."}
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}

	const groups = new Map<string, CatalogPracticeSummary[]>();
	for (const practice of offered) {
		const key = practice.groupSlug ?? "__unassigned__";
		groups.set(key, [...(groups.get(key) ?? []), practice]);
	}

	return (
		<div className="space-y-5">
			{Array.from(groups, ([key, entries]) => {
				const [first] = entries;
				if (!first) {
					return null;
				}
				const { groupSlug } = first;
				const available = entries.filter(({ availability }) => availability === "AVAILABLE").length;
				const restorable = entries.filter(({ availability }) => availability === "ADOPTED").length;
				const groupMissing = groupSlug !== undefined && !existingGroupSlugs.has(groupSlug);

				return (
					<Section
						key={key}
						size="sm"
						level={3}
						title={
							<span className="flex items-center gap-2">
								<GroupPill size="sm" slug={groupSlug} name={first.groupName} />
								{first.groupName ?? "Unassigned"}
							</span>
						}
						actions={
							hasText(groupSlug) && (available > 0 || (groupMissing && restorable > 0)) ? (
								<DetailStackLink
									entry={{ kind: "catalog-group", id: groupSlug }}
									className={buttonVariants({ size: "sm", variant: "outline" })}
								>
									{reviewLabel(groupMissing, available, restorable)}
								</DetailStackLink>
							) : (
								<span className="text-xs text-muted-foreground">{countLabel(entries.length)}</span>
							)
						}
					>
						<ItemGroup>
							{entries.map((practice) => (
								<div key={practice.slug} role="listitem">
									<PracticeRow practice={practice} />
								</div>
							))}
						</ItemGroup>
					</Section>
				);
			})}
		</div>
	);
}

function countLabel(count: number): string {
	return `${count} ${count === 1 ? "practice" : "practices"}`;
}

function reviewLabel(groupMissing: boolean, available: number, restorable: number): string {
	if (!groupMissing) {
		return `Review ${countLabel(available)}`;
	}
	// The whole group can only be reviewed at once when it is still to be created here.
	if (available === 0) {
		return `Restore group · ${countLabel(restorable)}`;
	}
	return `Review group · ${countLabel(available)}`;
}

function PracticeRow({ practice }: { practice: CatalogPracticeSummary }) {
	const def = CATALOG_AVAILABILITY_DEFS[practice.availability];
	// Both open as a drawer level, so the catalog never has to be left to look at something in it.
	const link =
		practice.availability === "ADOPTED" ? (
			<DetailStackLink entry={{ kind: "practice", id: practice.slug }} />
		) : (
			<DetailStackLink entry={{ kind: "catalog-practice", id: practice.slug }} />
		);

	return (
		// No `aria-label`: it would replace the work type and group below it for a screen reader. The
		// visible text is the name; the registry's verb phrase stands in for the chevron.
		<Item variant="outline" render={link}>
			<ItemMedia variant="icon">
				<GroupPill size="md" slug={practice.groupSlug} name={practice.groupName} />
			</ItemMedia>
			<ItemContent className="min-w-0">
				<ItemTitle className="line-clamp-none break-words">
					{practice.name}
					<span className="sr-only">, {def.action}</span>
				</ItemTitle>
				<ItemDescription className="line-clamp-none">
					<WorkTypeLabel artifactKind={practice.artifactKind} />
				</ItemDescription>
				{/* Without this the rows differ only by name: the large majority of the bundled catalogue
				    reviews a pull request, so the work type above separates almost none of them. */}
				{hasText(practice.whyItMatters) && (
					<ItemDescription className="line-clamp-2 text-pretty">
						{practice.whyItMatters}
					</ItemDescription>
				)}
			</ItemContent>
			<ItemActions>
				{def.badged && <StatusBadge def={def} />}
				<ChevronRight className="size-4 text-muted-foreground" aria-hidden="true" />
			</ItemActions>
		</Item>
	);
}
