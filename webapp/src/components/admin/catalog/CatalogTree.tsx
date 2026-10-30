import { GripVertical, MoreHorizontal } from "lucide-react";

import type { CatalogGroup, CatalogPractice } from "@/api/types.gen";
import {
	type ActionTriggerRef,
	type CatalogEntryMoveActions,
	type CatalogMoveActions,
	SortableCatalogTree,
	UNASSIGNED_CATALOG_BUCKET,
} from "@/components/admin/practice-editor/SortableCatalogTree";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuGroup,
	DropdownMenuItem,
	DropdownMenuLabel,
	DropdownMenuRadioGroup,
	DropdownMenuRadioItem,
	DropdownMenuSeparator,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Item, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { itemKindLabel } from "@/lib/item-kinds";
import { hasText } from "@/lib/text";

import { catalogGroupLevel, catalogPracticeLevel } from "./catalog-search";
import { CatalogEntryBadges } from "./CatalogEntryBadges";

interface TreePractice {
	slug: string;
	name: string;
	groupSlug: string | null;
	position: number;
	missingGroupSlug?: string;
	moveSourceGroupSlug?: string;
	practice: CatalogPractice;
}

// Automated review is the default; badge only exceptions.
const REVIEWED_BY_BADGE = {
	HEPHAESTUS: undefined,
	PEOPLE: "Reviewed by people",
	NOBODY: "Guidance only",
} as const satisfies Record<CatalogPractice["definition"]["reviewedBy"]["by"], string | undefined>;

export interface CatalogTreeProps {
	groups: readonly CatalogGroup[];
	practices: readonly CatalogPractice[];
	visibleGroupSlugs: ReadonlySet<string>;
	visiblePracticeSlugs: ReadonlySet<string>;
	forceOpenGroupSlugs?: ReadonlySet<string>;
	canReorder: boolean;
	writePending: boolean;
	pendingPracticeSlugs: ReadonlySet<string>;
	pendingGroupSlugs: ReadonlySet<string>;
	onPracticeStatusChange: (practice: CatalogPractice, offered: boolean) => void;
	onGroupStatusChange: (group: CatalogGroup, offered: boolean) => void;
	onExcludePractice: (practice: CatalogPractice) => void;
	onExcludeGroup: (group: CatalogGroup) => void;
	onReorderGroups: (orderedSlugs: string[]) => void;
	onPlacePractice: (practiceSlug: string, groupSlug: string | null, position: number) => void;
}

export function CatalogTree({
	groups,
	practices,
	visibleGroupSlugs,
	visiblePracticeSlugs,
	forceOpenGroupSlugs,
	canReorder,
	writePending,
	pendingPracticeSlugs,
	pendingGroupSlugs,
	onPracticeStatusChange,
	onGroupStatusChange,
	onExcludePractice,
	onExcludeGroup,
	onReorderGroups,
	onPlacePractice,
}: CatalogTreeProps) {
	const knownGroups = new Set(groups.map((group) => group.slug));
	const treePractices: TreePractice[] = practices.map((practice) => {
		const missingGroupSlug =
			hasText(practice.groupSlug) && !knownGroups.has(practice.groupSlug)
				? practice.groupSlug
				: undefined;
		return {
			slug: practice.slug,
			name: practice.definition.name,
			groupSlug: missingGroupSlug === undefined ? practice.groupSlug : null,
			position: practice.position,
			missingGroupSlug,
			moveSourceGroupSlug: missingGroupSlug,
			practice,
		};
	});
	const blockedBuckets = canReorder
		? new Set<string>()
		: new Set([...groups.map((group) => group.slug), UNASSIGNED_CATALOG_BUCKET]);

	return (
		<SortableCatalogTree
			groups={groups.filter((group) => visibleGroupSlugs.has(group.slug))}
			entries={treePractices}
			visibleEntrySlugs={visiblePracticeSlugs}
			forceOpenGroupSlugs={forceOpenGroupSlugs}
			groupReorderDisabled={!canReorder || writePending}
			disabledGroupSlugs={pendingGroupSlugs}
			disabledEntrySlugs={pendingPracticeSlugs}
			blockedEntryOrderBuckets={blockedBuckets}
			blockedMoveDestinationSlugs={blockedBuckets}
			showEntryReorderHandles={canReorder && !writePending}
			onReorderGroups={onReorderGroups}
			onPlaceEntry={onPlacePractice}
			renderGroupLeading={(group) => (
				<GroupPill
					slug={group.slug}
					name={group.name}
					icon={group.icon ?? undefined}
					color={group.color ?? undefined}
				/>
			)}
			renderGroupMeta={(group) => <CatalogEntryBadges status={group} kind="group" />}
			renderGroupActions={(group, move, actionTriggerRef) => (
				<GroupActions
					group={group}
					move={move}
					actionTriggerRef={actionTriggerRef}
					pending={pendingGroupSlugs.has(group.slug)}
					disabled={writePending}
					onStatusChange={onGroupStatusChange}
					onExclude={onExcludeGroup}
				/>
			)}
			renderEntryContent={(entry) => <PracticeDetails entry={entry} />}
			renderEntryActions={(entry, move, actionTriggerRef) => (
				<PracticeActions
					entry={entry}
					groups={groups}
					move={move}
					actionTriggerRef={actionTriggerRef}
					pending={pendingPracticeSlugs.has(entry.slug)}
					disabled={writePending}
					onStatusChange={onPracticeStatusChange}
					onExclude={onExcludePractice}
				/>
			)}
			renderEntryPreview={(entry) => <PracticeDragPreview entry={entry} />}
			getEmptyLabel={(groupSlug, total) => {
				if (total > 0) {
					return "No matching practices";
				}
				return groupSlug === null ? "Nothing unassigned" : "No practices here";
			}}
		/>
	);
}

function GroupActions({
	group,
	move,
	actionTriggerRef,
	pending,
	disabled,
	onStatusChange,
	onExclude,
}: {
	group: CatalogGroup;
	move: CatalogMoveActions;
	actionTriggerRef: ActionTriggerRef;
	pending: boolean;
	disabled: boolean;
	onStatusChange: (group: CatalogGroup, offered: boolean) => void;
	onExclude: (group: CatalogGroup) => void;
}) {
	return (
		<>
			{pending && (
				<Spinner className="size-4 text-muted-foreground" role="status" aria-label="Saving" />
			)}
			<Switch
				className="hidden sm:inline-flex"
				checked={!group.excluded}
				onCheckedChange={(offered) => (offered ? onStatusChange(group, true) : onExclude(group))}
				disabled={disabled}
				aria-busy={pending}
				aria-label={`Include ${group.name} for workspaces`}
			/>
			<DropdownMenu>
				<DropdownMenuTrigger
					render={
						<Button
							ref={actionTriggerRef}
							variant="ghost"
							size="icon-sm"
							disabled={disabled}
							aria-label={`More actions for ${group.name}`}
						>
							<MoreHorizontal className="size-4" />
						</Button>
					}
				/>
				<DropdownMenuContent align="end">
					<DropdownMenuItem render={<DetailStackLink entry={catalogGroupLevel(group.slug)} />}>
						Edit group
					</DropdownMenuItem>
					<DropdownMenuSeparator />
					<DropdownMenuGroup>
						<DropdownMenuLabel>Order</DropdownMenuLabel>
						<DropdownMenuItem disabled={!move.canMoveUp} onClick={move.moveUp}>
							Move up
						</DropdownMenuItem>
						<DropdownMenuItem disabled={!move.canMoveDown} onClick={move.moveDown}>
							Move down
						</DropdownMenuItem>
					</DropdownMenuGroup>
					<DropdownMenuSeparator />
					{group.excluded ? (
						<DropdownMenuItem onClick={() => onStatusChange(group, true)}>
							Include for workspaces
						</DropdownMenuItem>
					) : (
						<DropdownMenuItem variant="destructive" onClick={() => onExclude(group)}>
							Exclude from workspaces
						</DropdownMenuItem>
					)}
				</DropdownMenuContent>
			</DropdownMenu>
		</>
	);
}

function PracticeDetails({ entry }: { entry: TreePractice }) {
	const { practice } = entry;
	const parentUnavailable =
		hasText(entry.missingGroupSlug) || (!practice.excluded && !practice.offered);
	const reviewedBy = REVIEWED_BY_BADGE[practice.definition.reviewedBy.by];
	return (
		<ItemContent className="min-w-0">
			<ItemTitle className="line-clamp-none w-full min-w-0">
				<DetailStackLink
					entry={catalogPracticeLevel(practice.slug)}
					className="rounded-sm break-words hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-hidden"
				>
					{entry.name}
				</DetailStackLink>
			</ItemTitle>
			<ItemDescription className="flex flex-wrap items-center gap-1.5">
				<span>{itemKindLabel(practice.kind)}</span>
				{reviewedBy !== undefined && <Badge variant="outline">{reviewedBy}</Badge>}
				{parentUnavailable && (
					<Badge variant="outline">
						{hasText(entry.missingGroupSlug)
							? "Group no longer exists"
							: "Excluded because its group is excluded"}
					</Badge>
				)}
				<CatalogEntryBadges status={practice} kind="practice" />
			</ItemDescription>
		</ItemContent>
	);
}

function PracticeActions({
	entry,
	groups,
	move,
	actionTriggerRef,
	pending,
	disabled,
	onStatusChange,
	onExclude,
}: {
	entry: TreePractice;
	groups: readonly CatalogGroup[];
	move: CatalogEntryMoveActions;
	actionTriggerRef: ActionTriggerRef;
	pending: boolean;
	disabled: boolean;
	onStatusChange: (practice: CatalogPractice, offered: boolean) => void;
	onExclude: (practice: CatalogPractice) => void;
}) {
	const { practice } = entry;
	const missingGroup = hasText(entry.missingGroupSlug);
	const group = hasText(entry.groupSlug)
		? groups.find((candidate) => candidate.slug === entry.groupSlug)
		: undefined;
	const parentUnavailable = missingGroup || group?.excluded === true;
	let includeLabel = "Include for workspaces";
	let switchLabel = `Include ${entry.name} for workspaces`;
	if (missingGroup) {
		includeLabel = "Move to Unassigned or an included group first";
		switchLabel = `${entry.name} cannot be included until it is moved out of the missing group`;
	} else if (parentUnavailable) {
		includeLabel = "Include when its group is included";
		switchLabel = practice.excluded
			? `${entry.name} is excluded from workspaces`
			: `${entry.name} is excluded because its group is excluded`;
	}
	return (
		<>
			{pending && (
				<Spinner className="size-4 text-muted-foreground" role="status" aria-label="Saving" />
			)}
			<Switch
				className="hidden sm:inline-flex"
				checked={practice.offered}
				onCheckedChange={(offered) =>
					offered ? onStatusChange(practice, true) : onExclude(practice)
				}
				disabled={disabled || parentUnavailable}
				aria-busy={pending}
				aria-label={switchLabel}
			/>
			<DropdownMenu>
				<DropdownMenuTrigger
					render={
						<Button
							ref={actionTriggerRef}
							variant="ghost"
							size="icon-sm"
							disabled={disabled}
							aria-label={`More actions for ${entry.name}`}
						>
							<MoreHorizontal className="size-4" />
						</Button>
					}
				/>
				<DropdownMenuContent align="end">
					<DropdownMenuItem
						render={<DetailStackLink entry={catalogPracticeLevel(practice.slug)} />}
					>
						Edit practice
					</DropdownMenuItem>
					<DropdownMenuSeparator />
					<DropdownMenuGroup>
						<DropdownMenuLabel>Order</DropdownMenuLabel>
						<DropdownMenuItem disabled={!move.canMoveUp} onClick={move.moveUp}>
							Move up
						</DropdownMenuItem>
						<DropdownMenuItem disabled={!move.canMoveDown} onClick={move.moveDown}>
							Move down
						</DropdownMenuItem>
					</DropdownMenuGroup>
					<DropdownMenuSeparator />
					<DropdownMenuGroup>
						<DropdownMenuLabel>Move to</DropdownMenuLabel>
						<DropdownMenuRadioGroup
							value={entry.missingGroupSlug ?? entry.groupSlug ?? UNASSIGNED_CATALOG_BUCKET}
							onValueChange={(value) =>
								move.moveTo(value === UNASSIGNED_CATALOG_BUCKET ? null : value)
							}
						>
							<DropdownMenuRadioItem
								value={UNASSIGNED_CATALOG_BUCKET}
								disabled={move.currentGroupSlug !== null && !move.canMoveTo(null)}
								closeOnClick
							>
								Unassigned
							</DropdownMenuRadioItem>
							{groups.map((destination) => (
								<DropdownMenuRadioItem
									key={destination.slug}
									value={destination.slug}
									disabled={
										move.currentGroupSlug !== destination.slug && !move.canMoveTo(destination.slug)
									}
									closeOnClick
								>
									{destination.name}
									{destination.excluded && " (excluded)"}
								</DropdownMenuRadioItem>
							))}
						</DropdownMenuRadioGroup>
					</DropdownMenuGroup>
					<DropdownMenuSeparator />
					{practice.excluded ? (
						<DropdownMenuItem
							disabled={missingGroup}
							onClick={() => onStatusChange(practice, true)}
						>
							{includeLabel}
						</DropdownMenuItem>
					) : (
						<DropdownMenuItem variant="destructive" onClick={() => onExclude(practice)}>
							Exclude from workspaces
						</DropdownMenuItem>
					)}
				</DropdownMenuContent>
			</DropdownMenu>
		</>
	);
}

function PracticeDragPreview({ entry }: { entry: TreePractice }) {
	return (
		<Item aria-hidden="true" variant="overlay" size="xs" className="flex-nowrap">
			<div className="flex size-8 shrink-0 items-center justify-center text-muted-foreground">
				<GripVertical className="size-4" />
			</div>
			<ItemContent className="min-w-0">
				<ItemTitle className="line-clamp-none break-words">{entry.name}</ItemTitle>
				<ItemDescription>{itemKindLabel(entry.practice.kind)}</ItemDescription>
			</ItemContent>
		</Item>
	);
}
