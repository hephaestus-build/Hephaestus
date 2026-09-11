import { Link } from "@tanstack/react-router";
import { cn } from "cn";
import { useState } from "react";
import type { AutonomyRollup, Practice, PracticeReviewSettings } from "@/api/types.gen";
import { automatedReviewLimitationLabel } from "@/components/admin/practice-catalog/evidence-presentation";
import { PracticeDetailHoverCard } from "@/components/admin/practice-catalog/PracticeDetailHoverCard";
import { AUTONOMY_DEFS } from "@/components/practice-vocabulary/autonomy-defs";
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
import { Field, FieldDescription, FieldTitle } from "@/components/ui/field";
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
	autonomyDistributionSentence,
	autonomySourceOf,
	autonomySourceSentence,
	PRACTICE_AUTONOMY_LABELS,
	PRACTICE_AUTONOMY_ORDER,
	type PracticeAutonomy,
	WORKSPACE_DEFAULT_SOURCE,
} from "@/lib/practice-autonomy";
import { AutonomyLadder } from "./AutonomyLadder";
import {
	type AutonomyGroup,
	countOverrides,
	groupPracticesByGroup,
	isOverridden,
	reviewableByHephaestus,
} from "./practice-autonomy-model";

const DECISION_COLUMN = "sm:w-80";

const GROUP_GRID = "sm:grid-cols-[minmax(0,1fr)_20rem]";

const ROW_GRID = "grid-cols-[auto_minmax(0,1fr)] sm:grid-cols-[auto_minmax(0,1fr)_20rem]";

type AutomaticPromotion =
	| { scope: "workspace" }
	| { scope: "group"; slug: string; name: string }
	| { scope: "practice"; slug: string; name: string }
	| { scope: "bulk"; slugs: string[] };

export interface PracticeAutonomyPendingState {
	workspace: boolean;
	groupSlugs: ReadonlySet<string>;
	practiceSlugs: ReadonlySet<string>;
	bulk: { done: number; total: number } | null;
}

export interface PracticeAutonomyPageProps {
	workspaceSlug: string;
	settings: PracticeReviewSettings;
	rollup: AutonomyRollup;
	practices: Practice[];
	pending: PracticeAutonomyPendingState;
	overridesOnly: boolean;
	onOverridesOnlyChange: (next: boolean) => void;
	onSetWorkspaceDefault: (autonomy: PracticeAutonomy) => void;
	onClearWorkspaceDefault: () => void;
	onSetGroupAutonomy: (groupSlug: string, autonomy: PracticeAutonomy) => void;
	onClearGroupAutonomy: (groupSlug: string) => void;
	onSetPracticeAutonomy: (practiceSlug: string, autonomy: PracticeAutonomy) => void;
	onClearPracticeAutonomy: (practiceSlug: string) => void;
	onBulkSetAutonomy: (practiceSlugs: string[], autonomy: PracticeAutonomy | null) => void;
}

export function PracticeAutonomyPage({
	workspaceSlug,
	settings,
	rollup,
	practices,
	pending,
	overridesOnly,
	onOverridesOnlyChange,
	onSetWorkspaceDefault,
	onClearWorkspaceDefault,
	onSetGroupAutonomy,
	onClearGroupAutonomy,
	onSetPracticeAutonomy,
	onClearPracticeAutonomy,
	onBulkSetAutonomy,
}: PracticeAutonomyPageProps) {
	const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
	const [openGroups, setOpenGroups] = useState<string[]>([]);
	const [automaticPromotion, setAutomaticPromotion] = useState<AutomaticPromotion | null>(null);

	const groups = groupPracticesByGroup(rollup, practices, { overridesOnly });
	const overrides = countOverrides(rollup);
	const openValue = overridesOnly ? groups.map((group) => group.key) : openGroups;

	const selectableSlugs = new Set(
		groups.flatMap((group) =>
			group.practices
				.filter((practice) => reviewableByHephaestus(practice.automatedReviewPolicy))
				.map((practice) => practice.slug),
		),
	);
	const actionable = [...selected].filter((slug) => selectableSlugs.has(slug));

	const toggle = (slug: string, checked: boolean) => {
		setSelected((current) => {
			const next = new Set(current);
			if (checked) next.add(slug);
			else next.delete(slug);
			return next;
		});
	};

	return (
		<div className="space-y-6">
			<AlertDialog
				open={automaticPromotion !== null}
				onOpenChange={(open) => !open && setAutomaticPromotion(null)}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Start sending automatically?</AlertDialogTitle>
						<AlertDialogDescription>
							Set {automaticPromotionLabel(automaticPromotion)} to Send automatically. Eligible new
							feedback affected by this setting can proceed without approval, subject to delivery
							policy. Feedback already awaiting approval will remain unchanged.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							onClick={() => {
								if (automaticPromotion !== null) {
									applyAutomaticPromotion(automaticPromotion, {
										onSetWorkspaceDefault,
										onSetGroupAutonomy,
										onSetPracticeAutonomy,
										onBulkSetAutonomy,
									});
								}
								setAutomaticPromotion(null);
							}}
						>
							Start sending automatically
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
			<WorkspaceDecisionCard
				settings={settings}
				saving={pending.workspace}
				onSetWorkspaceDefault={(autonomy) => {
					if (autonomy === "AUTOMATIC") {
						setAutomaticPromotion({ scope: "workspace" });
						return;
					}
					onSetWorkspaceDefault(autonomy);
				}}
				onClearWorkspaceDefault={onClearWorkspaceDefault}
			/>
			<div className="sticky top-0 z-20 space-y-3 border-b bg-background/95 py-3 backdrop-blur supports-backdrop-filter:bg-background/80">
				<div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
					<AutonomySummary counts={rollup.counts} overrides={overrides} />
					<ScopeFilter value={overridesOnly} onChange={onOverridesOnlyChange} />
				</div>
				<BulkActionBar
					count={actionable.length}
					bulk={pending.bulk}
					onSet={(autonomy) => {
						if (autonomy === "AUTOMATIC") {
							setAutomaticPromotion({ scope: "bulk", slugs: actionable });
							return;
						}
						onBulkSetAutonomy(actionable, autonomy);
					}}
					onClear={() => setSelected(new Set())}
				/>
			</div>

			{groups.length === 0 ? (
				<Empty className="border">
					<EmptyHeader>
						<EmptyTitle>
							{overridesOnly ? "Nothing was set by hand" : "No practices yet"}
						</EmptyTitle>
						<EmptyDescription>
							{overridesOnly
								? "Every group and practice follows the workspace default above. Switch the filter off to see them."
								: "Add practices in Practice setup, then decide how far reviews go on them."}
						</EmptyDescription>
					</EmptyHeader>
				</Empty>
			) : (
				<Accordion
					value={openValue}
					onValueChange={(next) => setOpenGroups(next as string[])}
					className="space-y-2"
				>
					{groups.map((group) => (
						<GroupGroup
							key={group.key}
							workspaceSlug={workspaceSlug}
							group={group}
							pending={pending}
							selected={selected}
							onToggle={toggle}
							onSelectMany={(slugs, checked) =>
								setSelected((current) => {
									const next = new Set(current);
									for (const slug of slugs) {
										if (checked) next.add(slug);
										else next.delete(slug);
									}
									return next;
								})
							}
							onSetGroupAutonomy={(groupSlug, autonomy) => {
								if (autonomy === "AUTOMATIC") {
									setAutomaticPromotion({ scope: "group", slug: groupSlug, name: group.name });
									return;
								}
								onSetGroupAutonomy(groupSlug, autonomy);
							}}
							onClearGroupAutonomy={onClearGroupAutonomy}
							onSetPracticeAutonomy={(practiceSlug, autonomy) => {
								if (autonomy === "AUTOMATIC") {
									const practice = group.practices.find(
										(candidate) => candidate.slug === practiceSlug,
									);
									if (practice) {
										setAutomaticPromotion({
											scope: "practice",
											slug: practiceSlug,
											name: practice.name,
										});
									}
									return;
								}
								onSetPracticeAutonomy(practiceSlug, autonomy);
							}}
							onClearPracticeAutonomy={onClearPracticeAutonomy}
						/>
					))}
				</Accordion>
			)}
		</div>
	);
}

function automaticPromotionLabel(promotion: AutomaticPromotion | null): string {
	if (promotion === null) return "this selection";
	if (promotion.scope === "workspace") return "the workspace default";
	if (promotion.scope === "group") return `the ${promotion.name} group`;
	if (promotion.scope === "practice") return promotion.name;
	return `${promotion.slugs.length} selected ${promotion.slugs.length === 1 ? "practice" : "practices"}`;
}

function applyAutomaticPromotion(
	promotion: AutomaticPromotion,
	actions: Pick<
		PracticeAutonomyPageProps,
		"onSetWorkspaceDefault" | "onSetGroupAutonomy" | "onSetPracticeAutonomy" | "onBulkSetAutonomy"
	>,
) {
	switch (promotion.scope) {
		case "workspace":
			actions.onSetWorkspaceDefault("AUTOMATIC");
			return;
		case "group":
			actions.onSetGroupAutonomy(promotion.slug, "AUTOMATIC");
			return;
		case "practice":
			actions.onSetPracticeAutonomy(promotion.slug, "AUTOMATIC");
			return;
		case "bulk":
			actions.onBulkSetAutonomy(promotion.slugs, "AUTOMATIC");
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
	onSetWorkspaceDefault: (autonomy: PracticeAutonomy) => void;
	onClearWorkspaceDefault: () => void;
}) {
	const autonomyChosen = settings.defaultAutonomyOverride != null;

	return (
		<Card>
			<CardHeader>
				<CardTitle>
					<h2>Workspace default</h2>
				</CardTitle>
				<CardDescription>
					One decision for the whole workspace. Every group and every practice below follows it
					unless somebody says otherwise.
				</CardDescription>
			</CardHeader>
			<CardContent>
				<Field>
					<FieldTitle>How far reviews go without you</FieldTitle>
					<FieldDescription>
						Off stops the review. Review before sending prepares a proposal for a person to decide.
						Send automatically delivers eligible feedback without waiting.
					</FieldDescription>
					<AutonomyLadder
						label="How far reviews go without you"
						variant="full"
						value={settings.defaultAutonomy}
						disabled={saving}
						onChange={onSetWorkspaceDefault}
					/>
					<DecisionNote
						follows={
							autonomyChosen
								? null
								: `Not chosen yet, so ${PRACTICE_AUTONOMY_LABELS[settings.defaultAutonomy]} applies.`
						}
						resetLabel="Use the default for how far reviews go without you"
						disabled={saving}
						onClear={onClearWorkspaceDefault}
					/>
				</Field>
			</CardContent>
		</Card>
	);
}

function AutonomySummary({
	counts,
	overrides,
}: {
	counts: Record<string, number>;
	overrides: { practices: number; groups: number };
}) {
	return (
		<p className="min-w-0 text-muted-foreground text-sm" aria-live="polite" aria-atomic="true">
			{autonomyDistributionSentence(counts)} {byHandSentence(overrides)}
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
	onSet: (autonomy: PracticeAutonomy | null) => void;
	onClear: () => void;
}) {
	if (count === 0 && bulk === null) return null;

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
									Change the selected
								</Button>
							}
						/>
						<DropdownMenuContent align="end">
							<DropdownMenuGroup>
								<DropdownMenuLabel>Set every selected practice to</DropdownMenuLabel>
								{PRACTICE_AUTONOMY_ORDER.map((autonomy) => {
									const { icon: Icon, label } = AUTONOMY_DEFS[autonomy];
									return (
										<DropdownMenuItem key={autonomy} onClick={() => onSet(autonomy)}>
											<Icon aria-hidden />
											{label}
										</DropdownMenuItem>
									);
								})}
							</DropdownMenuGroup>
							<DropdownMenuSeparator />
							<DropdownMenuItem onClick={() => onSet(null)}>
								Use the inherited setting
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

function GroupGroup({
	workspaceSlug,
	group,
	pending,
	selected,
	onToggle,
	onSelectMany,
	onSetGroupAutonomy,
	onClearGroupAutonomy,
	onSetPracticeAutonomy,
	onClearPracticeAutonomy,
}: {
	workspaceSlug: string;
	group: AutonomyGroup;
	pending: PracticeAutonomyPendingState;
	selected: ReadonlySet<string>;
	onToggle: (slug: string, checked: boolean) => void;
	onSelectMany: (slugs: string[], checked: boolean) => void;
	onSetGroupAutonomy: (groupSlug: string, autonomy: PracticeAutonomy) => void;
	onClearGroupAutonomy: (groupSlug: string) => void;
	onSetPracticeAutonomy: (practiceSlug: string, autonomy: PracticeAutonomy) => void;
	onClearPracticeAutonomy: (practiceSlug: string) => void;
}) {
	const groupSlug = group.groupSlug;
	const groupPending = groupSlug !== null && pending.groupSlugs.has(groupSlug);
	const selectableSlugs = group.practices
		.filter((practice) => reviewableByHephaestus(practice.automatedReviewPolicy))
		.map((practice) => practice.slug);
	const allSelected =
		selectableSlugs.length > 0 && selectableSlugs.every((slug) => selected.has(slug));

	return (
		<AccordionItem value={group.key} className="scroll-mt-24 rounded-lg border bg-card px-3">
			<div className={cn("grid gap-2 py-1 sm:items-center sm:gap-4", GROUP_GRID)}>
				<AccordionTrigger>
					<span className="flex min-w-0 flex-col gap-1">
						<span className="flex flex-wrap items-center gap-2">
							<span className="break-words">{group.name}</span>
							{group.overriddenCount > 0 && (
								<Badge variant="outline">{group.overriddenCount} set by hand</Badge>
							)}
						</span>
						<span className="font-normal text-muted-foreground text-xs">
							{autonomyDistributionSentence(group.counts)}
						</span>
					</span>
				</AccordionTrigger>
				{groupSlug === null ? (
					<span className={cn("min-w-0 text-muted-foreground text-xs", DECISION_COLUMN)}>
						Follows the workspace default
					</span>
				) : (
					<div className={cn("min-w-0 space-y-1", DECISION_COLUMN)}>
						<AutonomyLadder
							label={`How far reviews go in ${group.name}`}
							value={group.autonomy.effective}
							muted={!isOverridden(group.autonomy)}
							disabled={groupPending}
							onChange={(autonomy) => onSetGroupAutonomy(groupSlug, autonomy)}
						/>
						<DecisionNote
							follows={inheritedSentenceOrNull(group.autonomy, WORKSPACE_DEFAULT_SOURCE)}
							resetLabel={`Use the default for ${group.name}`}
							disabled={groupPending}
							onClear={() => onClearGroupAutonomy(groupSlug)}
						/>
					</div>
				)}
			</div>
			<AccordionContent className="pb-3">
				{group.practices.length === 0 ? (
					<p className="py-2 text-muted-foreground text-sm">
						{group.totalPractices === 0
							? "No practices here."
							: "No practices here were set by hand."}
					</p>
				) : (
					<>
						{selectableSlugs.length > 0 && (
							<Button
								variant="link"
								size="inline"
								className="text-xs"
								aria-label={`${allSelected ? "Deselect" : "Select"} all ${selectableSlugs.length} practices in ${group.name}`}
								onClick={() => onSelectMany(selectableSlugs, !allSelected)}
							>
								{allSelected ? "Deselect" : "Select"} all {selectableSlugs.length}
							</Button>
						)}
						<ul className="mt-2 divide-y border-t">
							{group.practices.map((practice) => (
								<PracticeAutonomyRow
									key={practice.slug}
									workspaceSlug={workspaceSlug}
									practice={practice}
									groupName={group.groupSlug === null ? null : group.name}
									pending={pending.practiceSlugs.has(practice.slug)}
									selected={selected.has(practice.slug)}
									onToggle={onToggle}
									onSetAutonomy={onSetPracticeAutonomy}
									onClearAutonomy={onClearPracticeAutonomy}
								/>
							))}
						</ul>
					</>
				)}
			</AccordionContent>
		</AccordionItem>
	);
}

function PracticeAutonomyRow({
	workspaceSlug,
	practice,
	groupName,
	pending,
	selected,
	onToggle,
	onSetAutonomy,
	onClearAutonomy,
}: {
	workspaceSlug: string;
	practice: Practice;
	groupName: string | null;
	pending: boolean;
	selected: boolean;
	onToggle: (slug: string, checked: boolean) => void;
	onSetAutonomy: (practiceSlug: string, autonomy: PracticeAutonomy) => void;
	onClearAutonomy: (practiceSlug: string) => void;
}) {
	const reviewable = reviewableByHephaestus(practice.automatedReviewPolicy);
	const limitation = automatedReviewLimitationLabel(practice.automatedReviewPolicy.automatedReview);

	return (
		<Item
			render={<li />}
			variant="default"
			className={cn("grid items-start gap-2 rounded-none px-0 py-3 sm:gap-4", ROW_GRID)}
		>
			<ItemMedia>
				<Checkbox
					checked={selected}
					disabled={!reviewable}
					aria-label={`Select ${practice.name}`}
					onCheckedChange={(checked) => onToggle(practice.slug, checked)}
				/>
			</ItemMedia>
			<ItemContent className="min-w-0 gap-0.5">
				<ItemTitle className="w-full min-w-0 line-clamp-none">
					<PracticeDetailHoverCard practice={practice}>
						<Link
							to="/w/$workspaceSlug/admin/practices/$practiceSlug"
							params={{ workspaceSlug, practiceSlug: practice.slug }}
							className="break-words rounded-sm hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
						>
							{practice.name}
						</Link>
					</PracticeDetailHoverCard>
				</ItemTitle>
				<ItemDescription className="flex flex-wrap items-center gap-1.5">
					<WorkTypeLabel artifactKind={practice.artifactKind} />
					{limitation && <Badge variant="warning">{limitation}</Badge>}
				</ItemDescription>
			</ItemContent>
			<ItemActions
				className={cn(
					"col-span-2 min-w-0 flex-col items-stretch gap-1 sm:col-span-1",
					DECISION_COLUMN,
				)}
			>
				<AutonomyLadder
					label={`How far reviews go on ${practice.name}`}
					value={practice.autonomy.effective}
					muted={!isOverridden(practice.autonomy)}
					disabled={pending || !reviewable}
					onChange={(autonomy) => onSetAutonomy(practice.slug, autonomy)}
				/>
				{reviewable ? (
					<DecisionNote
						follows={inheritedSentenceOrNull(
							practice.autonomy,
							groupName ?? WORKSPACE_DEFAULT_SOURCE,
						)}
						resetLabel={`Use the default for ${practice.name}`}
						disabled={pending}
						onClear={() => onClearAutonomy(practice.slug)}
					/>
				) : (
					<p className="text-muted-foreground text-xs">
						This practice can't be reviewed automatically, so it stays off.
					</p>
				)}
			</ItemActions>
		</Item>
	);
}

export interface DecisionNoteProps {
	follows: string | null;
	resetLabel: string;
	disabled: boolean;
	onClear: () => void;
}

/** `DecisionNote` reads `null` as "chosen here, so offer the reset" — narrowed once, not per site. */
function inheritedSentenceOrNull(
	assignment: Parameters<typeof autonomySourceOf>[0],
	inheritedFrom: string,
): string | null {
	const source = autonomySourceOf(assignment, inheritedFrom);
	return source.kind === "inherited" ? autonomySourceSentence(source) : null;
}

function DecisionNote({ follows, resetLabel, disabled, onClear }: DecisionNoteProps) {
	return (
		<p className="text-muted-foreground text-xs">
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
