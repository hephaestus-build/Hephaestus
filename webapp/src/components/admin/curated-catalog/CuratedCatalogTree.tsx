import { GripVertical, MoreHorizontal } from "lucide-react";

import type { CuratedGroup, CuratedPracticeSummary } from "@/api/types.gen";
import { automatedReviewLimitationLabel } from "@/components/admin/practice-editor/evidence-presentation";
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
import { artifactKindLabel } from "@/lib/artifact-kinds";
import { hasText } from "@/lib/text";

import { curatedGroupLevel, curatedPracticeLevel } from "./curated-catalog-search";
import { CuratedEntryBadges } from "./CuratedEntryBadges";

type TreeGroup = CuratedGroup & { displayOrder: number; name: string };
type TreePractice = CuratedPracticeSummary & {
	displayOrder: number;
	missingGroupSlug?: string;
	moveSourceGroupSlug?: string;
};

export interface CuratedCatalogTreeProps {
	groups: readonly CuratedGroup[];
	practices: readonly CuratedPracticeSummary[];
	visibleGroupSlugs: ReadonlySet<string>;
	visiblePracticeSlugs: ReadonlySet<string>;
	forceOpenGroupSlugs?: ReadonlySet<string>;
	canReorder: boolean;
	writePending: boolean;
	pendingPracticeSlugs: ReadonlySet<string>;
	pendingGroupSlugs: ReadonlySet<string>;
	onPracticeStatusChange: (practice: CuratedPracticeSummary, offered: boolean) => void;
	onGroupStatusChange: (group: CuratedGroup, offered: boolean) => void;
	onExcludePractice: (practice: CuratedPracticeSummary) => void;
	onExcludeGroup: (group: CuratedGroup) => void;
	onReorderGroups: (orderedSlugs: string[]) => void;
	onPlacePractice: (practiceSlug: string, groupSlug: string | null, position: number) => void;
}

export function CuratedCatalogTree({
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
}: CuratedCatalogTreeProps) {
	const knownGroups = new Set(groups.map((group) => group.slug));
	const treeGroups: TreeGroup[] = groups.map((group) => ({
		...group,
		name: group.definition.name,
		displayOrder: group.position,
	}));
	const treePractices: TreePractice[] = practices.map((practice) => ({
		...practice,
		groupSlug:
			hasText(practice.groupSlug) && knownGroups.has(practice.groupSlug)
				? practice.groupSlug
				: undefined,
		displayOrder: practice.position,
		missingGroupSlug:
			hasText(practice.groupSlug) && !knownGroups.has(practice.groupSlug)
				? practice.groupSlug
				: undefined,
		moveSourceGroupSlug:
			hasText(practice.groupSlug) && !knownGroups.has(practice.groupSlug)
				? practice.groupSlug
				: undefined,
	}));
	const blockedBuckets = canReorder
		? new Set<string>()
		: new Set([...groups.map((group) => group.slug), UNASSIGNED_CATALOG_BUCKET]);

	return (
		<SortableCatalogTree
			groups={treeGroups.filter((group) => visibleGroupSlugs.has(group.slug))}
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
			renderGroupLeading={(group) => <GroupIcon group={group} />}
			renderGroupMeta={(group) => <CuratedEntryBadges status={group.status} kind="group" />}
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
			renderEntryContent={(practice) => <PracticeDetails practice={practice} />}
			renderEntryActions={(practice, move, actionTriggerRef) => (
				<PracticeActions
					practice={practice}
					groups={treeGroups}
					move={move}
					actionTriggerRef={actionTriggerRef}
					pending={pendingPracticeSlugs.has(practice.slug)}
					disabled={writePending}
					onStatusChange={onPracticeStatusChange}
					onExclude={onExcludePractice}
				/>
			)}
			renderEntryPreview={(practice) => <PracticeDragPreview practice={practice} />}
			getEmptyLabel={(groupSlug, total) => {
				if (total > 0) {
					return "No matching practices.";
				}
				return groupSlug === null ? "Nothing unassigned." : "No practices here.";
			}}
		/>
	);
}

function GroupIcon({ group }: { group: TreeGroup }) {
	return (
		<GroupPill
			slug={group.slug}
			name={group.definition.name}
			icon={group.definition.icon}
			color={group.definition.color}
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
	group: TreeGroup;
	move: CatalogMoveActions;
	actionTriggerRef: ActionTriggerRef;
	pending: boolean;
	disabled: boolean;
	onStatusChange: (group: CuratedGroup, offered: boolean) => void;
	onExclude: (group: CuratedGroup) => void;
}) {
	return (
		<>
			{pending && (
				<Spinner className="size-4 text-muted-foreground" role="status" aria-label="Saving" />
			)}
			<Switch
				className="hidden sm:inline-flex"
				checked={group.status.offered}
				onCheckedChange={(offered) => (offered ? onStatusChange(group, true) : onExclude(group))}
				disabled={disabled}
				aria-busy={pending}
				aria-label={`Offer ${group.definition.name} to workspaces`}
			/>
			<DropdownMenu>
				<DropdownMenuTrigger
					render={
						<Button
							ref={actionTriggerRef}
							variant="ghost"
							size="icon-sm"
							disabled={disabled}
							aria-label={`More actions for ${group.definition.name}`}
						>
							<MoreHorizontal className="size-4" />
						</Button>
					}
				/>
				<DropdownMenuContent align="end">
					<DropdownMenuItem render={<DetailStackLink entry={curatedGroupLevel(group.slug)} />}>
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
					{group.status.offered ? (
						<DropdownMenuItem variant="destructive" onClick={() => onExclude(group)}>
							Stop offering
						</DropdownMenuItem>
					) : (
						<DropdownMenuItem onClick={() => onStatusChange(group, true)}>
							Offer to workspaces
						</DropdownMenuItem>
					)}
				</DropdownMenuContent>
			</DropdownMenu>
		</>
	);
}

function PracticeDetails({ practice }: { practice: TreePractice }) {
	const parentUnavailable =
		Boolean(practice.missingGroupSlug) || (practice.status.offered && !practice.effectivelyOffered);
	const reviewLimitation = automatedReviewLimitationLabel(practice.automatedReview);
	return (
		<ItemContent className="min-w-0">
			<ItemTitle className="line-clamp-none w-full min-w-0">
				<DetailStackLink
					entry={curatedPracticeLevel(practice.slug)}
					className="rounded-sm break-words hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
				>
					{practice.name}
				</DetailStackLink>
			</ItemTitle>
			<ItemDescription className="flex flex-wrap items-center gap-1.5">
				<span>{artifactKindLabel(practice.artifactKind)}</span>
				{reviewLimitation && <Badge variant="outline">{reviewLimitation}</Badge>}
				{parentUnavailable && (
					<Badge variant="outline">
						{hasText(practice.missingGroupSlug)
							? "Group no longer exists"
							: "Excluded because its group is excluded"}
					</Badge>
				)}
				<CuratedEntryBadges status={practice.status} kind="practice" />
			</ItemDescription>
		</ItemContent>
	);
}

function PracticeActions({
	practice,
	groups,
	move,
	actionTriggerRef,
	pending,
	disabled,
	onStatusChange,
	onExclude,
}: {
	practice: TreePractice;
	groups: readonly TreeGroup[];
	move: CatalogEntryMoveActions;
	actionTriggerRef: ActionTriggerRef;
	pending: boolean;
	disabled: boolean;
	onStatusChange: (practice: CuratedPracticeSummary, offered: boolean) => void;
	onExclude: (practice: CuratedPracticeSummary) => void;
}) {
	const group = hasText(practice.groupSlug)
		? groups.find((candidate) => candidate.slug === practice.groupSlug)
		: undefined;
	const parentUnavailable = Boolean(practice.missingGroupSlug) || group?.status.offered === false;
	let includeLabel = "Include for workspaces";
	let switchLabel = `Offer ${practice.name} to workspaces`;
	if (hasText(practice.missingGroupSlug)) {
		includeLabel = "Move to Unassigned or an included group first";
		switchLabel = `${practice.name} cannot be included until it is moved out of the missing group`;
	} else if (parentUnavailable) {
		includeLabel = "Include when its group is included";
		switchLabel = practice.status.offered
			? `${practice.name} is excluded because its group is excluded`
			: `${practice.name} is not offered to workspaces`;
	}
	const persistedPractice = hasText(practice.missingGroupSlug)
		? { ...practice, groupSlug: practice.missingGroupSlug }
		: practice;
	return (
		<>
			{pending && (
				<Spinner className="size-4 text-muted-foreground" role="status" aria-label="Saving" />
			)}
			<Switch
				className="hidden sm:inline-flex"
				checked={practice.effectivelyOffered}
				onCheckedChange={(offered) =>
					offered ? onStatusChange(persistedPractice, true) : onExclude(persistedPractice)
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
							aria-label={`More actions for ${practice.name}`}
						>
							<MoreHorizontal className="size-4" />
						</Button>
					}
				/>
				<DropdownMenuContent align="end">
					<DropdownMenuItem
						render={<DetailStackLink entry={curatedPracticeLevel(practice.slug)} />}
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
							value={practice.missingGroupSlug ?? practice.groupSlug ?? UNASSIGNED_CATALOG_BUCKET}
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
									{destination.definition.name}
									{!destination.status.offered && " (excluded)"}
								</DropdownMenuRadioItem>
							))}
						</DropdownMenuRadioGroup>
					</DropdownMenuGroup>
					<DropdownMenuSeparator />
					{practice.status.offered ? (
						<DropdownMenuItem variant="destructive" onClick={() => onExclude(persistedPractice)}>
							Stop offering
						</DropdownMenuItem>
					) : (
						<DropdownMenuItem
							disabled={Boolean(practice.missingGroupSlug)}
							onClick={() => onStatusChange(persistedPractice, true)}
						>
							{includeLabel}
						</DropdownMenuItem>
					)}
				</DropdownMenuContent>
			</DropdownMenu>
		</>
	);
}

function PracticeDragPreview({ practice }: { practice: TreePractice }) {
	return (
		<Item aria-hidden="true" variant="overlay" size="xs" className="flex-nowrap">
			<div className="flex size-8 shrink-0 items-center justify-center text-muted-foreground">
				<GripVertical className="size-4" />
			</div>
			<ItemContent className="min-w-0">
				<ItemTitle className="line-clamp-none break-words">{practice.name}</ItemTitle>
				<ItemDescription>{artifactKindLabel(practice.artifactKind)}</ItemDescription>
			</ItemContent>
		</Item>
	);
}
