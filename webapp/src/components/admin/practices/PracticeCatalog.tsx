import { Link } from "@tanstack/react-router";
import { GripVertical, Library, ListChecks, MoreHorizontal, Plus } from "lucide-react";
import { type ReactNode, useState } from "react";

import { cn } from "cn";
import type {
	CatalogPracticeSummary,
	Practice,
	PracticeDefinitionOptions,
	PracticeGroup,
} from "@/api/types.gen";
import { AvailablePracticeList } from "@/components/admin/practice-adoption/AvailablePracticeList";
import { WORK_TYPE_FILTER_OPTIONS } from "@/components/admin/practice-catalog/constants";
import { automatedReviewUnavailableLabel } from "@/components/admin/practice-catalog/evidence-presentation";
import {
	type GroupDetails,
	GroupDetailsDialog,
} from "@/components/admin/practice-catalog/GroupDetailsDialog";
import { GroupVisualPicker } from "@/components/admin/practice-catalog/GroupVisualPicker";
import {
	type ActionTriggerRef,
	type CatalogEntryMoveActions,
	type CatalogMoveActions,
	SortableCatalogTree,
	UNASSIGNED_CATALOG_BUCKET,
} from "@/components/admin/practice-catalog/SortableCatalogTree";
import { practiceFormLevel } from "@/components/admin/practices/practice-search";
import { PracticeListSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { FilterToggle } from "@/components/common/FilterToggle";
import { MetaRow } from "@/components/common/MetaRow";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { DetailStackLink } from "@/components/core/detail-drawer/DetailStackLink";
import { Section } from "@/components/core/Section";
import { AutonomyBadge } from "@/components/practice-vocabulary/AutonomyBadge";
import { AutonomySourceNote } from "@/components/practice-vocabulary/AutonomySourceNote";
import { DASHBOARD_VISIBILITY_DEFS } from "@/components/practice-vocabulary/dashboard-visibility-defs";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { WorkTypeLabel } from "@/components/practice-vocabulary/WorkTypeLabel";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
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
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Item, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import { Switch } from "@/components/ui/switch";
import { Toggle } from "@/components/ui/toggle";
import { artifactKindPluralLabel, type KnownArtifactKind } from "@/lib/artifact-kinds";
import { autonomySourceOf } from "@/lib/practice-autonomy";

import { CatalogOriginBadge } from "./CatalogOriginBadge";

export type FocusFilter = "ALL" | KnownArtifactKind;

export interface PracticeCatalogPendingState {
	groupSlugs: ReadonlySet<string>;
	practiceSlugs: ReadonlySet<string>;
	groupStructure: boolean;
	blockedMoveDestinationSlugs: ReadonlySet<string>;
	blockedPracticeOrderBuckets: ReadonlySet<string>;
	creatingGroup: boolean;
}

export type LibraryState = PanelState<{ practices: CatalogPracticeSummary[] }>;

export interface PracticeLibrary {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	state: LibraryState;
}

export interface PracticeCatalogProps {
	workspaceSlug: string;
	groups: PracticeGroup[];
	practices: Practice[];
	definitionOptions: PracticeDefinitionOptions;
	pending: PracticeCatalogPendingState;
	focusFilter: FocusFilter;
	onFocusFilterChange: (f: FocusFilter) => void;
	onCreateGroup: (details: GroupDetails) => Promise<boolean>;
	onUpdateGroup: (slug: string, details: GroupDetails) => Promise<boolean>;
	onSetGroupDashboardVisibility: (slug: string, visibleInPracticeDashboards: boolean) => void;
	onDeleteGroup: (slug: string) => void;
	onReorderGroups: (orderedSlugs: string[]) => void;
	onSetGroupVisual: (slug: string, patch: { icon?: string; color?: string }) => void;
	onDeletePractice: (practice: Practice) => void;
	onPlacePractice: (practiceSlug: string, groupSlug: string | null, position: number) => void;
	library?: PracticeLibrary;
}

export function PracticeCatalog({
	workspaceSlug,
	groups,
	practices,
	definitionOptions,
	pending,
	focusFilter,
	onFocusFilterChange,
	onCreateGroup,
	onUpdateGroup,
	onSetGroupDashboardVisibility,
	onDeleteGroup,
	onReorderGroups,
	onSetGroupVisual,
	onDeletePractice,
	onPlacePractice,
	library,
}: PracticeCatalogProps) {
	const [namingGroup, setNamingGroup] = useState<PracticeGroup | null | undefined>(undefined);
	const visiblePracticeSlugs = new Set(
		practices
			.filter((practice) => focusFilter === "ALL" || practice.artifactKind === focusFilter)
			.map((practice) => practice.slug),
	);
	const visibleCatalogPractices =
		library?.state.status === "ready"
			? library.state.practices.filter(
					(practice) => focusFilter === "ALL" || practice.artifactKind === focusFilter,
				)
			: undefined;
	const forceOpenGroupSlugs =
		focusFilter === "ALL"
			? undefined
			: new Set(
					practices
						.filter((practice) => visiblePracticeSlugs.has(practice.slug))
						.map((practice) => practice.groupSlug)
						.filter((slug): slug is string => Boolean(slug)),
				);
	const supportedModesFor = (practice: Practice) =>
		definitionOptions.workTypes.find((option) => option.artifactKind === practice.artifactKind)
			?.supportedAutomatedReviewModes ?? [];
	const groupNames = new Map(groups.map((group) => [group.slug, group.name]));
	const inheritedFromFor = (practice: Practice) =>
		(practice.groupSlug ? groupNames.get(practice.groupSlug) : null) ?? null;

	return (
		<div className="space-y-4">
			<CatalogToolbar
				focusFilter={focusFilter}
				onFocusFilterChange={onFocusFilterChange}
				onCreateGroup={() => setNamingGroup(null)}
				creatingGroup={pending.creatingGroup}
				groupStructurePending={pending.groupStructure}
				library={library}
			/>
			{library?.open && (
				<Section
					size="sm"
					title="Instance catalog"
					description="Practices this instance includes. Adding one gives you a copy you own — later catalog changes never reach it."
					// Arrives rather than appears: the toggle is above it, so a section that simply exists
					// on the next frame gives no clue where it came from. Short, and off under
					// `prefers-reduced-motion`, where the arrival is the information and the travel is not.
					className="rounded-lg border bg-muted/20 p-4 motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-top-2 motion-safe:duration-200"
				>
					{library.state.status === "error" ? (
						<QueryErrorAlert
							error={library.state.error}
							title="Couldn't load the catalog"
							onRetry={library.state.onRetry}
						/>
					) : visibleCatalogPractices ? (
						<AvailablePracticeList
							practices={visibleCatalogPractices}
							existingGroupSlugs={new Set(groups.map((group) => group.slug))}
						/>
					) : (
						<PracticeListSkeleton rows={4} />
					)}
				</Section>
			)}
			{focusFilter !== "ALL" && (
				<p className="text-muted-foreground text-sm">Clear the filter to reorder practices.</p>
			)}

			<SortableCatalogTree
				groups={groups}
				entries={practices}
				visibleEntrySlugs={visiblePracticeSlugs}
				forceOpenGroupSlugs={forceOpenGroupSlugs}
				groupReorderDisabled={pending.groupStructure}
				disabledGroupSlugs={pending.groupSlugs}
				disabledEntrySlugs={pending.practiceSlugs}
				blockedEntryOrderBuckets={pending.blockedPracticeOrderBuckets}
				blockedMoveDestinationSlugs={pending.blockedMoveDestinationSlugs}
				showEntryReorderHandles={focusFilter === "ALL"}
				onReorderGroups={onReorderGroups}
				onPlaceEntry={onPlacePractice}
				renderGroupLeading={(group) => (
					<GroupVisualPicker
						name={group.name}
						icon={group.icon}
						color={group.color}
						onChange={(patch) => onSetGroupVisual(group.slug, patch)}
						disabled={pending.groupSlugs.has(group.slug)}
					/>
				)}
				renderGroupMeta={(group) => (
					<>
						{!group.visibleInPracticeDashboards && (
							<StatusBadge def={DASHBOARD_VISIBILITY_DEFS.HIDDEN} />
						)}
						<CatalogOriginBadge origin={group.catalogOrigin} kind="group" />
					</>
				)}
				renderGroupActions={(group, move, actionTriggerRef) => (
					<GroupActions
						group={group}
						move={move}
						actionTriggerRef={actionTriggerRef}
						pending={pending.groupSlugs.has(group.slug)}
						structurePending={pending.groupStructure}
						onRename={() => setNamingGroup(group)}
						onSetDashboardVisibility={onSetGroupDashboardVisibility}
						onDelete={onDeleteGroup}
					/>
				)}
				renderEntryContent={(practice) => (
					<PracticeRowDetails
						practice={practice}
						supportedModes={supportedModesFor(practice)}
						inheritedFrom={inheritedFromFor(practice)}
						title={
							<DetailStackLink
								entry={{ kind: "practice", id: practice.slug }}
								className="break-words rounded-sm hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
							>
								{practice.name}
							</DetailStackLink>
						}
					/>
				)}
				renderEntryActions={(practice, move, actionTriggerRef) => (
					<PracticeActions
						practice={practice}
						workspaceSlug={workspaceSlug}
						groups={groups}
						move={move}
						actionTriggerRef={actionTriggerRef}
						pending={pending.practiceSlugs.has(practice.slug)}
						onDelete={onDeletePractice}
					/>
				)}
				renderEntryPreview={(practice) => (
					<PracticeDragPreview
						practice={practice}
						supportedModes={supportedModesFor(practice)}
						inheritedFrom={inheritedFromFor(practice)}
					/>
				)}
				getEmptyLabel={(groupSlug, total) => {
					if (total > 0) return "No matching practices.";
					return groupSlug === null ? "Nothing unassigned." : "No practices here.";
				}}
			/>

			{groups.length === 0 && practices.length === 0 ? (
				<Empty className="min-h-56 border">
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<ListChecks aria-hidden />
						</EmptyMedia>
						<EmptyTitle>No practices yet</EmptyTitle>
						<EmptyDescription>
							Add one from the instance catalog, or write your own.
						</EmptyDescription>
					</EmptyHeader>
					<EmptyContent>
						<Button onClick={() => library?.onOpenChange(true)} disabled={!library}>
							<Library className="mr-1.5 size-4" aria-hidden />
							Show catalog
						</Button>
					</EmptyContent>
				</Empty>
			) : (
				focusFilter !== "ALL" &&
				visiblePracticeSlugs.size === 0 && (
					// Without a way out, the reader is left with per-group "No matching practices." strings
					// and a banner telling them to clear a filter, and no control that clears it.
					<Empty className="min-h-56 border">
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<ListChecks aria-hidden />
							</EmptyMedia>
							<EmptyTitle>No practices match this filter</EmptyTitle>
							<EmptyDescription>
								Nothing in this workspace reviews{" "}
								{artifactKindPluralLabel(focusFilter).toLowerCase()}.
							</EmptyDescription>
						</EmptyHeader>
						<EmptyContent>
							<Button variant="outline" onClick={() => onFocusFilterChange("ALL")}>
								Clear the filter
							</Button>
						</EmptyContent>
					</Empty>
				)
			)}

			<GroupDetailsDialog
				group={namingGroup ?? null}
				open={namingGroup !== undefined}
				pending={namingGroup ? pending.groupSlugs.has(namingGroup.slug) : pending.creatingGroup}
				onOpenChange={(open) => {
					if (!open) setNamingGroup(undefined);
				}}
				onSubmit={(details) =>
					namingGroup ? onUpdateGroup(namingGroup.slug, details) : onCreateGroup(details)
				}
			/>
		</div>
	);
}

function CatalogToolbar({
	focusFilter,
	onFocusFilterChange,
	onCreateGroup,
	creatingGroup,
	groupStructurePending,
	library,
}: {
	focusFilter: FocusFilter;
	onFocusFilterChange: (filter: FocusFilter) => void;
	onCreateGroup: () => void;
	creatingGroup: boolean;
	groupStructurePending: boolean;
	library?: PracticeLibrary;
}) {
	return (
		<div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
			<FilterToggle
				label="Filter by work type"
				options={WORK_TYPE_FILTER_OPTIONS}
				value={focusFilter}
				onChange={onFocusFilterChange}
			/>
			<div className="grid gap-2 sm:flex">
				<Toggle
					variant="outline"
					pressed={library?.open ?? false}
					onPressedChange={(pressed) => library?.onOpenChange(pressed)}
				>
					<Library />
					Show catalog
				</Toggle>
				<Button
					variant="outline"
					onClick={onCreateGroup}
					disabled={groupStructurePending && !creatingGroup}
				>
					<Plus />
					Create group
				</Button>
				<DetailStackLink
					entry={practiceFormLevel()}
					className={cn(buttonVariants(), "w-full sm:w-auto")}
				>
					<Plus />
					Create practice
				</DetailStackLink>
			</div>
		</div>
	);
}

function GroupActions({
	group,
	move,
	actionTriggerRef,
	pending,
	structurePending,
	onRename,
	onSetDashboardVisibility,
	onDelete,
}: {
	group: PracticeGroup;
	move: CatalogMoveActions;
	actionTriggerRef: ActionTriggerRef;
	pending: boolean;
	structurePending: boolean;
	onRename: () => void;
	onSetDashboardVisibility: (slug: string, visibleInPracticeDashboards: boolean) => void;
	onDelete: (slug: string) => void;
}) {
	return (
		<>
			<Switch
				className="hidden sm:inline-flex"
				checked={group.visibleInPracticeDashboards}
				onCheckedChange={(visibleInPracticeDashboards) =>
					onSetDashboardVisibility(group.slug, visibleInPracticeDashboards)
				}
				disabled={pending}
				aria-label={`Show ${group.name} on practice dashboards`}
			/>
			<DropdownMenu>
				<DropdownMenuTrigger
					render={
						<Button
							ref={actionTriggerRef}
							variant="ghost"
							size="icon-sm"
							aria-label={`More actions for ${group.name}`}
							disabled={pending}
						>
							<MoreHorizontal className="size-4" />
						</Button>
					}
				/>
				<DropdownMenuContent align="end">
					<DropdownMenuItem disabled={pending} onClick={onRename}>
						Rename
					</DropdownMenuItem>
					<DropdownMenuItem
						disabled={pending}
						onClick={() => onSetDashboardVisibility(group.slug, !group.visibleInPracticeDashboards)}
					>
						{group.visibleInPracticeDashboards
							? "Hide from practice dashboards"
							: "Show on practice dashboards"}
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
					<DropdownMenuItem
						variant="destructive"
						disabled={pending || structurePending}
						onClick={() => onDelete(group.slug)}
					>
						Delete group
					</DropdownMenuItem>
				</DropdownMenuContent>
			</DropdownMenu>
		</>
	);
}

function PracticeActions({
	practice,
	workspaceSlug,
	groups,
	move,
	actionTriggerRef,
	pending,
	onDelete,
}: {
	practice: Practice;
	workspaceSlug: string;
	groups: PracticeGroup[];
	move: CatalogEntryMoveActions;
	actionTriggerRef: ActionTriggerRef;
	pending: boolean;
	onDelete: (practice: Practice) => void;
}) {
	return (
		<DropdownMenu>
			<DropdownMenuTrigger
				render={
					<Button
						ref={actionTriggerRef}
						variant="ghost"
						size="icon-sm"
						aria-label={`More actions for ${practice.name}`}
					>
						<MoreHorizontal className="size-4" />
					</Button>
				}
			/>
			<DropdownMenuContent align="end">
				<DropdownMenuItem render={<DetailStackLink entry={practiceFormLevel(practice.slug)} />}>
					Edit practice
				</DropdownMenuItem>
				<DropdownMenuSeparator />
				<DropdownMenuItem
					render={
						<Link
							to="/w/$workspaceSlug/admin/practices/review"
							params={{ workspaceSlug }}
							search={{}}
						/>
					}
				>
					Change on Review
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
						value={practice.groupSlug ?? UNASSIGNED_CATALOG_BUCKET}
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
						{groups.map((group) => (
							<DropdownMenuRadioItem
								key={group.slug}
								value={group.slug}
								disabled={move.currentGroupSlug !== group.slug && !move.canMoveTo(group.slug)}
								closeOnClick
							>
								{group.name}
							</DropdownMenuRadioItem>
						))}
					</DropdownMenuRadioGroup>
				</DropdownMenuGroup>
				<DropdownMenuSeparator />
				<DropdownMenuItem
					variant="destructive"
					disabled={pending}
					onClick={() => onDelete(practice)}
				>
					Delete practice
				</DropdownMenuItem>
			</DropdownMenuContent>
		</DropdownMenu>
	);
}

function PracticeRowDetails({
	practice,
	title,
	supportedModes,
	inheritedFrom,
}: {
	practice: Practice;
	title: ReactNode;
	supportedModes: readonly Practice["automatedReviewPolicy"]["automatedReview"]["mode"][];
	inheritedFrom: string | null;
}) {
	const unavailableLabel = automatedReviewUnavailableLabel(
		practice.automatedReviewPolicy,
		supportedModes,
	);
	const autonomySource = autonomySourceOf(practice.autonomy, inheritedFrom);
	return (
		<ItemContent className="min-w-0">
			<ItemTitle className="w-full min-w-0 line-clamp-none">{title}</ItemTitle>
			<ItemDescription>
				<MetaRow
					captions={[
						<WorkTypeLabel key="work" artifactKind={practice.artifactKind} />,
						<AutonomySourceNote key="source" source={autonomySource} />,
					]}
					badges={
						<>
							<AutonomyBadge autonomy={practice.autonomy.effective} />
							{unavailableLabel && <Badge variant="warning">{unavailableLabel}</Badge>}
							<CatalogOriginBadge origin={practice.catalogOrigin} kind="practice" />
						</>
					}
				/>
			</ItemDescription>
		</ItemContent>
	);
}

function PracticeDragPreview({
	practice,
	supportedModes,
	inheritedFrom,
}: {
	practice: Practice;
	supportedModes: readonly Practice["automatedReviewPolicy"]["automatedReview"]["mode"][];
	inheritedFrom: string | null;
}) {
	return (
		<Item
			aria-hidden="true"
			variant="outline"
			size="xs"
			className="flex-nowrap bg-popover text-popover-foreground shadow-lg ring-1 ring-foreground/10"
		>
			<div className="flex size-8 shrink-0 items-center justify-center text-muted-foreground">
				<GripVertical className="size-4" />
			</div>
			<PracticeRowDetails
				practice={practice}
				supportedModes={supportedModes}
				inheritedFrom={inheritedFrom}
				title={<span className="break-words">{practice.name}</span>}
			/>
		</Item>
	);
}
