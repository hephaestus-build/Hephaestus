import { Link } from "@tanstack/react-router";
import { cn } from "cn";
import { useState } from "react";
import type {
	FeedbackModeRollup,
	Practice,
	PracticeGroup,
	PracticeReviewSettings,
} from "@/api/types.gen";
import { PracticeDetailHoverCard } from "@/components/admin/practice-editor/PracticeDetailHoverCard";
import {
	FeedbackSendingSettings,
	type FeedbackSendingUpdate,
} from "@/components/admin/practices/FeedbackSendingSettings";
import { currentDefinition } from "@/components/admin/practices/practice-definition-fields";
import { practiceSetupLevel } from "@/components/admin/practices/practice-search";
import { PracticeTreeSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import { FEEDBACK_MODE_DEFS } from "@/components/practice-vocabulary/feedback-mode-defs";
import { WorkTypeLabel } from "@/components/practice-vocabulary/WorkTypeLabel";
import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";
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
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ButtonGroup } from "@/components/ui/button-group";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuGroup,
	DropdownMenuItem,
	DropdownMenuLabel,
	DropdownMenuSeparator,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { Field, FieldTitle } from "@/components/ui/field";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import {
	FEEDBACK_MODE_DESCRIPTIONS,
	FEEDBACK_MODE_LABELS,
	FEEDBACK_MODE_ORDER,
	type FeedbackMode,
	type FeedbackModeLevel,
	feedbackModeCounts,
	feedbackModeDistributionSentence,
	feedbackModeSourceOf,
	feedbackModeSourceSentence,
	WORKSPACE_DEFAULT_SOURCE,
} from "@/lib/practice-feedback-mode";
import { FeedbackModeLadder } from "./FeedbackModeLadder";
import {
	countOverrides,
	type FeedbackModeGroup,
	groupPracticesByGroup,
	isOverridden,
	practiceName,
	reviewedByHephaestus,
} from "./practice-feedback-mode-model";

// Wide enough for "Approve first" whole in a third of it.
const DECISION_COLUMN = "sm:w-96";

const GROUP_GRID = "sm:grid-cols-[minmax(0,1fr)_24rem]";

const ROW_GRID = "grid-cols-[auto_minmax(0,1fr)] sm:grid-cols-[auto_minmax(0,1fr)_24rem]";

type AutomaticPromotion =
	| { scope: "workspace" }
	| { scope: "group"; slug: string; name: string }
	| { scope: "practice"; slug: string; name: string }
	| { scope: "bulk"; slugs: string[] };

export interface PracticeFeedbackModePendingState {
	workspace: boolean;
	groupSlugs: ReadonlySet<string>;
	practiceSlugs: ReadonlySet<string>;
	bulk: { done: number; total: number } | null;
}

/**
 * The practice list loads apart from the workspace settings above it, so a failed or slow list
 * never takes the workspace default or pause and resume with it.
 */
export type FeedbackModeTreeState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			rollup: FeedbackModeRollup;
			groups: PracticeGroup[];
			practices: Practice[];
	  };

export interface PracticeFeedbackModePageProps {
	workspaceSlug: string;
	settings: PracticeReviewSettings;
	tree: FeedbackModeTreeState;
	pending: PracticeFeedbackModePendingState;
	overridesOnly: boolean;
	onOverridesOnlyChange: (next: boolean) => void;
	onSetWorkspaceDefault: (feedbackMode: FeedbackMode) => void;
	onClearWorkspaceDefault: () => void;
	onSetSending: (update: FeedbackSendingUpdate) => void;
	onClearDeliverToMerged: () => void;
	onSetGroupFeedbackMode: (groupSlug: string, feedbackMode: FeedbackMode) => void;
	onClearGroupFeedbackMode: (groupSlug: string) => void;
	onSetPracticeFeedbackMode: (practiceSlug: string, feedbackMode: FeedbackMode) => void;
	onClearPracticeFeedbackMode: (practiceSlug: string) => void;
	/** `null` makes every selected practice inherit again. */
	onBulkSetFeedbackMode: (practiceSlugs: string[], feedbackMode: FeedbackMode | null) => void;
}

export function PracticeFeedbackModePage({
	workspaceSlug,
	settings,
	tree,
	pending,
	overridesOnly,
	onOverridesOnlyChange,
	onSetWorkspaceDefault,
	onClearWorkspaceDefault,
	onSetSending,
	onClearDeliverToMerged,
	onSetGroupFeedbackMode,
	onClearGroupFeedbackMode,
	onSetPracticeFeedbackMode,
	onClearPracticeFeedbackMode,
	onBulkSetFeedbackMode,
}: PracticeFeedbackModePageProps) {
	const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
	const [openGroups, setOpenGroups] = useState<string[]>([]);
	const [automaticPromotion, setAutomaticPromotion] = useState<AutomaticPromotion | null>(null);

	const buckets =
		tree.status === "ready"
			? groupPracticesByGroup(tree.groups, tree.practices, { overridesOnly })
			: [];
	const openValue = overridesOnly ? buckets.map((bucket) => bucket.key) : openGroups;

	const shownSlugs = new Set(
		buckets.flatMap((bucket) => bucket.practices.map((practice) => practice.slug)),
	);
	const actionable = [...selected].filter((slug) => shownSlugs.has(slug));

	const select = (slugs: readonly string[], checked: boolean) => {
		setSelected((current) => {
			const next = new Set(current);
			for (const slug of slugs) {
				if (checked) {
					next.add(slug);
				} else {
					next.delete(slug);
				}
			}
			return next;
		});
	};

	return (
		<div className="space-y-6">
			<AlertDialog
				open={automaticPromotion !== null}
				onOpenChange={(open) => {
					if (!open) {
						setAutomaticPromotion(null);
					}
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Send feedback automatically?</AlertDialogTitle>
						<AlertDialogDescription>
							Set {automaticPromotionLabel(automaticPromotion)} to Automatic. New feedback can then
							be sent without approval, as long as delivery policy allows it. Feedback already
							waiting for approval does not change.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							onClick={() => {
								if (automaticPromotion !== null) {
									applyAutomaticPromotion(automaticPromotion, {
										onSetWorkspaceDefault,
										onSetGroupFeedbackMode,
										onSetPracticeFeedbackMode,
										onBulkSetFeedbackMode,
									});
								}
								setAutomaticPromotion(null);
							}}
						>
							Send automatically
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
			<WorkspaceDecisionCard
				settings={settings}
				saving={pending.workspace}
				onSetWorkspaceDefault={(feedbackMode) => {
					if (feedbackMode === "AUTOMATIC") {
						setAutomaticPromotion({ scope: "workspace" });
						return;
					}
					onSetWorkspaceDefault(feedbackMode);
				}}
				onClearWorkspaceDefault={onClearWorkspaceDefault}
			/>
			<FeedbackSendingSettings
				settings={settings}
				saving={pending.workspace}
				onSet={onSetSending}
				onClearDeliverToMerged={onClearDeliverToMerged}
			/>
			{tree.status === "loading" && <PracticeTreeSkeleton groups={3} practicesPerGroup={3} />}
			{tree.status === "error" && (
				<QueryErrorAlert
					error={tree.error}
					title="We could not load the practices"
					onRetry={tree.onRetry}
				/>
			)}
			{tree.status === "ready" && (
				<>
					<div className="sticky top-0 z-20 space-y-3 border-b bg-background/95 py-3 backdrop-blur supports-backdrop-filter:bg-background/80">
						<div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
							<FeedbackModeSummary
								counts={feedbackModeCounts(tree.rollup)}
								overrides={countOverrides(tree.groups, tree.practices)}
							/>
							<ScopeFilter value={overridesOnly} onChange={onOverridesOnlyChange} />
						</div>
						<BulkActionBar
							count={actionable.length}
							bulk={pending.bulk}
							onSet={(feedbackMode) => {
								if (feedbackMode === "AUTOMATIC") {
									setAutomaticPromotion({ scope: "bulk", slugs: actionable });
									return;
								}
								onBulkSetFeedbackMode(actionable, feedbackMode);
							}}
							onClear={() => setSelected(new Set())}
						/>
					</div>

					{buckets.length === 0 ? (
						<Empty variant="outlined">
							<EmptyHeader>
								<EmptyTitle>
									{overridesOnly ? "Nothing was set by hand" : "No practices yet"}
								</EmptyTitle>
								<EmptyDescription>
									{overridesOnly
										? "Every group and practice follows the workspace default above. Turn off the filter to see them."
										: "Add practices in Practice setup, then choose how their feedback is sent."}
								</EmptyDescription>
							</EmptyHeader>
						</Empty>
					) : (
						<Accordion
							value={openValue}
							onValueChange={(next) => setOpenGroups(next as string[])}
							className="space-y-2"
						>
							{buckets.map((bucket) => (
								<GroupSection
									key={bucket.key}
									workspaceSlug={workspaceSlug}
									bucket={bucket}
									pending={pending}
									selected={selected}
									onSelect={select}
									onSetGroupFeedbackMode={(groupSlug, feedbackMode) => {
										if (feedbackMode === "AUTOMATIC") {
											setAutomaticPromotion({ scope: "group", slug: groupSlug, name: bucket.name });
											return;
										}
										onSetGroupFeedbackMode(groupSlug, feedbackMode);
									}}
									onClearGroupFeedbackMode={onClearGroupFeedbackMode}
									onSetPracticeFeedbackMode={(practice, feedbackMode) => {
										if (feedbackMode === "AUTOMATIC") {
											setAutomaticPromotion({
												scope: "practice",
												slug: practice.slug,
												name: practiceName(practice),
											});
											return;
										}
										onSetPracticeFeedbackMode(practice.slug, feedbackMode);
									}}
									onClearPracticeFeedbackMode={onClearPracticeFeedbackMode}
									onResetPractices={(slugs) => onBulkSetFeedbackMode(slugs, null)}
								/>
							))}
						</Accordion>
					)}
				</>
			)}
		</div>
	);
}

function automaticPromotionLabel(promotion: AutomaticPromotion | null): string {
	if (promotion === null) {
		return "this selection";
	}
	if (promotion.scope === "workspace") {
		return "the workspace default";
	}
	if (promotion.scope === "group") {
		return `the ${promotion.name} group`;
	}
	if (promotion.scope === "practice") {
		return promotion.name;
	}
	return `${promotion.slugs.length} selected ${promotion.slugs.length === 1 ? "practice" : "practices"}`;
}

function applyAutomaticPromotion(
	promotion: AutomaticPromotion,
	actions: Pick<
		PracticeFeedbackModePageProps,
		| "onSetWorkspaceDefault"
		| "onSetGroupFeedbackMode"
		| "onSetPracticeFeedbackMode"
		| "onBulkSetFeedbackMode"
	>,
) {
	switch (promotion.scope) {
		case "workspace": {
			actions.onSetWorkspaceDefault("AUTOMATIC");
			return;
		}
		case "group": {
			actions.onSetGroupFeedbackMode(promotion.slug, "AUTOMATIC");
			return;
		}
		case "practice": {
			actions.onSetPracticeFeedbackMode(promotion.slug, "AUTOMATIC");
			return;
		}
		case "bulk": {
			actions.onBulkSetFeedbackMode(promotion.slugs, "AUTOMATIC");
		}
	}
}

function WorkspaceDecisionCard({
	settings,
	saving,
	onSetWorkspaceDefault,
	onClearWorkspaceDefault,
}: {
	settings: PracticeReviewSettings;
	saving: boolean;
	onSetWorkspaceDefault: (feedbackMode: FeedbackMode) => void;
	onClearWorkspaceDefault: () => void;
}) {
	const chosen = settings.defaultFeedbackModeOverride !== null;

	return (
		<Card>
			<CardHeader>
				<CardTitle>
					<h2>Workspace default</h2>
				</CardTitle>
				<CardDescription>
					One feedback mode for the whole workspace. Every group and practice below follows it,
					unless you set that group or practice by hand. Feedback mode decides where observations
					become feedback. It does not decide what Hephaestus reviews.
				</CardDescription>
			</CardHeader>
			<CardContent>
				<Field>
					<FieldTitle>Feedback mode</FieldTitle>
					<FeedbackModeLadder
						label="Workspace feedback mode"
						variant="full"
						value={settings.defaultFeedbackMode}
						disabled={saving}
						onChange={onSetWorkspaceDefault}
					/>
					<DecisionNote
						follows={
							chosen
								? null
								: `Not chosen yet, so ${FEEDBACK_MODE_LABELS[settings.defaultFeedbackMode]} applies.`
						}
						resetLabel="Use the default feedback mode for the workspace"
						disabled={saving}
						onClear={onClearWorkspaceDefault}
					/>
				</Field>
			</CardContent>
		</Card>
	);
}

function FeedbackModeSummary({
	counts,
	overrides,
}: {
	counts: Record<FeedbackMode, number>;
	overrides: { practices: number; groups: number };
}) {
	const byHand = byHandSentence(overrides);
	return (
		<p className="min-w-0 text-sm text-muted-foreground" aria-live="polite" aria-atomic="true">
			{feedbackModeDistributionSentence(counts)}
			{byHand === "" ? "" : ` ${byHand}`}
		</p>
	);
}

function byHandSentence(overrides: { practices: number; groups: number }): string {
	const parts: string[] = [];
	if (overrides.practices > 0) {
		parts.push(`${overrides.practices} ${overrides.practices === 1 ? "practice" : "practices"}`);
	}
	if (overrides.groups > 0) {
		parts.push(`${overrides.groups} ${overrides.groups === 1 ? "group" : "groups"}`);
	}
	return parts.length === 0 ? "" : `${parts.join(" and ")} set by hand.`;
}

function ScopeFilter({ value, onChange }: { value: boolean; onChange: (next: boolean) => void }) {
	return (
		<ToggleGroup
			role="toolbar"
			variant="outline"
			size="sm"
			aria-label="Filter practices"
			value={[value ? "OVERRIDES" : "ALL"]}
			onValueChange={(next) => next[0] && onChange(next[0] === "OVERRIDES")}
		>
			<ToggleGroupItem value="ALL">All</ToggleGroupItem>
			<ToggleGroupItem value="OVERRIDES">Set by hand</ToggleGroupItem>
		</ToggleGroup>
	);
}

function BulkActionBar({
	count,
	bulk,
	onSet,
	onClear,
}: {
	count: number;
	bulk: { done: number; total: number } | null;
	onSet: (feedbackMode: FeedbackMode | null) => void;
	onClear: () => void;
}) {
	if (count === 0 && bulk === null) {
		return null;
	}

	return (
		<Item variant="muted" size="sm" role="group" aria-label="Selected practices">
			<ItemContent>
				<ItemTitle aria-live="polite" aria-atomic="true" className="font-normal">
					{bulk
						? `Changing ${bulk.done} of ${bulk.total}…`
						: `${count} ${count === 1 ? "practice" : "practices"} selected`}
				</ItemTitle>
			</ItemContent>
			<ItemActions>
				<ButtonGroup>
					<DropdownMenu>
						<DropdownMenuTrigger
							render={
								<Button size="sm" variant="outline" disabled={bulk !== null || count === 0}>
									Change selected practices
								</Button>
							}
						/>
						<DropdownMenuContent align="end">
							<DropdownMenuGroup>
								<DropdownMenuLabel>Set every selected practice to</DropdownMenuLabel>
								{FEEDBACK_MODE_ORDER.map((feedbackMode) => {
									const { icon: Icon, label } = FEEDBACK_MODE_DEFS[feedbackMode];
									return (
										<DropdownMenuItem key={feedbackMode} onClick={() => onSet(feedbackMode)}>
											<Icon aria-hidden />
											{label}
										</DropdownMenuItem>
									);
								})}
							</DropdownMenuGroup>
							<DropdownMenuSeparator />
							<DropdownMenuItem onClick={() => onSet(null)}>
								Use the inherited feedback mode
							</DropdownMenuItem>
						</DropdownMenuContent>
					</DropdownMenu>
					<Button size="sm" variant="outline" disabled={bulk !== null} onClick={onClear}>
						Clear selection
					</Button>
				</ButtonGroup>
			</ItemActions>
		</Item>
	);
}

function GroupSection({
	workspaceSlug,
	bucket,
	pending,
	selected,
	onSelect,
	onSetGroupFeedbackMode,
	onClearGroupFeedbackMode,
	onSetPracticeFeedbackMode,
	onClearPracticeFeedbackMode,
	onResetPractices,
}: {
	workspaceSlug: string;
	bucket: FeedbackModeGroup;
	pending: PracticeFeedbackModePendingState;
	selected: ReadonlySet<string>;
	onSelect: (slugs: readonly string[], checked: boolean) => void;
	onSetGroupFeedbackMode: (groupSlug: string, feedbackMode: FeedbackMode) => void;
	onClearGroupFeedbackMode: (groupSlug: string) => void;
	onSetPracticeFeedbackMode: (practice: Practice, feedbackMode: FeedbackMode) => void;
	onClearPracticeFeedbackMode: (practiceSlug: string) => void;
	onResetPractices: (practiceSlugs: string[]) => void;
}) {
	const { group } = bucket;
	// Every practice holds its own mode, so the setting this group shows reaches none of them.
	const allSetByHand =
		bucket.totalPractices > 0 && bucket.overriddenCount === bucket.totalPractices;
	const groupFollows =
		group === null ? null : inheritedSentenceOrNull(group.feedbackMode, "GROUP", null);
	const setByHandNote = allSetByHand ? (
		<AllSetByHandNote
			count={bucket.totalPractices}
			setting={group === null ? "the workspace default" : "this group setting"}
			name={bucket.name}
			disabled={pending.bulk !== null}
			onReset={() => onResetPractices(bucket.practices.map((practice) => practice.slug))}
		/>
	) : undefined;
	const groupPending = group !== null && pending.groupSlugs.has(group.slug);
	const slugs = bucket.practices.map((practice) => practice.slug);
	const allSelected = slugs.length > 0 && slugs.every((slug) => selected.has(slug));

	return (
		<AccordionItem value={bucket.key} variant="card" className="scroll-mt-24 px-3">
			<div className={cn("grid gap-2 py-1 sm:items-center sm:gap-4", GROUP_GRID)}>
				<AccordionTrigger>
					<span className="flex min-w-0 flex-col gap-1">
						<span className="flex flex-wrap items-center gap-2">
							<span className="break-words">{bucket.name}</span>
							{bucket.overriddenCount > 0 && (
								<Badge variant="outline">{bucket.overriddenCount} set by hand</Badge>
							)}
						</span>
						<span className="text-xs font-normal text-muted-foreground">
							{feedbackModeDistributionSentence(bucket.counts)}
						</span>
					</span>
				</AccordionTrigger>
				{group === null ? (
					<div className={cn("min-w-0", DECISION_COLUMN)}>
						{setByHandNote ?? (
							<span className="text-xs text-muted-foreground">Follows the workspace default</span>
						)}
					</div>
				) : (
					<div className={cn("min-w-0 space-y-1", DECISION_COLUMN)}>
						<FeedbackModeLadder
							label={`Feedback mode for ${bucket.name}`}
							value={group.feedbackMode.effective}
							muted={!isOverridden(group.feedbackMode, "GROUP")}
							disabled={groupPending}
							onChange={(feedbackMode) => onSetGroupFeedbackMode(group.slug, feedbackMode)}
						/>
						{/* "Follows the workspace default" would read as effective here; the note says why it is not. */}
						{!(allSetByHand && groupFollows !== null) && (
							<DecisionNote
								follows={groupFollows}
								resetLabel={`Use the default feedback mode for ${bucket.name}`}
								disabled={groupPending}
								onClear={() => onClearGroupFeedbackMode(group.slug)}
							/>
						)}
						{setByHandNote}
						{group.feedbackMode.effective === "OFF" && <OffNote />}
					</div>
				)}
			</div>
			<AccordionContent className="pb-3">
				{bucket.practices.length === 0 ? (
					<p className="py-2 text-sm text-muted-foreground">
						{bucket.totalPractices === 0
							? "No practices here"
							: "No practices here were set by hand"}
					</p>
				) : (
					<>
						<Button
							variant="link"
							size="inline"
							className="text-xs"
							aria-label={`${allSelected ? "Deselect" : "Select"} all ${slugs.length} ${slugs.length === 1 ? "practice" : "practices"} in ${bucket.name}`}
							onClick={() => onSelect(slugs, !allSelected)}
						>
							{allSelected ? "Deselect" : "Select"} all {slugs.length}
						</Button>
						<ul className="mt-2 border-t">
							{bucket.practices.map((practice) => (
								<PracticeFeedbackModeRow
									key={practice.slug}
									workspaceSlug={workspaceSlug}
									practice={practice}
									groupName={group === null ? null : bucket.name}
									pending={pending.practiceSlugs.has(practice.slug)}
									selected={selected.has(practice.slug)}
									onSelect={onSelect}
									onSetFeedbackMode={onSetPracticeFeedbackMode}
									onClearFeedbackMode={onClearPracticeFeedbackMode}
								/>
							))}
						</ul>
					</>
				)}
			</AccordionContent>
		</AccordionItem>
	);
}

function PracticeFeedbackModeRow({
	workspaceSlug,
	practice,
	groupName,
	pending,
	selected,
	onSelect,
	onSetFeedbackMode,
	onClearFeedbackMode,
}: {
	workspaceSlug: string;
	practice: Practice;
	groupName: string | null;
	pending: boolean;
	selected: boolean;
	onSelect: (slugs: readonly string[], checked: boolean) => void;
	onSetFeedbackMode: (practice: Practice, feedbackMode: FeedbackMode) => void;
	onClearFeedbackMode: (practiceSlug: string) => void;
}) {
	const name = practiceName(practice);

	return (
		<Item
			render={<li />}
			variant="row"
			className={cn("grid items-start gap-2 px-0 py-3 sm:gap-4", ROW_GRID)}
		>
			<ItemMedia>
				<Checkbox
					checked={selected}
					aria-label={`Select ${name}`}
					onCheckedChange={(checked) => onSelect([practice.slug], checked)}
				/>
			</ItemMedia>
			<ItemContent className="min-w-0 gap-0.5">
				<ItemTitle className="line-clamp-none w-full min-w-0">
					<PracticeDetailHoverCard practice={practice}>
						<Link
							to="/w/$workspaceSlug/admin/practices"
							params={{ workspaceSlug }}
							search={detailSearch(practiceSetupLevel(practice.slug))}
							className="rounded-sm break-words hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-hidden"
						>
							{name}
						</Link>
					</PracticeDetailHoverCard>
				</ItemTitle>
				<ItemDescription className="flex flex-wrap items-center gap-1.5">
					<WorkTypeLabel itemKind={practice.kind} />
				</ItemDescription>
				{!reviewedByHephaestus(practice) && <ReviewedByNote practice={practice} />}
			</ItemContent>
			<ItemActions
				className={cn(
					"col-span-2 min-w-0 flex-col items-stretch gap-1 sm:col-span-1",
					DECISION_COLUMN,
				)}
			>
				<FeedbackModeLadder
					label={`Feedback mode for ${name}`}
					value={practice.feedbackMode.effective}
					muted={!isOverridden(practice.feedbackMode, "PRACTICE")}
					disabled={pending}
					onChange={(feedbackMode) => onSetFeedbackMode(practice, feedbackMode)}
				/>
				<DecisionNote
					follows={inheritedSentenceOrNull(
						practice.feedbackMode,
						"PRACTICE",
						groupName ?? WORKSPACE_DEFAULT_SOURCE,
					)}
					resetLabel={`Use the default feedback mode for ${name}`}
					disabled={pending}
					onClear={() => onClearFeedbackMode(practice.slug)}
				/>
				{practice.feedbackMode.effective === "OFF" && <OffNote />}
			</ItemActions>
		</Item>
	);
}

function OffNote() {
	return <p className="text-xs text-muted-foreground">{FEEDBACK_MODE_DESCRIPTIONS.OFF}</p>;
}

function ReviewedByNote({ practice }: { practice: Practice }) {
	const { reviewedBy } = currentDefinition(practice);
	return (
		<p className="text-xs text-muted-foreground">
			{reviewedBy.by === "PEOPLE" ? (
				<>Reviewed by: People. {reviewedBy.humanReason?.description}</>
			) : (
				"Reviewed by: Nobody. This practice offers guidance without automated review."
			)}{" "}
			Hephaestus does not review it, which is separate from feedback mode Off.
		</p>
	);
}

// DecisionNote treats null as a local choice that can be reset.
function inheritedSentenceOrNull(
	assignment: Parameters<typeof feedbackModeSourceOf>[0],
	level: FeedbackModeLevel,
	inheritedFrom: string | null,
): string | null {
	const source = feedbackModeSourceOf(assignment, level, inheritedFrom);
	return source.kind === "inherited" ? feedbackModeSourceSentence(source) : null;
}

function AllSetByHandNote({
	count,
	setting,
	name,
	disabled,
	onReset,
}: {
	count: number;
	setting: string;
	name: string;
	disabled: boolean;
	onReset: () => void;
}) {
	return (
		<p className="text-xs text-muted-foreground">
			{count === 1 ? "Its 1 practice is" : `All ${count} practices are`} set by hand, so {setting}{" "}
			has no effect.{" "}
			<Button
				variant="link"
				size="inline"
				aria-label={`Reset the practices in ${name} to follow ${setting}`}
				disabled={disabled}
				onClick={onReset}
			>
				Reset them to follow it
			</Button>
		</p>
	);
}

interface DecisionNoteProps {
	follows: string | null;
	resetLabel: string;
	disabled: boolean;
	onClear: () => void;
}

function DecisionNote({ follows, resetLabel, disabled, onClear }: DecisionNoteProps) {
	return (
		<p className="text-xs text-muted-foreground">
			{follows ?? (
				<>
					Set here.{" "}
					<Button
						variant="link"
						size="inline"
						aria-label={resetLabel}
						disabled={disabled}
						onClick={onClear}
					>
						Use the default
					</Button>
				</>
			)}
		</p>
	);
}
