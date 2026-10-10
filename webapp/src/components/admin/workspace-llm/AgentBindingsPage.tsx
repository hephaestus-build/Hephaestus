import { Link } from "@tanstack/react-router";
import { cn } from "cn";
import { BrainCircuit, ChevronDown, CircleHelpIcon, InfoIcon, type LucideIcon } from "lucide-react";
import {
	type ReactElement,
	type ReactNode,
	type SubmitEvent,
	useEffect,
	useId,
	useRef,
	useState,
} from "react";

import type {
	AgentBinding,
	AgentBindingRequest,
	AvailableLlmModel,
	PracticePrecomputeSummary,
	PrecomputeNeed,
	WorkspaceLlmUsageReport,
} from "@/api/types.gen";
import { practiceSetupLevel } from "@/components/admin/practices/practice-search";
import { purseCap } from "@/components/admin/usage/usage-utils";
import { FOCUS_RING } from "@/components/common/focus";
import { InlineLink } from "@/components/common/InlineLink";
import type { LoadState, PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
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
	isPrecomputePurpose,
	PRECOMPUTE_PURPOSES,
} from "@/components/practice-vocabulary/agent-purpose-defs";
import {
	BINDING_READINESS_DEFS,
	type BindingReadiness,
	bindingReadiness,
} from "@/components/practice-vocabulary/binding-readiness-defs";
import {
	DATA_HANDLING_DEFS,
	type DataHandlingTier,
	DATA_HANDLING_TIERS,
	MEMBER_AI_CHOICE_DEFS,
	tierMembersPhrase,
	UNCHOSEN_MEMBERS,
} from "@/components/practice-vocabulary/data-handling-defs";
import { DataHandlingMark } from "@/components/practice-vocabulary/DataHandlingMark";
import { ModelKindMark } from "@/components/practice-vocabulary/ModelKindMark";
import { precomputeNeedBadge } from "@/components/practice-vocabulary/precompute-need-defs";
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
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
	Field,
	FieldDescription,
	FieldError,
	FieldGroup,
	FieldLabel,
	FieldLegend,
	FieldSet,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { capitalise, hasText } from "@/lib/text";

import { BudgetExhaustedAlert } from "./BudgetExhaustedAlert";
import { ModelPicker, type ModelSelection, PROVIDER_PANEL_ID } from "./ModelPicker";

/** One tier editor on the page: a purpose's assignment for one data-handling tier. */
export interface BindingTarget {
	purpose: AgentPurpose;
	tier: DataHandlingTier;
}

export function bindingTargetKey({ purpose, tier }: BindingTarget): string {
	return `${purpose}:${tier}`;
}

/** Which write a row is waiting on, so its own button can say so. */
export type BindingWrite = "SAVE" | "CLEAR";

interface PurposeGroup {
	title: string;
	/** The group's rule, the first sentence of its description. */
	rule: string;
	purposes: AgentPurpose[];
}

const REVIEWS_GROUP: PurposeGroup = {
	title: "Reviews and Heph",
	rule: "A member who chooses Cloud gets the Cloud assignment when it is ready, and the In-house one when it is not. A member who chooses In-house gets only the In-house assignment.",
	purposes: AGENT_PURPOSES.filter((purpose) => !isPrecomputePurpose(purpose)),
};

const PRECOMPUTE_GROUP: PurposeGroup = {
	title: "Models for precompute scripts",
	rule: "A practice’s precompute script runs before its review and points it at places to check. A script uses only models at least as strict as the review’s own model.",
	purposes: PRECOMPUTE_PURPOSES,
};

/** One column of a purpose row: the members who gave one answer, and which model serves them. */
interface TierColumn {
	/**
	 * The tier under which the server reports these members as served: their AI choice's ceiling,
	 * or `UNDECLARED` for the members who have not chosen.
	 */
	tier: DataHandlingTier;
	label: string;
	icon: LucideIcon;
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

/** The column for members who have not chosen exists only while choosing is optional. */
function tierColumns(aiChoiceRequired: boolean): readonly TierColumn[] {
	return aiChoiceRequired
		? TIER_COLUMNS.filter((column) => column.tier !== "UNDECLARED")
		: TIER_COLUMNS;
}

/**
 * One template for the header and every row of a card, so the tier columns line up. The status
 * column fits the longest status label.
 */
const ROW_GRID = {
	2: "md:grid-cols-[minmax(11rem,1fr)_repeat(2,minmax(0,1fr))_12rem]",
	3: "md:grid-cols-[minmax(11rem,1fr)_repeat(3,minmax(0,1fr))_12rem]",
};

function rowGrid(aiChoiceRequired: boolean): string {
	return ROW_GRID[aiChoiceRequired ? 2 : 3];
}

/**
 * The binding that serves one column's members, as the server routed it. Routing is the server's
 * rule, sent as each binding's `servedTiers`, so the page never derives it.
 */
function servingBinding(
	own: readonly AgentBinding[],
	tier: DataHandlingTier,
): AgentBinding | undefined {
	return own.find((binding) => binding.servedTiers.includes(tier));
}

/**
 * A precompute assignment that could run but serves none of its own tier's members: the server
 * serves a script no looser than the review model of the same members.
 */
function servesNoneOfItsTier(binding: AgentBinding): boolean {
	return (
		binding.enabled && binding.ready && !binding.servedTiers.includes(binding.dataHandlingTier)
	);
}

/** A long list of practices folds after this many, behind "Show all". */
const USED_BY_PREVIEW = 5;

const UNCHOSEN_ROW_TITLE = capitalise(UNCHOSEN_MEMBERS);

const MIN_TIMEOUT_SECONDS = 30;
const MAX_TIMEOUT_SECONDS = 10_800;
const MIN_CONCURRENT_JOBS = 1;

const TIMEOUT_CEILING = {
	max: MAX_TIMEOUT_SECONDS,
	error: `Runs stop after three hours, so enter ${MAX_TIMEOUT_SECONDS} seconds or less.`,
};

function bindingToSelection(binding?: AgentBinding): ModelSelection | null {
	if (binding?.instanceModelId != null) {
		return { scope: "SHARED", id: binding.instanceModelId };
	}
	if (binding?.workspaceModelId != null) {
		return { scope: "WORKSPACE", id: binding.workspaceModelId };
	}
	return null;
}

function modelOf(
	binding: AgentBinding | undefined,
	models: AvailableLlmModel[],
): AvailableLlmModel | undefined {
	const selection = bindingToSelection(binding);
	return models.find((m) => m.scope === selection?.scope && m.id === selection.id);
}

interface ParsedNumber {
	value: number | null;
	error: string | null;
}

function parseWholeNumber(
	raw: string,
	min: number,
	unit: string,
	ceiling?: { max: number; error: string },
): ParsedNumber {
	const trimmed = raw.trim();
	if (trimmed === "") {
		return { value: null, error: `Enter a number of ${unit}.` };
	}
	const parsed = Number(trimmed);
	if (!Number.isInteger(parsed) || parsed < min) {
		return { value: null, error: `Enter a whole number of ${unit}, ${min} or more.` };
	}
	if (ceiling && parsed > ceiling.max) {
		return { value: null, error: ceiling.error };
	}
	return { value: parsed, error: null };
}

export type PrecomputeSummariesState = PanelState<{ summaries: PracticePrecomputeSummary[] }>;

export interface AgentBindingsPageProps {
	workspaceSlug: string;
	state: LoadState;
	bindings: AgentBinding[];
	availableModels: AvailableLlmModel[];
	practicesEnabled: boolean;
	/** Once a workspace requires the choice, the undeclared row serves nobody. */
	aiChoiceRequired: boolean;
	/**
	 * What each practice's precompute script declared in its newest review. Its own state: the rest
	 * of the page works without it, so its failure shows only in the precompute section.
	 */
	precomputeNeeds: PrecomputeSummariesState;
	/**
	 * Whether the instance lets this workspace add its own providers. False, a hint that would send
	 * the admin to add one names who can share a model instead.
	 */
	ownProviderAllowed: boolean;
	/** The workspace's own providers, below the models. A picker with nothing to offer links to it. */
	providerPanel?: ReactElement;
	usage?: WorkspaceLlmUsageReport;
	/** The purpose a link asked for: its row opens and scrolls into view. */
	focusPurpose?: AgentPurpose;
	/** The reader closed the row a link opened, so the link can open it again. */
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
	...ready
}: AgentBindingsPageProps) {
	const ownProvider = usage === undefined ? undefined : purseCap(usage, "OWN_PROVIDER");
	return (
		<PageLayout>
			<PageHeader
				icon={<BrainCircuit />}
				title="AI models"
				description="Choose the models that run practice reviews and Heph."
			/>

			<div className="space-y-8">
				{usage !== undefined && (ownProvider?.paused === true || usage.instancePaused) && (
					<div className="space-y-3">
						{ownProvider?.paused === true && (
							<BudgetExhaustedAlert
								scope="own"
								verdict={ownProvider.verdict}
								unpricedEventCount={usage.unpricedEventCount}
								context="models"
								workspaceSlug={workspaceSlug}
							/>
						)}
						{usage.instancePaused && (
							<BudgetExhaustedAlert
								scope="shared"
								verdict={usage.instanceBudgetVerdict}
								unpricedEventCount={usage.unpricedEventCount}
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

/** The tier columns' names, once per card above its rows. The cells carry the same words for a screen reader. */
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

/**
 * Rows per group, so the skeleton holds the height the accordions will take. The group rules and
 * the tier columns are known before any data, so they show as they will stay.
 */
function PurposeGroupsSkeleton({ aiChoiceRequired }: { aiChoiceRequired: boolean }) {
	const columns = tierColumns(aiChoiceRequired);
	return (
		<div className="space-y-8" aria-busy="true">
			{[REVIEWS_GROUP, PRECOMPUTE_GROUP].map((group) => (
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
												{isPrecomputePurpose(purpose) && <Skeleton className="h-3 w-24" />}
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
	need: PrecomputeNeed["need"];
	unmetTiers: DataHandlingTier[];
}

/** One tier column of a row: the model its members get, if any. */
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
	 * Precompute purposes only: the practices whose scripts declared this model. Undefined for a
	 * precompute purpose until the needs are in.
	 */
	usedBy?: UsedBy[];
	/** The practices whose required model some members do not get. */
	unmet: UsedBy[];
}

/**
 * An unmet required need makes its row `NEEDS_ATTENTION`, so the note about it takes that status's
 * tone and icon: the row's badge and the note say one state.
 */
const { badgeVariant: UNMET_NEED_TONE, icon: UnmetNeedIcon } = PURPOSE_STATUS_DEFS.NEEDS_ATTENTION;

const unmetRequired = (entry: UsedBy) => entry.need === "REQUIRED" && entry.unmetTiers.length > 0;

/** Unmet first, then required, then by name: the practice to fix leads the list. */
function usedByOrder(a: UsedBy, b: UsedBy): number {
	return (
		Number(unmetRequired(b)) - Number(unmetRequired(a)) ||
		Number(b.need === "REQUIRED") - Number(a.need === "REQUIRED") ||
		a.practiceName.localeCompare(b.practiceName)
	);
}

function usedByOf(purpose: AgentPurpose, summaries: PracticePrecomputeSummary[]): UsedBy[] {
	return summaries
		.flatMap((summary) =>
			summary.needs
				.filter((need) => need.purpose === purpose)
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
	needs: PracticePrecomputeSummary[] | undefined;
}

function purposeView(
	purpose: AgentPurpose,
	{ bindings, availableModels, practicesEnabled, aiChoiceRequired, needs }: PurposeViewInput,
): PurposeView {
	const own = bindings.filter((binding) => binding.purpose === purpose);
	// Every purpose but Heph runs inside a practice review, so their switch turns it off too.
	const off = purpose !== "MENTOR" && !practicesEnabled;
	const precompute = isPrecomputePurpose(purpose);
	const usedBy = precompute && needs !== undefined ? usedByOf(purpose, needs) : undefined;
	const unmet = off ? [] : (usedBy ?? []).filter(unmetRequired);
	// While practice reviews are off no column is served, whatever the row binds.
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
			used: !precompute || (usedBy !== undefined && usedBy.length > 0),
		}),
	};
}

/** Open at mount: a row that needs the admin, or the review model nobody has assigned yet. */
function opensAtMount(view: PurposeView): boolean {
	return (
		view.status === "NEEDS_ATTENTION" ||
		(view.purpose === "PRACTICE_REVIEW" && view.status === "NOT_SET")
	);
}

type PurposeGroupsProps = { workspaceSlug: string } & Omit<
	AgentBindingsPageProps,
	"workspaceSlug" | "state" | "usage" | "providerPanel"
>;

function PurposeGroups({
	workspaceSlug,
	focusPurpose,
	onFocusPurposeClosed,
	...props
}: PurposeGroupsProps) {
	const { precomputeNeeds } = props;
	const needs = precomputeNeeds.status === "ready" ? precomputeNeeds.summaries : undefined;
	const views = new Map(
		[...REVIEWS_GROUP.purposes, ...PRECOMPUTE_GROUP.purposes].map((purpose) => [
			purpose,
			purposeView(purpose, { ...props, needs }),
		]),
	);
	const openingNow = (purposes: AgentPurpose[]) =>
		purposes.filter((purpose) => {
			const view = views.get(purpose);
			return view !== undefined && opensAtMount(view);
		});
	// Decided once: a row that opens or closes under the admin after each save would lose their place.
	const [open, setOpen] = useState<AgentPurpose[]>(() => openingNow(AGENT_PURPOSES));
	// The precompute rows are judged by the needs, which can arrive after the page, so they decide
	// once when the needs first arrive.
	const [needsJudged, setNeedsJudged] = useState(needs !== undefined);
	if (needs !== undefined && !needsJudged) {
		setNeedsJudged(true);
		setOpen((current) => [
			...current,
			...openingNow(PRECOMPUTE_GROUP.purposes).filter((purpose) => !current.includes(purpose)),
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
			search={{ section: "when-and-where" }}
			className="underline underline-offset-4"
		>
			Open Review: When and where
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
				group={PRECOMPUTE_GROUP}
				description={
					<>
						{PRECOMPUTE_GROUP.rule}
						{off && <> Practice reviews are off, so these models are not used. {reviewsOffLink}</>}
					</>
				}
				notice={
					precomputeNeeds.status === "error" && (
						<QueryErrorAlert
							error={precomputeNeeds.error}
							title="We could not load which practices use these models"
							onRetry={precomputeNeeds.onRetry}
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
	rowProps: RowProps;
}

/** A deep link lands on its row as soon as the row is drawn, and again for each new link. */
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

/**
 * The model one column's members get. The tier prefix is for a screen reader, which hears the cells
 * without the header above them. A Cloud member served by an In-house model sees that model's tier.
 */
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
	/** A link opened this row: it keeps the accent's rail on its leading edge. */
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
	precomputeNeeds,
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
	// The server names the purposes each model's protocol can serve.
	const servingModels = availableModels.filter((model) => model.purposes.includes(purpose));
	const precompute = isPrecomputePurpose(purpose);
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
			{linked && <span aria-hidden className="absolute inset-y-0 left-0 w-0.5 bg-mentor" />}
			<AccordionTrigger
				headingLevel={3}
				// The title alone names the row and the region it opens; the cells describe it.
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
							{precompute && precomputeNeeds.status === "loading" && (
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
					// Static page content, so no live role: opening the row must not announce it as news.
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
						// Once members must choose, the editor for members who have not chosen serves
						// no one; it stays only while it still holds an assignment to clear.
						if (tier === "UNDECLARED" && aiChoiceRequired && binding === undefined) {
							return null;
						}
						return (
							<BindingRow
								key={`${key}:${saveRevisions?.[key] ?? 0}`}
								target={target}
								binding={binding}
								availableModels={servingModels}
								aiChoiceRequired={aiChoiceRequired}
								ownProviderAllowed={ownProviderAllowed}
								practiceReviewsOff={off}
								unusedForCloudMembers={
									precompute &&
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

/**
 * How many practices miss a required model, and for which members: "2 practices need a decision
 * model for In-house members." The server judges the members once per model, so every practice in
 * `unmet` misses the same ones. Their names are in *Used by* below.
 */
function unmetNeedSentence(purpose: AgentPurpose, unmet: UsedBy[]): string {
	const { withArticle } = AGENT_PURPOSE_DEFS[purpose];
	const members = tierMembersPhrase(unmet[0]?.unmetTiers ?? []);
	const needs =
		unmet.length === 1
			? `1 practice needs ${withArticle} for ${members}.`
			: `${unmet.length} practices need ${withArticle} for ${members}.`;
	const consequence =
		unmet.length === 1
			? "Until you assign one, its precompute script does not run, and the review checks the practice without its help."
			: "Until you assign one, their precompute scripts do not run, and the reviews check those practices without their help.";
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
					const badge = precomputeNeedBadge(entry.need);
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

/** The ids of the descriptions on screen, for `aria-describedby`; undefined when none is. */
function idsShown(entries: readonly (readonly [id: string, shown: boolean])[]): string | undefined {
	const ids = entries.filter(([, shown]) => shown).map(([id]) => id);
	return ids.length > 0 ? ids.join(" ") : undefined;
}

/**
 * A refusal is about the model it refused, so it shows only while that model is still the choice:
 * picking another one takes it away, and the next save asks again.
 */
function refusalOf(
	saveError: string | undefined,
	submitted: ModelSelection | null,
	selection: ModelSelection | null,
): string | undefined {
	const sameModel =
		submitted !== null &&
		selection !== null &&
		submitted.scope === selection.scope &&
		submitted.id === selection.id;
	return sameModel ? saveError : undefined;
}

interface BindingRowProps {
	target: BindingTarget;
	binding?: AgentBinding;
	availableModels: AvailableLlmModel[];
	aiChoiceRequired: boolean;
	ownProviderAllowed: boolean;
	/** While practice reviews are off no row serves anyone, so none claims to be ready. */
	practiceReviewsOff: boolean;
	/**
	 * A precompute Cloud assignment that Cloud members do not get: their reviews run on an In-house
	 * model, and a script model is never looser than its review's.
	 */
	unusedForCloudMembers: boolean;
	pendingWrite?: BindingWrite;
	saveError?: string;
	onSave: (target: BindingTarget, body: AgentBindingRequest) => void;
	onTurnOff: (target: BindingTarget) => void;
}

function BindingRow({
	target,
	binding,
	availableModels,
	aiChoiceRequired,
	ownProviderAllowed,
	practiceReviewsOff,
	unusedForCloudMembers,
	pendingWrite,
	saveError,
	onSave,
	onTurnOff,
}: BindingRowProps) {
	const pending = pendingWrite !== undefined;
	const { purpose, tier } = target;
	const rowId = useId();
	const headingId = `${rowId}-heading`;
	const formRef = useRef<HTMLFormElement>(null);

	const undeclared = tier === "UNDECLARED";
	const tierLabel = DATA_HANDLING_DEFS[tier].label;
	const rowTitle = undeclared ? UNCHOSEN_ROW_TITLE : tierLabel;
	// The undeclared row takes any model; a declared row holds only models of its own tier.
	const pickerTier = undeclared ? undefined : tier;
	const noModels = !availableModels.some(
		(model) => pickerTier === undefined || model.dataHandlingTier === pickerTier,
	);
	// With no model of a precompute kind at all, the picker itself says what to connect.
	const pickerExplainsEmpty = isPrecomputePurpose(purpose) && availableModels.length === 0;
	const boundModel = modelOf(binding, availableModels);
	// A bound model whose facts were re-declared has dropped out of this row: the picker still names
	// it on the trigger, and the row says why it stopped serving.
	const boundModelMoved =
		boundModel !== undefined && pickerTier !== undefined && boundModel.dataHandlingTier !== tier;
	const hintShown = boundModelMoved || (noModels && !pickerExplainsEmpty);

	const [selection, setSelection] = useState<ModelSelection | null>(bindingToSelection(binding));
	const [submitted, setSubmitted] = useState<ModelSelection | null>(null);
	const refusal = refusalOf(saveError, submitted, selection);
	const [enabled, setEnabled] = useState(binding?.enabled ?? true);
	const [timeoutSeconds, setTimeoutSeconds] = useState(() =>
		String(binding?.timeoutSeconds ?? MAX_TIMEOUT_SECONDS),
	);
	const [maxConcurrentJobs, setMaxConcurrentJobs] = useState(() =>
		String(binding?.maxConcurrentJobs ?? 3),
	);
	const [allowInternet, setAllowInternet] = useState(binding?.allowInternet ?? false);
	const [showAdvanced, setShowAdvanced] = useState(false);
	// Only Heph may reach the internet; the server rejects the flag for every other purpose.
	const internetConfigurable = purpose === "MENTOR";
	// A precompute model is called inside a practice review, under that review's limits.
	const hasRunLimits = purpose === "PRACTICE_REVIEW" || purpose === "MENTOR";
	const [submitAttempt, setSubmitAttempt] = useState(0);

	const showErrors = submitAttempt > 0;

	const timeout = parseWholeNumber(timeoutSeconds, MIN_TIMEOUT_SECONDS, "seconds", TIMEOUT_CEILING);
	const concurrency = parseWholeNumber(maxConcurrentJobs, MIN_CONCURRENT_JOBS, "runs");
	const modelError = showErrors && !selection ? "Choose the model this runs on." : null;
	const timeoutError = showErrors ? timeout.error : null;
	const concurrencyError = showErrors ? concurrency.error : null;

	const modelId = `${rowId}-model`;
	const modelLabelId = `${rowId}-model-label`;
	const modelHintId = `${rowId}-model-hint`;
	const modelErrorId = `${rowId}-model-error`;
	const noteId = `${rowId}-note`;
	const saveErrorId = `${rowId}-save-error`;
	const timeoutErrorId = `${rowId}-timeout-error`;
	const concurrencyErrorId = `${rowId}-concurrency-error`;
	const modelDescribedBy = idsShown([
		[noteId, unusedForCloudMembers],
		[modelHintId, hintShown],
		[modelErrorId, modelError !== null],
		[saveErrorId, hasText(refusal)],
	]);

	useEffect(() => {
		if (submitAttempt > 0) {
			formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
		}
	}, [submitAttempt]);

	const handleSubmit = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		if (!selection || timeout.value == null || concurrency.value == null) {
			setSubmitAttempt((attempt) => attempt + 1);
			if (timeout.value == null || concurrency.value == null) {
				setShowAdvanced(true);
			}
			return;
		}
		setSubmitted(selection);
		onSave(target, {
			instanceModelId: selection.scope === "SHARED" ? selection.id : undefined,
			workspaceModelId: selection.scope === "WORKSPACE" ? selection.id : undefined,
			timeoutSeconds: timeout.value,
			maxConcurrentJobs: concurrency.value,
			allowInternet: internetConfigurable && allowInternet,
			enabled,
		});
	};

	// Every row repeats the same controls, so each control names its row for a screen reader.
	const forRow = <span className="sr-only"> for {rowTitle}</span>;

	return (
		<div role="group" aria-labelledby={headingId} className="space-y-3 py-4 last:pb-0">
			<TierEditorHeading
				id={headingId}
				tier={tier}
				aiChoiceRequired={aiChoiceRequired}
				readiness={binding && !practiceReviewsOff ? bindingReadiness(binding) : null}
			/>
			{unusedForCloudMembers && (
				<p id={noteId} className="flex gap-2 text-sm text-muted-foreground">
					<InfoIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
					Cloud members’ reviews run on an In-house model, so this model is not used. Assign a Cloud
					model for practice reviews to use it.
				</p>
			)}
			<form ref={formRef} onSubmit={handleSubmit} noValidate className="space-y-2">
				{/* Picker, switch and buttons on one line from `md`; the switch and buttons sit level
				    with the picker, below its label, whatever hint grows under it. */}
				<div className="grid items-start gap-x-6 gap-y-4 md:grid-cols-[minmax(0,32rem)_auto_1fr]">
					<Field data-invalid={Boolean(modelError ?? refusal)}>
						<FieldLabel id={modelLabelId} htmlFor={modelId}>
							Model{forRow}
						</FieldLabel>
						<ModelPicker
							id={modelId}
							aria-labelledby={modelLabelId}
							availableModels={availableModels}
							tier={pickerTier}
							purpose={purpose}
							ownProviderAllowed={ownProviderAllowed}
							value={selection}
							onChange={setSelection}
							disabled={pending || noModels}
							invalid={Boolean(modelError ?? refusal)}
							aria-describedby={modelDescribedBy}
						/>
						{hintShown && (
							<ModelHint
								id={modelHintId}
								movedModel={boundModelMoved ? boundModel : undefined}
								noModels={noModels}
								undeclared={undeclared}
								tierLabel={tierLabel}
								ownProviderAllowed={ownProviderAllowed}
							/>
						)}
						{modelError && <FieldError id={modelErrorId}>{modelError}</FieldError>}
						{hasText(refusal) && <FieldError id={saveErrorId}>{refusal}</FieldError>}
					</Field>

					<Field orientation="horizontal" className="w-auto md:mt-7 md:h-8">
						<FieldLabel htmlFor={`${rowId}-enabled`}>Use this model{forRow}</FieldLabel>
						<Switch
							id={`${rowId}-enabled`}
							checked={selection != null && enabled}
							onCheckedChange={setEnabled}
							disabled={pending || selection == null}
						/>
					</Field>

					<RowActions
						bound={binding !== undefined}
						pendingWrite={pendingWrite}
						forRow={forRow}
						onClear={() => onTurnOff(target)}
					/>
				</div>

				{hasRunLimits && (
					<Collapsible open={showAdvanced} onOpenChange={setShowAdvanced}>
						<CollapsibleTrigger
							render={
								<Button type="button" variant="ghost" size="sm" className="group/adv -ml-2">
									Advanced{forRow}
									<ChevronDown
										className="transition-transform group-aria-expanded/adv:rotate-180"
										aria-hidden
									/>
								</Button>
							}
						/>
						<CollapsibleContent>
							<FieldSet className="pt-2 pb-2">
								<FieldLegend variant="label">Run limits</FieldLegend>
								<FieldGroup>
									<Field data-invalid={Boolean(timeoutError)}>
										<FieldLabel htmlFor={`${rowId}-timeout`}>Timeout (seconds){forRow}</FieldLabel>
										<Input
											id={`${rowId}-timeout`}
											type="number"
											inputMode="numeric"
											min={MIN_TIMEOUT_SECONDS}
											max={MAX_TIMEOUT_SECONDS}
											value={timeoutSeconds}
											aria-invalid={Boolean(timeoutError)}
											aria-describedby={hasText(timeoutError) ? timeoutErrorId : undefined}
											onChange={(e) => setTimeoutSeconds(e.target.value)}
											disabled={pending}
										/>
										{hasText(timeoutError) && (
											<FieldError id={timeoutErrorId}>{timeoutError}</FieldError>
										)}
									</Field>
									{purpose === "PRACTICE_REVIEW" && (
										<Field data-invalid={Boolean(concurrencyError)}>
											<FieldLabel htmlFor={`${rowId}-concurrency`}>
												Max concurrent runs{forRow}
											</FieldLabel>
											<Input
												id={`${rowId}-concurrency`}
												type="number"
												inputMode="numeric"
												min={MIN_CONCURRENT_JOBS}
												value={maxConcurrentJobs}
												aria-invalid={Boolean(concurrencyError)}
												aria-describedby={
													hasText(concurrencyError) ? concurrencyErrorId : undefined
												}
												onChange={(e) => setMaxConcurrentJobs(e.target.value)}
												disabled={pending}
											/>
											{hasText(concurrencyError) && (
												<FieldError id={concurrencyErrorId}>{concurrencyError}</FieldError>
											)}
										</Field>
									)}
									{internetConfigurable && (
										<Field orientation="horizontal">
											<FieldLabel htmlFor={`${rowId}-internet`}>Internet access{forRow}</FieldLabel>
											<Switch
												id={`${rowId}-internet`}
												checked={allowInternet}
												onCheckedChange={setAllowInternet}
												disabled={pending}
											/>
										</Field>
									)}
								</FieldGroup>
							</FieldSet>
						</CollapsibleContent>
					</Collapsible>
				)}
			</form>
		</div>
	);
}

interface TierEditorHeadingProps {
	id: string;
	tier: DataHandlingTier;
	aiChoiceRequired: boolean;
	readiness: BindingReadiness | null;
}

/**
 * A declared row wears its tier's icon and words, the same ones a developer's card shows. The row
 * for members who have not chosen names those members only: *Not declared* is a model's tier, not
 * an answer a member gave.
 */
function TierEditorHeading({ id, tier, aiChoiceRequired, readiness }: TierEditorHeadingProps) {
	const undeclared = tier === "UNDECLARED";
	const { label, icon: TierIcon } = DATA_HANDLING_DEFS[tier];
	return (
		<div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
			<div className="min-w-0 flex-1 space-y-0.5">
				<h4 id={id} className="flex items-center gap-2 text-sm font-medium">
					<TierIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
					{undeclared ? UNCHOSEN_ROW_TITLE : label}
				</h4>
				<p className="text-sm text-muted-foreground">
					{bindingAudience(undeclared, aiChoiceRequired, label)}
				</p>
			</div>
			{readiness && <StatusBadge def={BINDING_READINESS_DEFS[readiness]} />}
		</div>
	);
}

interface ModelHintProps {
	id: string;
	/** A bound model whose declared tier no longer fits the row. */
	movedModel?: AvailableLlmModel;
	noModels: boolean;
	undeclared: boolean;
	tierLabel: string;
	/** False, only an instance admin can add a model, so the hint sends the admin to one. */
	ownProviderAllowed: boolean;
}

/** Why the row's picker cannot offer what the row needs, and who can do something about it. */
function ModelHint({
	id,
	movedModel,
	noModels,
	undeclared,
	tierLabel,
	ownProviderAllowed,
}: ModelHintProps) {
	if (movedModel) {
		return (
			<FieldDescription id={id}>
				{movedModel.displayName} is now declared as{" "}
				<span className="font-medium">{DATA_HANDLING_DEFS[movedModel.dataHandlingTier].label}</span>{" "}
				and no longer serves this assignment.{" "}
				{noModels ? (
					<>
						Clear the assignment, or ask an instance admin for a model declared as{" "}
						<span className="font-medium">{tierLabel}</span>.
					</>
				) : (
					"Choose another model, or clear the assignment."
				)}
			</FieldDescription>
		);
	}
	if (undeclared) {
		return (
			<FieldDescription id={id}>
				No model for this purpose is available yet.{" "}
				{ownProviderAllowed
					? "Ask an instance admin to share one, or connect a provider under Your providers."
					: "Ask an instance admin to share one."}
			</FieldDescription>
		);
	}
	return (
		<FieldDescription id={id}>
			No model for this purpose declared as <span className="font-medium">{tierLabel}</span> is
			available yet.{" "}
			{ownProviderAllowed
				? "Ask an instance admin, or add one to a provider under Your providers."
				: "Ask an instance admin to share one."}
		</FieldDescription>
	);
}

interface RowActionsProps {
	bound: boolean;
	pendingWrite?: BindingWrite;
	/** The row's name for a screen reader, since every row repeats the same buttons. */
	forRow: ReactNode;
	onClear: () => void;
}

/** The pending write's own button says what it waits on; both stay frozen until it lands. */
function RowActions({ bound, pendingWrite, forRow, onClear }: RowActionsProps) {
	const pending = pendingWrite !== undefined;
	return (
		<div className="flex flex-wrap items-center justify-end gap-2 md:mt-7 md:h-8">
			{bound && (
				<Button type="button" variant="outline" size="sm" onClick={onClear} disabled={pending}>
					{pendingWrite === "CLEAR" ? (
						<>
							<Spinner />
							Clearing…
						</>
					) : (
						"Clear assignment"
					)}
					{forRow}
				</Button>
			)}
			<Button type="submit" size="sm" disabled={pending}>
				{pendingWrite === "SAVE" ? (
					<>
						<Spinner />
						Saving…
					</>
				) : (
					"Save assignment"
				)}
				{forRow}
			</Button>
		</div>
	);
}

function bindingAudience(undeclared: boolean, required: boolean, tierLabel: string): string {
	if (!undeclared) {
		return `For members whose AI choice allows ${tierLabel}.`;
	}
	if (required) {
		return "For members who have not chosen yet, when choosing is optional. Every member here must choose, so no one uses it now.";
	}
	return "For members who have not chosen yet, when choosing is optional. A member who has chosen never uses it.";
}
