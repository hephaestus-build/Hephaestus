import { Link } from "@tanstack/react-router";
import { cn } from "cn";
import { BrainCircuit, CircleHelpIcon } from "lucide-react";
import { type ReactElement, type ReactNode, useId, useState } from "react";
import { DocLink } from "@/components/common/DocLink";
import { definitionFor, type StatusDef } from "@/components/common/status-def";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

import type {
	AgentBinding,
	AgentBindingRequest,
	AvailableLlmModel,
	WorkspaceLlmUsageReport,
} from "@/api/types.gen";
import { memberTiers } from "@/components/admin/practices/review/review-readiness";
import { purseCap } from "@/components/admin/usage/usage-utils";
import type { LoadState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";

import { StatusBadge } from "@/components/common/StatusBadge";
import { AiMark } from "@/components/icons/AiMark";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import {
	UNKNOWN_AGENT_PURPOSE_DEF,
	AGENT_PURPOSE_DEFS,
	AGENT_PURPOSES,
	type AgentPurpose,
	isAgentPurpose,
} from "@/components/practice-vocabulary/agent-purpose-defs";
import {
	type DataHandlingTier,
	DATA_HANDLING_TIERS,
	MEMBER_AI_CHOICE_DEFS,
} from "@/components/practice-vocabulary/data-handling-defs";
import { DataHandlingMark } from "@/components/practice-vocabulary/DataHandlingMark";
import { ModelKindMark } from "@/components/practice-vocabulary/ModelKindMark";
import {
	PURPOSE_STATUS_DEFS,
	type PurposeStatus,
	purposeStatus,
} from "@/components/practice-vocabulary/purpose-status-defs";
import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

import { AgentBindingEditor, modelOf } from "./AgentBindingEditor";
import { BudgetExhaustedAlert } from "./BudgetExhaustedAlert";

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
	purposes: AGENT_PURPOSES,
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
	const tiers = memberTiers(aiChoiceRequired);
	return TIER_COLUMNS.filter((column) => tiers.includes(column.tier));
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

export interface AgentBindingsPageProps {
	workspaceSlug: string;
	state: LoadState;
	bindings: AgentBinding[];
	availableModels: AvailableLlmModel[];
	practicesEnabled: boolean;
	/** Once a workspace requires the choice, the undeclared row serves nobody. */
	aiChoiceRequired: boolean;
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
				description={
					<>
						Choose the models that run practice reviews and conversations.{" "}
						<DocLink doc="membersAiChoices">
							How a member’s AI choice limits which model serves them
						</DocLink>
						.
					</>
				}
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
						{providerPanel}
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
		<div aria-busy="true">
			<Section title={REVIEWS_GROUP.title} description={REVIEWS_GROUP.rule}>
				<Card flush>
					<TierHeader aiChoiceRequired={aiChoiceRequired} />
					<div className="divide-y">
						{REVIEWS_GROUP.purposes.map((purpose) => (
							<div key={purpose} className="flex items-center gap-3 px-4 py-3">
								<div
									className={cn(
										"grid flex-1 grid-cols-1 items-center gap-x-4 gap-y-2",
										rowGrid(aiChoiceRequired),
									)}
								>
									<div className="flex items-center gap-3">
										<Skeleton className="size-8 shrink-0 rounded-md" />
										<Skeleton className="h-4 w-32" />
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
		</div>
	);
}

interface TierCell {
	column: TierColumn;
	binding?: AgentBinding;
	model?: AvailableLlmModel;
}

interface PurposeView {
	purpose: AgentPurpose;
	bindings: AgentBinding[];
	off: boolean;
	status: PurposeStatus | null;
	cells: TierCell[];
}

interface PurposeViewInput {
	bindings: AgentBinding[];
	availableModels: AvailableLlmModel[];
	practicesEnabled: boolean;
	aiChoiceRequired: boolean;
}

function purposeView(
	purpose: AgentPurpose,
	{ bindings, availableModels, practicesEnabled, aiChoiceRequired }: PurposeViewInput,
): PurposeView {
	const own = bindings.filter((binding) => binding.purpose === purpose);
	const off = purpose !== "MENTOR" && !practicesEnabled;
	const cells = tierColumns(aiChoiceRequired).map((column) => {
		const binding = off ? undefined : servingBinding(own, column.tier);
		return { column, binding, model: modelOf(binding, availableModels) };
	});
	return {
		purpose,
		bindings: own,
		off,
		cells,
		status: purposeStatus({
			bindings: own,
			served: cells.map((cell) => cell.binding !== undefined),
			off,
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
	const views = new Map(
		REVIEWS_GROUP.purposes.map((purpose) => [purpose, purposeView(purpose, props)]),
	);
	// Decide initial expansion once so saving does not move the admin's place.
	const [open, setOpen] = useState<AgentPurpose[]>(() =>
		REVIEWS_GROUP.purposes.filter((purpose) => {
			const view = views.get(purpose);
			return view !== undefined && opensAtMount(view);
		}),
	);
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

	return (
		<PurposeGroupSection
			group={REVIEWS_GROUP}
			description={
				<>
					{REVIEWS_GROUP.rule}
					{off && <> Practice reviews are off, so their model is not used. {reviewsOffLink}</>}
				</>
			}
			views={views}
			openValue={openValue}
			onOpenChange={onOpenChange}
			focusPurpose={focusPurpose}
			rowProps={props}
		/>
	);
}

type RowProps = Omit<PurposeGroupsProps, "workspaceSlug" | "focusPurpose" | "onFocusPurposeClosed">;

interface PurposeGroupSectionProps {
	group: PurposeGroup;
	description: ReactNode;
	views: ReadonlyMap<AgentPurpose, PurposeView>;
	openValue: AgentPurpose[];
	onOpenChange: (next: AgentPurpose[]) => void;
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
	views,
	openValue,
	onOpenChange,
	focusPurpose,
	rowProps,
}: PurposeGroupSectionProps) {
	return (
		<Section title={group.title} description={description}>
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

// Screen readers need the tier label because table headers may not be read with cells.
function TierCellView({ cell }: { cell: TierCell }) {
	const { column, binding, model } = cell;
	const ColumnIcon = column.icon;
	const fallback = column.tier === "CLOUD" && binding?.dataHandlingTier === "IN_HOUSE";
	return (
		<span className="flex min-w-0 items-center gap-2 text-sm font-normal">
			{/* Below `md` the header is gone, so each cell names its column with the icon. */}
			<ColumnIcon className="size-3.5 shrink-0 text-muted-foreground md:hidden" aria-hidden />
			<span className="sr-only">{column.label}: </span>
			{binding !== undefined && (
				<>
					<CellModel model={model} />
					{fallback && <DataHandlingMark tier="IN_HOUSE" />}
				</>
			)}
			{binding === undefined && (
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
	linked: boolean;
	rowRef?: (node: HTMLElement | null) => void;
}

function PurposeRow({
	view,
	linked,
	rowRef,
	availableModels,
	aiChoiceRequired,
	ownProviderAllowed,
	pendingWrites,
	saveRevisions,
	saveErrors,
	onSave,
	onTurnOff,
}: PurposeRowProps) {
	const titleId = useId();
	const { purpose, bindings, off, status, cells } = view;
	const { title, description } = definitionFor(
		AGENT_PURPOSE_DEFS,
		purpose,
		UNKNOWN_AGENT_PURPOSE_DEF,
	);
	const servingModels = availableModels.filter((model) => model.purposes.includes(purpose));
	return (
		<AccordionItem value={purpose} ref={rowRef} className="relative scroll-mt-24">
			{linked && <span aria-hidden className="absolute inset-y-0 left-0 w-0.5 bg-highlight" />}
			<AccordionTrigger
				headingLevel={3}
				className="items-center gap-3 px-4 py-3 hover:no-underline"
			>
				<span
					className={cn(
						"grid min-w-0 flex-1 grid-cols-1 items-center gap-x-4 gap-y-2",
						rowGrid(aiChoiceRequired),
					)}
				>
					<span className="flex min-w-0 items-center gap-3">
						<ModelKindMark purpose={purpose} size="md" label="none" />
						<span id={titleId} className="min-w-0 font-medium">
							{title}
						</span>
					</span>
					{cells.map((cell) => (
						<span key={cell.column.label} className="min-w-0">
							<TierCellView cell={cell} />
						</span>
					))}
					{/* Last below `md` too, so the badge never squeezes the title beside it. */}
					<span className="flex empty:hidden md:justify-end">
						{status && <StatusBadge def={PURPOSE_STATUS_DEFS[status]} />}
					</span>
				</span>
			</AccordionTrigger>
			{/* Gaps rather than margins: the panel's own paragraph margin would reach every form below. */}
			<AccordionContent
				aria-labelledby={titleId}
				className="flex flex-col gap-6 px-4 pt-2 pb-6 [&_p:not(:last-child)]:mb-0"
			>
				<p className="max-w-2xl text-muted-foreground">{description}</p>
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
