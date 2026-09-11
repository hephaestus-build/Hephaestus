import { Plus, RotateCcw, Search, Shapes } from "lucide-react";
import { useState } from "react";

import { cn } from "cn";
import type {
	CuratedGroup,
	CuratedPracticeSummary,
	CuratedCatalogSummary as Summary,
} from "@/api/types.gen";
import {
	WORK_ARTIFACT_FILTER_ITEMS,
	type WorkArtifact,
} from "@/components/admin/practice-catalog/constants";
import { DetailStackLink } from "@/components/core/detail-drawer/DetailStackLink";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button, buttonVariants } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Input } from "@/components/ui/input";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { isKnownArtifactKind } from "@/lib/artifact-kinds";

import { type CuratedCatalogSearch, curatedPracticeLevel } from "./curated-catalog-search";
import { CuratedCatalogSummary } from "./CuratedCatalogSummary";
import { CuratedCatalogTree } from "./CuratedCatalogTree";

export interface CuratedCatalogProps {
	groups: readonly CuratedGroup[];
	practices: readonly CuratedPracticeSummary[];
	summary: Summary;
	search: CuratedCatalogSearch;
	customOrder: boolean;
	writePending?: boolean;
	pendingPracticeSlugs?: ReadonlySet<string>;
	pendingGroupSlugs?: ReadonlySet<string>;
	onSearchChange: (search: CuratedCatalogSearch) => void;
	onPracticeStatusChange: (practice: CuratedPracticeSummary, offered: boolean) => void;
	onGroupStatusChange: (group: CuratedGroup, offered: boolean) => void;
	onReorderGroups: (orderedSlugs: string[]) => void;
	onPlacePractice: (practiceSlug: string, groupSlug: string | null, position: number) => void;
	onResetOrder: () => void;
}

type StatusFilter = "OFFERED" | "NOT_OFFERED" | "ALL";
type ArtifactFilter = WorkArtifact;

const STATUS_FILTERS = [
	{ value: "ALL", label: "All" },
	{ value: "OFFERED", label: "Included" },
	{ value: "NOT_OFFERED", label: "Excluded" },
] satisfies Array<{ value: StatusFilter; label: string }>;

function matches(haystack: (string | undefined)[], needle: string): boolean {
	return !needle || haystack.some((value) => value?.toLowerCase().includes(needle));
}

export function CuratedCatalog({
	groups,
	practices,
	summary,
	search,
	customOrder,
	writePending = false,
	pendingPracticeSlugs = new Set(),
	pendingGroupSlugs = new Set(),
	onSearchChange,
	onPracticeStatusChange,
	onGroupStatusChange,
	onReorderGroups,
	onPlacePractice,
	onResetOrder,
}: CuratedCatalogProps) {
	const [excludingPractice, setExcludingPractice] = useState<CuratedPracticeSummary | null>(null);
	const [excludingGroup, setExcludingGroup] = useState<CuratedGroup | null>(null);
	const [resettingOrder, setResettingOrder] = useState(false);
	const query = search.q ?? "";
	const status: StatusFilter = search.status ?? "ALL";
	const artifact: ArtifactFilter = search.artifact ?? "ALL";
	const reviewOnly = search.review === true;
	const removedDefaultsToReview =
		groups.filter((group) => group.status.state === "NO_LONGER_SHIPPED" && group.status.offered)
			.length +
		practices.filter(
			(practice) => practice.status.state === "NO_LONGER_SHIPPED" && practice.effectivelyOffered,
		).length;
	const needle = query.trim().toLowerCase();
	const groupBySlug = new Map(groups.map((group) => [group.slug, group]));
	const matchesStatus = (offered: boolean) =>
		status === "ALL" || (status === "OFFERED") === offered;

	const visiblePractices = practices.filter(
		(practice) =>
			(!reviewOnly ||
				practice.status.state === "UPDATE_WAITING" ||
				(practice.status.state === "NO_LONGER_SHIPPED" && practice.effectivelyOffered)) &&
			matchesStatus(practice.effectivelyOffered) &&
			(artifact === "ALL" || practice.artifactKind === artifact) &&
			matches(
				[
					practice.name,
					practice.slug,
					practice.groupSlug ?? undefined,
					practice.groupSlug ? groupBySlug.get(practice.groupSlug)?.definition.name : undefined,
				],
				needle,
			),
	);
	const visiblePracticeSlugs = new Set(visiblePractices.map((practice) => practice.slug));
	const groupsHoldingMatches = new Set(
		visiblePractices.map((practice) => practice.groupSlug).filter((slug): slug is string => !!slug),
	);
	const visibleGroups = groups.filter(
		(group) =>
			groupsHoldingMatches.has(group.slug) ||
			(artifact === "ALL" &&
				(!reviewOnly ||
					group.status.state === "UPDATE_WAITING" ||
					(group.status.state === "NO_LONGER_SHIPPED" && group.status.offered)) &&
				matchesStatus(group.status.offered) &&
				matches(
					[group.definition.name, group.slug, group.definition.description ?? undefined],
					needle,
				)),
	);

	const visibleGroupSlugs = new Set(visibleGroups.map((group) => group.slug));
	const canReorder = !needle && status === "ALL" && artifact === "ALL" && !reviewOnly;
	const forcedOpenGroups = canReorder ? undefined : visibleGroupSlugs;
	const catalogIsEmpty = groups.length === 0 && practices.length === 0;
	const nothingMatches = visibleGroups.length === 0 && visiblePractices.length === 0;
	const practicesExcludedWithGroup = excludingGroup
		? practices.filter(
				(practice) => practice.groupSlug === excludingGroup.slug && practice.status.offered,
			)
		: [];

	return (
		<>
			<div className="space-y-4">
				<CuratedCatalogSummary
					summary={summary}
					removedDefaultsToReview={removedDefaultsToReview}
					reviewing={reviewOnly}
					onReviewChanges={() => onSearchChange({ review: true })}
				/>
				<CatalogFilters search={search} onSearchChange={onSearchChange} />
				{customOrder && (
					<div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-3 text-sm">
						<p className="text-muted-foreground">
							This catalog keeps your custom order when the Hephaestus defaults change.
						</p>
						<Button
							variant="outline"
							size="sm"
							disabled={writePending}
							onClick={() => setResettingOrder(true)}
						>
							<RotateCcw className="mr-1.5 size-4" aria-hidden />
							Use Hephaestus order
						</Button>
					</div>
				)}
				{!canReorder && !catalogIsEmpty && (
					<p className="text-muted-foreground text-sm">
						Clear the search and filters to reorder the catalog.
					</p>
				)}

				{catalogIsEmpty ? (
					<Empty className="min-h-56 border">
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<Shapes aria-hidden />
							</EmptyMedia>
							<EmptyTitle>The catalog is empty</EmptyTitle>
							<EmptyDescription>
								Create a group or practice, then include it for workspace administrators.
							</EmptyDescription>
						</EmptyHeader>
						<EmptyContent>
							<DetailStackLink entry={curatedPracticeLevel()} className={cn(buttonVariants())}>
								<Plus className="mr-1.5 size-4" aria-hidden />
								Create practice
							</DetailStackLink>
						</EmptyContent>
					</Empty>
				) : nothingMatches ? (
					<Empty className="min-h-56 border">
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<Shapes aria-hidden />
							</EmptyMedia>
							<EmptyTitle>Nothing matches</EmptyTitle>
							<EmptyDescription>
								Adjust the search or filters to see more of the catalog.
							</EmptyDescription>
						</EmptyHeader>
						<EmptyContent>
							<Button variant="outline" onClick={() => onSearchChange({})}>
								Clear search and filters
							</Button>
						</EmptyContent>
					</Empty>
				) : (
					<CuratedCatalogTree
						groups={groups}
						practices={practices}
						visibleGroupSlugs={visibleGroupSlugs}
						visiblePracticeSlugs={visiblePracticeSlugs}
						forceOpenGroupSlugs={forcedOpenGroups}
						canReorder={canReorder}
						writePending={writePending}
						pendingGroupSlugs={pendingGroupSlugs}
						pendingPracticeSlugs={pendingPracticeSlugs}
						onGroupStatusChange={onGroupStatusChange}
						onPracticeStatusChange={onPracticeStatusChange}
						onExcludeGroup={setExcludingGroup}
						onExcludePractice={setExcludingPractice}
						onReorderGroups={onReorderGroups}
						onPlacePractice={onPlacePractice}
					/>
				)}
			</div>

			<AlertDialog open={resettingOrder} onOpenChange={setResettingOrder}>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Use the Hephaestus order?</AlertDialogTitle>
						<AlertDialogDescription>
							Groups and practices will return to the order included with this Hephaestus version.
							Definitions and inclusion will not change.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							onClick={() => {
								onResetOrder();
								setResettingOrder(false);
							}}
						>
							Use Hephaestus order
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>

			<AlertDialog
				open={excludingPractice !== null}
				onOpenChange={(open) => {
					if (!open) setExcludingPractice(null);
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Exclude “{excludingPractice?.name}”?</AlertDialogTitle>
						<AlertDialogDescription>
							{excludingPractice?.effectivelyOffered === false
								? "Its group is already not offered. This keeps the practice unavailable if the group is offered again. Existing workspace copies will not change."
								: "Workspace administrators will no longer be able to add this practice. Existing workspace copies will not change. You can include it again later."}
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							onClick={() => {
								if (excludingPractice) onPracticeStatusChange(excludingPractice, false);
								setExcludingPractice(null);
							}}
						>
							Stop offering
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>

			<AlertDialog
				open={excludingGroup !== null}
				onOpenChange={(open) => {
					if (!open) setExcludingGroup(null);
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Exclude “{excludingGroup?.definition.name}”?</AlertDialogTitle>
						<AlertDialogDescription>
							{practicesExcludedWithGroup.length === 0
								? "Workspace administrators will no longer be able to add this group. No additional practices are affected. Existing workspace copies will not change."
								: `Workspace administrators will no longer be able to add this group. This also stops offering ${practicesExcludedWithGroup.length} currently offered ${
										practicesExcludedWithGroup.length === 1 ? "practice" : "practices"
									}. Existing workspace copies will not change.`}
						</AlertDialogDescription>
					</AlertDialogHeader>
					{practicesExcludedWithGroup.length > 0 && (
						<ul
							aria-label="Practices this also excludes"
							className="max-h-40 list-disc overflow-y-auto pl-5 text-muted-foreground text-sm"
						>
							{practicesExcludedWithGroup.map((practice) => (
								<li key={practice.slug}>{practice.name}</li>
							))}
						</ul>
					)}
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							onClick={() => {
								if (excludingGroup) onGroupStatusChange(excludingGroup, false);
								setExcludingGroup(null);
							}}
						>
							Stop offering
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</>
	);
}

function CatalogFilters({
	search,
	onSearchChange,
}: {
	search: CuratedCatalogSearch;
	onSearchChange: (search: CuratedCatalogSearch) => void;
}) {
	const status: StatusFilter = search.status ?? "ALL";
	const artifact: ArtifactFilter = search.artifact ?? "ALL";
	return (
		<div className="grid gap-2 sm:grid-cols-2 lg:flex lg:items-center">
			{search.review && (
				<Button
					variant="secondary"
					size="sm"
					onClick={() => onSearchChange({ ...search, review: undefined })}
				>
					Show all entries
				</Button>
			)}
			<div className="relative sm:col-span-2 lg:w-64">
				<Search
					className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
					aria-hidden
				/>
				<Input
					type="search"
					value={search.q ?? ""}
					onChange={(event) => onSearchChange({ ...search, q: event.target.value || undefined })}
					placeholder="Search the catalog"
					aria-label="Search the catalog"
					className="pl-9"
				/>
			</div>
			<Select
				items={STATUS_FILTERS}
				value={status}
				onValueChange={(value) =>
					value &&
					onSearchChange({
						...search,
						status: value === "ALL" ? undefined : value,
					})
				}
			>
				<SelectTrigger
					className="w-full lg:hidden"
					aria-label="Filter by availability to workspaces"
				>
					<SelectValue />
				</SelectTrigger>
				<SelectContent aria-label="Filter by inclusion in new workspaces">
					{STATUS_FILTERS.map((filter) => (
						<SelectItem key={filter.value} value={filter.value}>
							{filter.label}
						</SelectItem>
					))}
				</SelectContent>
			</Select>
			{/* `toolbar`, not the default `group`: Base UI gives the group a roving tabindex, and
			    `toolbar` is the role that contract belongs to. `radiogroup` would be worse — the items
			    are `aria-pressed`, not radios. */}
			<ToggleGroup
				role="toolbar"
				value={[status]}
				onValueChange={(value) =>
					value[0] &&
					onSearchChange({
						...search,
						status: value[0] === "ALL" ? undefined : value[0],
					})
				}
				variant="outline"
				size="sm"
				aria-label="Filter by availability to workspaces"
				className="hidden lg:flex"
			>
				{STATUS_FILTERS.map((filter) => (
					<ToggleGroupItem key={filter.value} value={filter.value} className="min-w-0">
						{filter.label}
					</ToggleGroupItem>
				))}
			</ToggleGroup>
			<Select
				items={WORK_ARTIFACT_FILTER_ITEMS}
				value={artifact}
				onValueChange={(value) =>
					onSearchChange({
						...search,
						artifact: value === "ALL" || !isKnownArtifactKind(value) ? undefined : value,
					})
				}
			>
				<SelectTrigger className="w-full lg:w-52" aria-label="Filter by work type">
					<SelectValue />
				</SelectTrigger>
				<SelectContent aria-label="Filter by work type">
					{WORK_ARTIFACT_FILTER_ITEMS.map(({ value, label }) => (
						<SelectItem key={value} value={value}>
							{label}
						</SelectItem>
					))}
				</SelectContent>
			</Select>
		</div>
	);
}
