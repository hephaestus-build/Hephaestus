import { Link } from "@tanstack/react-router";
import { cn } from "cn";
import { BrainCircuit, CircleHelpIcon } from "lucide-react";
import { type ReactElement, type ReactNode, useId, useState } from "react";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

import type {
	AgentBinding,
	AgentBindingRequest,
	AvailableLlmModel,
	PracticeScriptSummary,
	PracticeScriptNeed,
	WorkspaceLlmUsageReport,
} from "@/api/types.gen";
import { practiceSetupLevel } from "@/components/admin/practices/practice-search";
import { purseCap } from "@/components/admin/usage/usage-utils";
import { FOCUS_RING } from "@/components/common/focus";
import { InlineLink } from "@/components/common/InlineLink";
import type { LoadState, PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import type { StatusDef } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { AiMark } from "@/components/icons/AiMark";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import {
	AGENT_PURPOSE_DEFS,
	AGENT_PURPOSES,
	type AgentPurpose,
	isAgentPurpose,
	isPracticeScriptPurpose,
	PRACTICE_SCRIPT_PURPOSES,
} from "@/components/practice-vocabulary/agent-purpose-defs";
import {
	type DataHandlingTier,
	DATA_HANDLING_TIERS,
	MEMBER_AI_CHOICE_DEFS,
	tierMembersPhrase,
} from "@/components/practice-vocabulary/data-handling-defs";
import { DataHandlingMark } from "@/components/practice-vocabulary/DataHandlingMark";
import { ModelKindMark } from "@/components/practice-vocabulary/ModelKindMark";
import { practiceScriptNeedBadge } from "@/components/practice-vocabulary/practice-script-need-defs";
import {
	PURPOSE_STATUS_DEFS,
	type PurposeStatus,
	purposeStatus,
} from "@/components/practice-vocabulary/purpose-status-defs";
import { UnmetTiers } from "@/components/practice-vocabulary/UnmetTiers";
import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

import { AgentBindingEditor, modelOf } from "./AgentBindingEditor";
import { BudgetExhaustedAlert } from "./BudgetExhaustedAlert";
import { PROVIDER_PANEL_ID } from "./ModelPicker";

export interface BindingTarget {
	purpose: AgentPurpose;
	tier: DataHandlingTier;
}

export function bindingTargetKey({ purpose, tier }: BindingTarget): string {
	return `${purpose}:${tier}`;
}

export type BindingWrite = "SAVE" | "CLEAR";

interface PurposeGroup {
	title: string;
	rule: string;
	purposes: AgentPurpose[];
}

const REVIEWS_GROUP: PurposeGroup = {
	title: "Reviews and conversations",
	rule: "A member who chooses Cloud gets the Cloud assignment when it is ready, and the In-house one when it is not. A member who chooses In-house gets only the In-house assignment.",
	purposes: AGENT_PURPOSES.filter((purpose) => !isPracticeScriptPurpose(purpose)),
};

const PRACTICE_SCRIPT_GROUP: PurposeGroup = {
	title: "Models for practice scripts",
	rule: "A practice’s script reviews its practice and can call these models. A script uses only models at least as strict as the review’s own model.",
	purposes: PRACTICE_SCRIPT_PURPOSES,
};

interface TierColumn {
	/**
	 * The tier under which the server reports these members as served: their AI choice's ceiling,
	 * or `UNDECLARED` for the members who have not chosen.
	 */
	tier: DataHandlingTier;
	label: string;
	icon: StatusDef["icon"];
}

const TIER_COLUMNS: readonly TierColumn[] = [
	{
		tier: "IN_HOUSE",
		label: MEMBER_AI_CHOICE_DEFS.IN_HOUSE_ONLY.label,
		icon: MEMBER_AI_CHOICE_DEFS.IN_HOUSE_ONLY.icon,
	},
	{
		tier: "CLOUD",
		label: MEMBER_AI_CHOICE_DEFS.CLOUD.label,
		icon: MEMBER_AI_CHOICE_DEFS.CLOUD.icon,
	},
	{ tier: "UNDECLARED", label: "Not chosen", icon: CircleHelpIcon },
];

function tierColumns(aiChoiceRequired: boolean): readonly TierColumn[] {
	return aiChoiceRequired
		? TIER_COLUMNS.filter((column) => column.tier !== "UNDECLARED")
		: TIER_COLUMNS;
}

const ROW_GRID = {
	2: "md:grid-cols-[minmax(11rem,1fr)_repeat(2,minmax(0,1fr))_12rem]",
	3: "md:grid-cols-[minmax(11rem,1fr)_repeat(3,minmax(0,1fr))_12rem]",
};

function rowGrid(aiChoiceRequired: boolean): string {
	return ROW_GRID[aiChoiceRequired ? 2 : 3];
}

// Routing comes from server servedTiers; the page must not derive privacy routes.
function servingBinding(
	own: readonly AgentBinding[],
	tier: DataHandlingTier,
): AgentBinding | undefined {
	return own.find((binding) => binding.servedTiers.includes(tier));
}

// A direct script model cannot serve members beyond their review model's privacy tier.
function servesNoneOfItsTier(binding: AgentBinding): boolean {
	return (
		binding.enabled && binding.ready && !binding.servedTiers.includes(binding.dataHandlingTier)
	);
}

const USED_BY_PREVIEW = 5;

export type PracticeScriptSummariesState = PanelState<{ summaries: PracticeScriptSummary[] }>;

export interface AgentBindingsPageProps {
	workspaceSlug: string;
	state: LoadState;
	bindings: AgentBinding[];
	availableModels: AvailableLlmModel[];
	practicesEnabled: boolean;
	/** Once a workspace requires the choice, the undeclared row serves nobody. */
	aiChoiceRequired: boolean;
	/**
	 * What each practice's script declared in its newest review. Its own state: the rest
	 * of the page works without it, so its failure shows only in the section *Models for practice scripts*.
	 */
	practiceScriptNeeds: PracticeScriptSummariesState;
	/**
	 * Whether the instance lets this workspace add its own providers. False, a hint that would send
	 * the admin to add one names who can share a model instead.
	 */
	ownProviderAllowed: boolean;
	/** The workspace's own providers, below the models. A picker with nothing to offer links to it. */
	providerPanel?: ReactElement;
	usage?: WorkspaceLlmUsageReport;
	focusPurpose?: AgentPurpose;
	refreshFailure?: RefreshFailure;
	onFocusPurposeClosed?: () => void;
	/** Rows with a save or clear in flight, keyed by {@link bindingTargetKey}. */
	pendingWrites: ReadonlyMap<string, BindingWrite>;
	/** Bumped after a write lands so the row reseeds from what the server returned. */
	saveRevisions?: Partial<Record<string, number>>;
	/** The server's refusal of the last save, worded by the route, shown in the row it refused. */
	saveErrors?: Partial<Record<string, string>>;
	onSave: (target: BindingTarget, body: AgentBindingRequest) => void;
	onTurnOff: (target: BindingTarget) => void;
}

export function AgentBindingsPage({
	workspaceSlug,
	state,
	usage,
	providerPanel,
	refreshFailure,
	...ready
}: AgentBindingsPageProps) {
	const ownProvider = usage === undefined ? undefined : purseCap(usage, "OWN_PROVIDER");
	return (
		<PageLayout>
			<PageHeader
				icon={<BrainCircuit />}
				title="AI models"
				description="Choose the models that run practice reviews and conversations."
			/>

			<div className="space-y-8">
				{usage !== undefined && (ownProvider?.paused === true || usage.instancePaused) && (
					<div className="space-y-3">
						{ownProvider?.paused === true && (
							<BudgetExhaustedAlert
								scope="own"
								verdict={ownProvider.verdict}
								context="models"
								workspaceSlug={workspaceSlug}
							/>
						)}
						{usage.instancePaused && (
							<BudgetExhaustedAlert
								scope="shared"
								verdict={usage.instanceBudgetVerdict}
								context="models"
								workspaceSlug={workspaceSlug}
							/>
						)}
					</div>
				)}

				{state.status === "error" && (
					<QueryErrorAlert
						error={state.error}
						title="We could not load AI models"
						onRetry={state.onRetry}
					/>
				)}
				{state.status === "loading" && (
					<PurposeGroupsSkeleton aiChoiceRequired={ready.aiChoiceRequired} />
				)}
				{state.status === "ready" && (
					<>
						{refreshFailure !== undefined && (
							<QueryErrorAlert
								error={refreshFailure.error}
								title="We could not refresh AI models"
								pending={refreshFailure.pending}
								onRetry={refreshFailure.onRetry}
							/>
						)}
						<PurposeGroups workspaceSlug={workspaceSlug} {...ready} />
						{providerPanel !== undefined && (
							// Focusable from script only, so the picker's link can move focus here; the panel
							// is its own section.
							<div
								id={PROVIDER_PANEL_ID}
								tabIndex={-1}
								className={cn("scroll-mt-24 rounded-md", FOCUS_RING)}
							>
								{providerPanel}
							</div>
						)}
					</>
				)}
			</div>
		</PageLayout>
	);
}

function TierHeader({ aiChoiceRequired }: { aiChoiceRequired: boolean }) {
	return (
		<div
			aria-hidden="true"
			className="hidden items-center gap-3 border-b px-4 py-2 text-xs text-muted-foreground md:flex"
		>
			<div className={cn("grid flex-1 items-center gap-x-4", rowGrid(aiChoiceRequired))}>
				<span />
				{tierColumns(aiChoiceRequired).map(({ label, icon: Icon }) => (
					<span key={label} className="flex items-center gap-1.5">
						<Icon className="size-3.5 shrink-0" />
						{label}
					</span>
				))}
				<span />
			</div>
			{/* Holds the width of the rows' chevron, so the columns line up with them. */}
			<span className="size-4 shrink-0" />
		</div>
	);
}

function PurposeGroupsSkeleton({ aiChoiceRequired }: { aiChoiceRequired: boolean }) {
	const columns = tierColumns(aiChoiceRequired);
	return (
		<div className="space-y-8" aria-busy="true">
			{[REVIEWS_GROUP, PRACTICE_SCRIPT_GROUP].map((group) => (
				<Section key={group.title} title={group.title} description={group.rule}>
					<Card flush>
						<TierHeader aiChoiceRequired={aiChoiceRequired} />
						<div className="divide-y">
							{group.purposes.map((purpose) => (
								<div key={purpose} className="flex items-center gap-3 px-4 py-3">
									<div
										className={cn(
											"grid flex-1 grid-cols-1 items-center gap-x-4 gap-y-2",
											rowGrid(aiChoiceRequired),
										)}
									>
										<div className="flex items-center gap-3">
											<Skeleton className="size-8 shrink-0 rounded-md" />
											<div className="min-w-0 flex-1 space-y-1.5">
												<Skeleton className="h-4 w-32" />
												{isPracticeScriptPurpose(purpose) && <Skeleton className="h-3 w-24" />}
											</div>
										</div>
										{columns.map((column) => (
											<Skeleton key={column.label} className="h-4 w-full max-w-28" />
										))}
									</div>
									<Skeleton className="size-4 shrink-0" />
								</div>
							))}
						</div>
					</Card>
				</Section>
			))}
		</div>
	);
}

interface UsedBy {
	practiceSlug: string;
	practiceName: string;
	need: PracticeScriptNeed["need"];
	unmetTiers: DataHandlingTier[];
}

interface TierCell {
	column: TierColumn;
	binding?: AgentBinding;
	model?: AvailableLlmModel;
	/**
	 * An assignment for this column's own tier that serves none of its members, such as a Cloud
	 * script model while Cloud reviews run In-house. Shown muted, so the assignment stays in sight.
	 */
	unused?: { model?: AvailableLlmModel };
}

interface PurposeView {
	purpose: AgentPurpose;
	bindings: AgentBinding[];
	off: boolean;
	status: PurposeStatus | null;
	cells: TierCell[];
	/**
	 * Purposes for practice scripts only: the practices whose scripts declared this model. Undefined for a
	 * purpose for practice scripts until the needs are in.
	 */
	usedBy?: UsedBy[];
	unmet: UsedBy[];
}

const { badgeVariant: UNMET_NEED_TONE, icon: UnmetNeedIcon } = PURPOSE_STATUS_DEFS.NEEDS_ATTENTION;

const unmetRequired = (entry: UsedBy) => entry.need === "required" && entry.unmetTiers.length > 0;

function usedByOrder(a: UsedBy, b: UsedBy): number {
	return (
		Number(unmetRequired(b)) - Number(unmetRequired(a)) ||
		Number(b.need === "required") - Number(a.need === "required") ||
		a.practiceName.localeCompare(b.practiceName)
	);
}

const SLOT_PURPOSES: Record<PracticeScriptNeed["slot"], AgentPurpose> = {
	chat: "PRACTICE_REVIEW",
	decision: "PRACTICE_DECISION",
	embedding: "PRACTICE_EMBEDDING",
	reranking: "PRACTICE_RERANKING",
};

function usedByOf(purpose: AgentPurpose, summaries: PracticeScriptSummary[]): UsedBy[] {
	return summaries
		.flatMap((summary) =>
			summary.needs
				.filter((need) => SLOT_PURPOSES[need.slot] === purpose)
				.map((need) => ({
					practiceSlug: summary.practiceSlug,
					practiceName: summary.practiceName,
					need: need.need,
					unmetTiers: need.unmetTiers,
				})),
		)
		.sort(usedByOrder);
}

interface PurposeViewInput {
	bindings: AgentBinding[];
	availableModels: AvailableLlmModel[];
	practicesEnabled: boolean;
	aiChoiceRequired: boolean;
	/** Undefined while the needs load or after they failed: no row claims anything about them. */
	needs: PracticeScriptSummary[] | undefined;
}

function purposeView(
	purpose: AgentPurpose,
	{ bindings, availableModels, practicesEnabled, aiChoiceRequired, needs }: PurposeViewInput,
): PurposeView {
	const own = bindings.filter((binding) => binding.purpose === purpose);
	const off = purpose !== "MENTOR" && !practicesEnabled;
	const practiceScript = isPracticeScriptPurpose(purpose);
	const usedBy = practiceScript && needs !== undefined ? usedByOf(purpose, needs) : undefined;
	const unmet = off ? [] : (usedBy ?? []).filter(unmetRequired);
	const cells = tierColumns(aiChoiceRequired).map((column) => {
		const binding = off ? undefined : servingBinding(own, column.tier);
		const assigned = own.find(
			(candidate) => candidate.dataHandlingTier === column.tier && servesNoneOfItsTier(candidate),
		);
		const unused = !off && binding === undefined && assigned !== undefined;
		return {
			column,
			binding,
			model: modelOf(binding, availableModels),
			unused: unused ? { model: modelOf(assigned, availableModels) } : undefined,
		};
	});
	return {
		purpose,
		bindings: own,
		off,
		usedBy,
		unmet,
		cells,
		status: purposeStatus({
			bindings: own,
			served: cells.map((cell) => cell.binding !== undefined),
			off,
			requiredNeedUnmet: unmet.length > 0,
			used: !practiceScript || (usedBy !== undefined && usedBy.length > 0),
		}),
	};
}

function opensAtMount(view: PurposeView): boolean {
	return (
		view.status === "NEEDS_ATTENTION" ||
		(view.purpose === "PRACTICE_REVIEW" && view.status === "NOT_SET")
	);
}

type PurposeGroupsProps = { workspaceSlug: string } & Omit<
	AgentBindingsPageProps,
	"workspaceSlug" | "state" | "usage" | "providerPanel" | "refreshFailure"
>;

function PurposeGroups({
	workspaceSlug,
	focusPurpose,
	onFocusPurposeClosed,
	...props
}: PurposeGroupsProps) {
	const { practiceScriptNeeds } = props;
	const needs = practiceScriptNeeds.status === "ready" ? practiceScriptNeeds.summaries : undefined;
	const views = new Map(
		[...REVIEWS_GROUP.purposes, ...PRACTICE_SCRIPT_GROUP.purposes].map((purpose) => [
			purpose,
			purposeView(purpose, { ...props, needs }),
		]),
	);
	const openingNow = (purposes: AgentPurpose[]) =>
		purposes.filter((purpose) => {
			const view = views.get(purpose);
			return view !== undefined && opensAtMount(view);
		});
	// Decide initial expansion once so saving does not move the admin's place.
	const [open, setOpen] = useState<AgentPurpose[]>(() => openingNow(AGENT_PURPOSES));
	// Needs load separately; decide expansion once on their first arrival.
	const [needsJudged, setNeedsJudged] = useState(needs !== undefined);
	if (needs !== undefined && !needsJudged) {
		setNeedsJudged(true);
		setOpen((current) => [
			...current,
			...openingNow(PRACTICE_SCRIPT_GROUP.purposes).filter((purpose) => !current.includes(purpose)),
		]);
	}
	const openValue =
		focusPurpose === undefined || open.includes(focusPurpose) ? open : [...open, focusPurpose];
	const onOpenChange = (next: AgentPurpose[]) => {
		setOpen(next);
		if (focusPurpose !== undefined && !next.includes(focusPurpose)) {
			onFocusPurposeClosed?.();
		}
	};
	const reviewsOffLink = (
		<Link
			to="/w/$workspaceSlug/admin/practices/review"
			params={{ workspaceSlug }}
			search={{ section: "automated-review" }}
			className="underline underline-offset-4"
		>
			Open Review settings
		</Link>
	);
	const off = !props.practicesEnabled;
	const sectionProps = {
		views,
		openValue,
		onOpenChange,
		workspaceSlug,
		focusPurpose,
		rowProps: props,
	};

	return (
		<div className="space-y-8">
			<PurposeGroupSection
				group={REVIEWS_GROUP}
				description={
					<>
						{REVIEWS_GROUP.rule}
						{off && <> Practice reviews are off, so their model is not used. {reviewsOffLink}</>}
					</>
				}
				{...sectionProps}
			/>
			<PurposeGroupSection
				group={PRACTICE_SCRIPT_GROUP}
				description={
					<>
						{PRACTICE_SCRIPT_GROUP.rule}
						{off && <> Practice reviews are off, so these models are not used. {reviewsOffLink}</>}
					</>
				}
				notice={
					practiceScriptNeeds.status === "error" && (
						<QueryErrorAlert
							error={practiceScriptNeeds.error}
							title="We could not load which practices use these models"
							onRetry={practiceScriptNeeds.onRetry}
						/>
					)
				}
				{...sectionProps}
			/>
		</div>
	);
}

type RowProps = Omit<PurposeGroupsProps, "workspaceSlug" | "focusPurpose" | "onFocusPurposeClosed">;

interface PurposeGroupSectionProps {
	group: PurposeGroup;
	description: ReactNode;
	/** Shown above the rows, such as why part of what they show could not load. */
	notice?: ReactNode;
	views: ReadonlyMap<AgentPurpose, PurposeView>;
	openValue: AgentPurpose[];
	onOpenChange: (next: AgentPurpose[]) => void;
	workspaceSlug: string;
	focusPurpose?: AgentPurpose;
	refreshFailure?: RefreshFailure;
	rowProps: RowProps;
}

function scrollToRow(node: HTMLElement | null) {
	node?.scrollIntoView({ block: "start" });
}

function PurposeGroupSection({
	group,
	description,
	notice,
	views,
	openValue,
	onOpenChange,
	workspaceSlug,
	focusPurpose,
	rowProps,
}: PurposeGroupSectionProps) {
	return (
		<Section title={group.title} description={description}>
			{notice}
			<Card flush className="overflow-hidden">
				<TierHeader aiChoiceRequired={rowProps.aiChoiceRequired} />
				<Accordion
					multiple
					value={openValue}
					onValueChange={(next) => onOpenChange(next.filter(isAgentPurpose))}
				>
					{group.purposes.map((purpose) => {
						const view = views.get(purpose);
						return (
							view && (
								<PurposeRow
									key={purpose}
									view={view}
									workspaceSlug={workspaceSlug}
									linked={purpose === focusPurpose}
									rowRef={purpose === focusPurpose ? scrollToRow : undefined}
									{...rowProps}
								/>
							)
						);
					})}
				</Accordion>
			</Card>
		</Section>
	);
}

function usedByLine(usedBy: UsedBy[]): string {
	const count = usedBy.length;
	if (count === 0) {
		return "No practice uses it";
	}
	return `Used by ${count} ${count === 1 ? "practice" : "practices"}`;
}

// Screen readers need the tier label because table headers may not be read with cells.
function TierCellView({ id, cell }: { id: string; cell: TierCell }) {
	const { column, binding, model, unused } = cell;
	const ColumnIcon = column.icon;
	const fallback = column.tier === "CLOUD" && binding?.dataHandlingTier === "IN_HOUSE";
	return (
		<span id={id} className="flex min-w-0 items-center gap-2 text-sm font-normal">
			{/* Below `md` the header is gone, so each cell names its column with the icon. */}
			<ColumnIcon className="size-3.5 shrink-0 text-muted-foreground md:hidden" aria-hidden />
			<span className="sr-only">{column.label}: </span>
			{binding !== undefined && (
				<>
					<CellModel model={model} />
					{fallback && <DataHandlingMark tier="IN_HOUSE" />}
				</>
			)}
			{binding === undefined && unused !== undefined && (
				<span className="flex min-w-0 items-center gap-2 text-muted-foreground">
					<CellModel model={unused.model} />
					<span className="shrink-0 text-xs">Not used</span>
				</span>
			)}
			{binding === undefined && unused === undefined && (
				<>
					<span aria-hidden className="text-muted-foreground">
						—
					</span>
					<span className="sr-only">none</span>
				</>
			)}
		</span>
	);
}

function CellModel({ model }: { model: AvailableLlmModel | undefined }) {
	const name = model?.displayName ?? "A model no longer offered here";
	return (
		<>
			<AiMark brand={model?.brand} size="sm" />
			<span className="min-w-0 truncate" title={name}>
				{name}
			</span>
		</>
	);
}

interface PurposeRowProps extends RowProps {
	view: PurposeView;
	workspaceSlug: string;
	linked: boolean;
	rowRef?: (node: HTMLElement | null) => void;
}

function PurposeRow({
	view,
	workspaceSlug,
	linked,
	rowRef,
	availableModels,
	aiChoiceRequired,
	practiceScriptNeeds,
	ownProviderAllowed,
	pendingWrites,
	saveRevisions,
	saveErrors,
	onSave,
	onTurnOff,
}: PurposeRowProps) {
	const baseId = useId();
	const { purpose, bindings, off, status, cells, usedBy, unmet } = view;
	const { title, description } = AGENT_PURPOSE_DEFS[purpose];
	const servingModels = availableModels.filter((model) => model.purposes.includes(purpose));
	const practiceScript = isPracticeScriptPurpose(purpose);
	const usedById = `${baseId}-used-by`;
	const statusId = `${baseId}-status`;
	const cellId = (index: number) => `${baseId}-cell-${index}`;
	const describedBy = [
		...(usedBy ? [usedById] : []),
		...cells.map((_, index) => cellId(index)),
		...(status ? [statusId] : []),
	].join(" ");

	return (
		<AccordionItem value={purpose} ref={rowRef} className="relative scroll-mt-24">
			{linked && <span aria-hidden className="absolute inset-y-0 left-0 w-0.5 bg-highlight" />}
			<AccordionTrigger
				headingLevel={3}
				aria-label={title}
				aria-describedby={describedBy}
				className="items-center gap-3 px-4 py-3 hover:no-underline"
			>
				<span
					className={cn(
						"grid min-w-0 flex-1 grid-cols-1 items-center gap-x-4 gap-y-2",
						rowGrid(aiChoiceRequired),
					)}
				>
					<span className="flex min-w-0 items-center gap-3">
						<ModelKindMark purpose={purpose} size="md" label="sr-only" />
						<span className="min-w-0">
							<span className="block font-medium">{title}</span>
							{usedBy && (
								<span id={usedById} className="block text-xs font-normal text-muted-foreground">
									{usedByLine(usedBy)}
								</span>
							)}
							{/* A div skeleton cannot sit in the trigger's button; an invisible line holds the
							    height the count will take, and claims nothing while the needs load. */}
							{practiceScript && practiceScriptNeeds.status === "loading" && (
								<span aria-hidden className="invisible block text-xs">
									{usedByLine([])}
								</span>
							)}
						</span>
					</span>
					{cells.map((cell, index) => (
						<span key={cell.column.label} className="min-w-0">
							<TierCellView id={cellId(index)} cell={cell} />
						</span>
					))}
					{/* Last below `md` too, so the badge never squeezes the title beside it. */}
					<span className="flex empty:hidden md:justify-end">
						{status && (
							<>
								<StatusBadge def={PURPOSE_STATUS_DEFS[status]} aria-hidden />
								<span id={statusId} className="sr-only">
									{PURPOSE_STATUS_DEFS[status].label}
								</span>
							</>
						)}
					</span>
				</span>
			</AccordionTrigger>
			{/* Gaps rather than margins: the panel's own paragraph margin would reach every form below. */}
			<AccordionContent className="flex flex-col gap-6 px-4 pt-2 pb-6 [&_p:not(:last-child)]:mb-0">
				<p className="max-w-2xl text-muted-foreground">{description}</p>
				{unmet.length > 0 && (
					// Static content must not announce itself as new when an accordion opens.
					<Alert variant={UNMET_NEED_TONE} role="note">
						<UnmetNeedIcon aria-hidden />
						<AlertDescription>{unmetNeedSentence(purpose, unmet)}</AlertDescription>
					</Alert>
				)}
				{usedBy !== undefined && usedBy.length > 0 && (
					<UsedByList usedBy={usedBy} workspaceSlug={workspaceSlug} />
				)}
				<div className="divide-y border-t">
					{DATA_HANDLING_TIERS.map((tier) => {
						const target: BindingTarget = { purpose, tier };
						const key = bindingTargetKey(target);
						const binding = bindings.find((candidate) => candidate.dataHandlingTier === tier);
						// Keep an unused assignment editable so the admin can clear it.
						if (tier === "UNDECLARED" && aiChoiceRequired && binding === undefined) {
							return null;
						}
						return (
							<AgentBindingEditor
								key={`${key}:${saveRevisions?.[key] ?? 0}`}
								target={target}
								binding={binding}
								availableModels={servingModels}
								aiChoiceRequired={aiChoiceRequired}
								ownProviderAllowed={ownProviderAllowed}
								practiceReviewsOff={off}
								unusedForCloudMembers={
									practiceScript &&
									tier === "CLOUD" &&
									binding !== undefined &&
									servesNoneOfItsTier(binding)
								}
								pendingWrite={pendingWrites.get(key)}
								saveError={saveErrors?.[key]}
								onSave={onSave}
								onTurnOff={onTurnOff}
							/>
						);
					})}
				</div>
			</AccordionContent>
		</AccordionItem>
	);
}

function unmetNeedSentence(purpose: AgentPurpose, unmet: UsedBy[]): string {
	const { withArticle } = AGENT_PURPOSE_DEFS[purpose];
	const members = tierMembersPhrase(unmet[0]?.unmetTiers ?? []);
	const needs =
		unmet.length === 1
			? `1 practice needs ${withArticle} for ${members}.`
			: `${unmet.length} practices need ${withArticle} for ${members}.`;
	const consequence =
		unmet.length === 1
			? "Until you assign one, reviews of their work record no observation for this practice."
			: "Until you assign one, reviews of their work record no observation for these practices.";
	return `${needs} ${consequence}`;
}

function UsedByList({ usedBy, workspaceSlug }: { usedBy: UsedBy[]; workspaceSlug: string }) {
	const headingId = useId();
	const [showAll, setShowAll] = useState(false);
	const shown = showAll ? usedBy : usedBy.slice(0, USED_BY_PREVIEW);
	return (
		<div className="space-y-1">
			<h4 id={headingId} className="text-sm font-medium">
				Used by
			</h4>
			<ul aria-labelledby={headingId} className="divide-y">
				{shown.map((entry) => {
					const badge = practiceScriptNeedBadge(entry.need);
					return (
						<li key={entry.practiceSlug} className="space-y-0.5 py-2">
							{/* The badge sits beside the name it qualifies, not at the far edge of the row. */}
							<span className="flex flex-wrap items-center gap-2">
								<InlineLink
									className="min-w-0 break-words"
									render={
										<Link
											to="/w/$workspaceSlug/admin/practices"
											params={{ workspaceSlug }}
											search={detailSearch(practiceSetupLevel(entry.practiceSlug))}
										/>
									}
								>
									{entry.practiceName}
								</InlineLink>
								{badge && <StatusBadge def={badge} />}
							</span>
							{unmetRequired(entry) && <UnmetTiers tiers={entry.unmetTiers} />}
						</li>
					);
				})}
			</ul>
			<div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1">
				<p className="text-xs text-muted-foreground">As of each practice’s newest review.</p>
				{usedBy.length > USED_BY_PREVIEW && !showAll && (
					<Button variant="link" size="inline" onClick={() => setShowAll(true)}>
						Show all {usedBy.length}
					</Button>
				)}
			</div>
		</div>
	);
}
