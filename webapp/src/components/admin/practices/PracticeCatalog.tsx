import { Link } from "@tanstack/react-router";
import { GripVertical, Library, ListChecks, MoreHorizontal, Plus } from "lucide-react";
import { type ReactNode, useState } from "react";

import { cn } from "cn";
import type { Practice, PracticeGroup } from "@/api/types.gen";
import { ITEM_KIND_TOGGLE_OPTIONS } from "@/components/admin/practice-editor/constants";
import {
	type GroupDetails,
	GroupDetailsDialog,
} from "@/components/admin/practice-editor/GroupDetailsDialog";
import { GroupVisualPicker } from "@/components/admin/practice-editor/GroupVisualPicker";
import {
	type ActionTriggerRef,
	type CatalogEntryMoveActions,
	type CatalogMoveActions,
	SortableCatalogTree,
	UNASSIGNED_CATALOG_BUCKET,
} from "@/components/admin/practice-editor/SortableCatalogTree";
import { AvailablePracticeList } from "@/components/admin/practices/AvailablePracticeList";
import type { AdoptablePractice } from "@/components/admin/practices/catalog-adoption";
import {
	currentDefinition,
	REVIEWED_BY_LABELS,
} from "@/components/admin/practices/practice-definition-fields";
import { practiceFormLevel } from "@/components/admin/practices/practice-search";
import { PracticeListSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { FilterToggle } from "@/components/common/FilterToggle";
import { MetaRow } from "@/components/common/MetaRow";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Section } from "@/components/layout/Section";
import { FeedbackModeBadge } from "@/components/practice-vocabulary/FeedbackModeBadge";
import { FeedbackModeSourceNote } from "@/components/practice-vocabulary/FeedbackModeSourceNote";
import { PROFILE_VISIBILITY_DEFS } from "@/components/practice-vocabulary/profile-visibility-defs";
import { WorkTypeLabel } from "@/components/practice-vocabulary/WorkTypeLabel";
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
import { itemKindNoun, type KnownItemKind } from "@/lib/item-kinds";
import { feedbackModeSourceOf } from "@/lib/practice-feedback-mode";
import { hasText } from "@/lib/text";

import { CatalogOriginBadge, canResetToCatalog } from "./CatalogOriginBadge";

export type FocusFilter = "ALL" | KnownItemKind;

interface PracticeRow {
	slug: string;
	groupSlug: string | null;
	position: number;
	name: string;
	practice: Practice;
}

export interface PracticeCatalogPendingState {
	groupSlugs: ReadonlySet<string>;
	practiceSlugs: ReadonlySet<string>;
	groupStructure: boolean;
	blockedMoveDestinationSlugs: ReadonlySet<string>;
	blockedPracticeOrderBuckets: ReadonlySet<string>;
	creatingGroup: boolean;
}

export type LibraryState = PanelState<{ practices: AdoptablePractice[] }>;

export interface PracticeLibrary {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	state: LibraryState;
}

export interface PracticeCatalogProps {
	workspaceSlug: string;
	groups: PracticeGroup[];
	practices: Practice[];
	pending: PracticeCatalogPendingState;
	focusFilter: FocusFilter;
	onFocusFilterChange: (f: FocusFilter) => void;
	onCreateGroup: (details: GroupDetails) => Promise<boolean>;
	onUpdateGroup: (slug: string, details: GroupDetails) => Promise<boolean>;
	onSetGroupShownToDevelopers: (slug: string, shownToDevelopers: boolean) => void;
	onDeleteGroup: (slug: string) => void;
	onResetGroup: (slug: string) => void;
	onReorderGroups: (orderedSlugs: string[]) => void;
	onSetGroupVisual: (slug: string, patch: { icon?: string; color?: string }) => void;
	onDeletePractice: (practice: Practice) => void;
	onMovePractice: (practiceSlug: string, groupSlug: string | null, position: number) => void;
	library?: PracticeLibrary;
}

export function PracticeCatalog({
	workspaceSlug,
	groups,
	practices,
	pending,
	focusFilter,
	onFocusFilterChange,
	onCreateGroup,
	onUpdateGroup,
	onSetGroupShownToDevelopers,
	onDeleteGroup,
	onResetGroup,
	onReorderGroups,
	onSetGroupVisual,
	onDeletePractice,
	onMovePractice,
	library,
}: PracticeCatalogProps) {
	const [namingGroup, setNamingGroup] = useState<PracticeGroup | null | undefined>(undefined);
	const rows: PracticeRow[] = practices.map((practice) => ({
		slug: practice.slug,
		groupSlug: practice.groupSlug,
		position: practice.position,
		name: currentDefinition(practice).name,
		practice,
	}));
	const visiblePracticeSlugs = new Set(
		practices
			.filter((practice) => focusFilter === "ALL" || practice.kind === focusFilter)
			.map((practice) => practice.slug),
	);
	const visibleCatalogPractices =
		library?.state.status === "ready"
			? library.state.practices.filter(
					(practice) => focusFilter === "ALL" || practice.kind === focusFilter,
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
	const groupNames = new Map(groups.map((group) => [group.slug, group.name]));
	const inheritedFromFor = (practice: Practice) =>
		(hasText(practice.groupSlug) ? groupNames.get(practice.groupSlug) : null) ?? null;

	let catalogContent: ReactNode;
	if (library?.state.status === "error") {
		catalogContent = (
			<QueryErrorAlert
				error={library.state.error}
				title="We could not load the catalog"
				onRetry={library.state.onRetry}
			/>
		);
	} else if (visibleCatalogPractices) {
		catalogContent = (
			<AvailablePracticeList
				practices={visibleCatalogPractices}
				existingGroupSlugs={new Set(groups.map((group) => group.slug))}
			/>
		);
	} else {
		catalogContent = <PracticeListSkeleton rows={4} />;
	}

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
			{library?.open === true && (
				<Section
					size="sm"
					title="Instance catalog"
					description="Practices this instance includes. Adding one gives you a copy you own. Later catalog changes do not change your copy unless you accept them."
					// Arrives rather than appears: the toggle is above it, so a section that simply exists
					// on the next frame gives no clue where it came from. Short, and off under
					// `prefers-reduced-motion`, where the arrival is the information and the travel is not.
					className="rounded-lg border bg-muted/20 p-4 motion-safe:animate-in motion-safe:duration-200 motion-safe:fade-in motion-safe:slide-in-from-top-2"
				>
					{catalogContent}
				</Section>
			)}
			{focusFilter !== "ALL" && (
				<p className="text-sm text-muted-foreground">Clear the filter to reorder practices.</p>
			)}

			<SortableCatalogTree
				groups={groups}
				entries={rows}
				visibleEntrySlugs={visiblePracticeSlugs}
				forceOpenGroupSlugs={forceOpenGroupSlugs}
				groupReorderDisabled={pending.groupStructure}
				disabledGroupSlugs={pending.groupSlugs}
				disabledEntrySlugs={pending.practiceSlugs}
				blockedEntryOrderBuckets={pending.blockedPracticeOrderBuckets}
				blockedMoveDestinationSlugs={pending.blockedMoveDestinationSlugs}
				showEntryReorderHandles={focusFilter === "ALL"}
				onReorderGroups={onReorderGroups}
				onPlaceEntry={onMovePractice}
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
						{!group.shownToDevelopers && <StatusBadge def={PROFILE_VISIBILITY_DEFS.HIDDEN} />}
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
						onSetShownToDevelopers={onSetGroupShownToDevelopers}
						onDelete={onDeleteGroup}
						onReset={onResetGroup}
					/>
				)}
				renderEntryContent={(row) => (
					<PracticeRowDetails
						practice={row.practice}
						inheritedFrom={inheritedFromFor(row.practice)}
						title={
							<DetailStackLink
								entry={{ kind: "practice", id: row.slug }}
								className="rounded-sm break-words hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-hidden"
							>
								{row.name}
							</DetailStackLink>
						}
					/>
				)}
				renderEntryActions={(row, move, actionTriggerRef) => (
					<PracticeActions
						practice={row.practice}
						workspaceSlug={workspaceSlug}
						groups={groups}
						move={move}
						actionTriggerRef={actionTriggerRef}
						pending={pending.practiceSlugs.has(row.slug)}
						onDelete={onDeletePractice}
					/>
				)}
				renderEntryPreview={(row) => (
					<PracticeDragPreview
						practice={row.practice}
						inheritedFrom={inheritedFromFor(row.practice)}
					/>
				)}
				getEmptyLabel={(groupSlug, total) => {
					if (total > 0) {
						return "No matching practices";
					}
					return groupSlug === null ? "Nothing unassigned" : "No practices here";
				}}
			/>

			{groups.length === 0 && practices.length === 0 ? (
				<Empty variant="outlined" className="min-h-56">
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
					// Without a way out, the reader is left with per-group "No matching practices" strings
					// and a banner telling them to clear a filter, and no control that clears it.
					<Empty variant="outlined" className="min-h-56">
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<ListChecks aria-hidden />
							</EmptyMedia>
							<EmptyTitle>No practices match this filter</EmptyTitle>
							<EmptyDescription>
								Nothing in this workspace reviews {itemKindNoun(focusFilter, 2)}.
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
					if (!open) {
						setNamingGroup(undefined);
					}
				}}
				onSubmit={async (details) =>
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
		<div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
			<FilterToggle
				className="sm:shrink-0"
				label="Filter by kind of work"
				options={ITEM_KIND_TOGGLE_OPTIONS}
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
	onSetShownToDevelopers,
	onDelete,
	onReset,
}: {
	group: PracticeGroup;
	move: CatalogMoveActions;
	actionTriggerRef: ActionTriggerRef;
	pending: boolean;
	structurePending: boolean;
	onRename: () => void;
	onSetShownToDevelopers: (slug: string, shownToDevelopers: boolean) => void;
	onDelete: (slug: string) => void;
	onReset: (slug: string) => void;
}) {
	return (
		<>
			<Switch
				className="hidden sm:inline-flex"
				checked={group.shownToDevelopers}
				onCheckedChange={(shownToDevelopers) =>
					onSetShownToDevelopers(group.slug, shownToDevelopers)
				}
				disabled={pending}
				aria-label={`Show ${group.name} on practice profiles`}
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
						onClick={() => onSetShownToDevelopers(group.slug, !group.shownToDevelopers)}
					>
						{group.shownToDevelopers ? "Hide from practice profiles" : "Show on practice profiles"}
					</DropdownMenuItem>
					{canResetToCatalog(group.catalogOrigin) && (
						<DropdownMenuItem disabled={pending} onClick={() => onReset(group.slug)}>
							Reset to catalog
						</DropdownMenuItem>
					)}
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
						aria-label={`More actions for ${currentDefinition(practice).name}`}
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
					Change in review settings
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
	inheritedFrom,
}: {
	practice: Practice;
	title: ReactNode;
	inheritedFrom: string | null;
}) {
	const { by } = currentDefinition(practice).reviewedBy;
	return (
		<ItemContent className="min-w-0">
			<ItemTitle className="line-clamp-none w-full min-w-0">{title}</ItemTitle>
			<ItemDescription>
				<MetaRow
					captions={[
						<WorkTypeLabel key="work" itemKind={practice.kind} />,
						<FeedbackModeSourceNote
							key="source"
							source={feedbackModeSourceOf(practice.feedbackMode, "PRACTICE", inheritedFrom)}
						/>,
						...(by === "HEPHAESTUS"
							? []
							: [
									<span key="reviewed-by">Reviewed by {REVIEWED_BY_LABELS[by].toLowerCase()}</span>,
								]),
					]}
					badges={
						<>
							<FeedbackModeBadge feedbackMode={practice.feedbackMode.effective} />
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
	inheritedFrom,
}: {
	practice: Practice;
	inheritedFrom: string | null;
}) {
	return (
		<Item aria-hidden="true" variant="overlay" size="xs" className="flex-nowrap">
			<div className="flex size-8 shrink-0 items-center justify-center text-muted-foreground">
				<GripVertical className="size-4" />
			</div>
			<PracticeRowDetails
				practice={practice}
				inheritedFrom={inheritedFrom}
				title={<span className="break-words">{currentDefinition(practice).name}</span>}
			/>
		</Item>
	);
}
