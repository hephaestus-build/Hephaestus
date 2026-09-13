import { Link } from "@tanstack/react-router";
import { BrainCircuit, ChevronDown, CircleHelpIcon } from "lucide-react";
import { type ReactNode, type SubmitEvent, useEffect, useId, useRef, useState } from "react";

import { cn } from "cn";
import type {
	AgentBinding,
	AgentBindingRequest,
	AvailableLlmModel,
	WorkspaceLlmUsageReport,
} from "@/api/types.gen";
import { type Fact, FactList } from "@/components/auth/FactList";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import {
	BINDING_READINESS_DEFS,
	bindingReadiness,
} from "@/components/practice-vocabulary/binding-readiness-defs";
import {
	bindingFor,
	DATA_HANDLING_DEFS,
	DATA_HANDLING_TIERS,
	type DataHandlingTier,
	MEMBER_AI_CHOICE_DEFS,
	type MemberAiChoice,
} from "@/components/practice-vocabulary/data-handling-defs";
import { DataHandlingBadge } from "@/components/practice-vocabulary/DataHandlingBadge";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
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
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";

import { BudgetExhaustedAlert } from "./BudgetExhaustedAlert";
import { ModelPicker, type ModelSelection } from "./ModelPicker";

export type Purpose = AgentBinding["purpose"];

/** One row on the page: a purpose's slot for one data-handling tier. */
export interface BindingTarget {
	purpose: Purpose;
	tier: DataHandlingTier;
}

export function bindingTargetKey({ purpose, tier }: BindingTarget): string {
	return `${purpose}:${tier}`;
}

interface PurposeMeta {
	purpose: Purpose;
	description: string;
	disabledLabel: string;
}

export const PURPOSE_TITLES = {
	PRACTICE_REVIEW: "Practice reviews",
	MENTOR: "Heph",
} satisfies Record<Purpose, string>;

export function isPurpose(value: string): value is Purpose {
	return Object.hasOwn(PURPOSE_TITLES, value);
}

const PURPOSES: PurposeMeta[] = [
	{
		purpose: "PRACTICE_REVIEW",
		description: "Reviews connected project work and conversations.",
		disabledLabel: "Practice reviews off",
	},
	{
		purpose: "MENTOR",
		description: "Powers conversations with Heph.",
		disabledLabel: "Heph web chat off",
	},
];

/** The developer answers the preview walks, in card order; No AI is served by nothing. */
const PREVIEWED_CHOICES = [
	"IN_HOUSE_ONLY",
	"NOT_KEPT_ONLY",
	"ANY_DECLARED",
] satisfies MemberAiChoice[];

const UNCHOSEN_ROW_TITLE = "Members who haven't chosen";

const MIN_TIMEOUT_SECONDS = 30;
const MAX_TIMEOUT_SECONDS = 10800;
const MIN_CONCURRENT_JOBS = 1;

const TIMEOUT_CEILING = {
	max: MAX_TIMEOUT_SECONDS,
	error: `Runs stop after three hours, so enter ${MAX_TIMEOUT_SECONDS} seconds or less.`,
};

function bindingToSelection(binding?: AgentBinding): ModelSelection | null {
	if (binding?.instanceModelId != null) return { scope: "SHARED", id: binding.instanceModelId };
	if (binding?.workspaceModelId != null)
		return { scope: "WORKSPACE", id: binding.workspaceModelId };
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

export interface AgentBindingsPageProps {
	workspaceSlug: string;
	bindings: AgentBinding[];
	availableModels: AvailableLlmModel[];
	practicesEnabled: boolean;
	mentorEnabled: boolean;
	/** Once a workspace requires the choice, the undeclared row serves nobody. */
	aiChoiceRequired: boolean;
	providerPanel?: ReactNode;
	usage?: WorkspaceLlmUsageReport;
	isLoading: boolean;
	isError: boolean;
	loadError: unknown;
	/** Rows with a save or clear in flight, keyed by {@link bindingTargetKey}. */
	pendingTargets: ReadonlySet<string>;
	/** Bumped after a write lands so the row reseeds from what the server returned. */
	saveRevisions?: Partial<Record<string, number>>;
	/** The server's refusal of the last save, worded by the route, shown in the row it refused. */
	saveErrors?: Partial<Record<string, string>>;
	onRetry: () => void;
	onSave: (target: BindingTarget, body: AgentBindingRequest) => void;
	onTurnOff: (target: BindingTarget) => void;
}

export function AgentBindingsPage({
	workspaceSlug,
	bindings,
	availableModels,
	practicesEnabled,
	mentorEnabled,
	aiChoiceRequired,
	providerPanel,
	usage,
	isLoading,
	isError,
	loadError,
	pendingTargets,
	saveRevisions,
	saveErrors,
	onRetry,
	onSave,
	onTurnOff,
}: AgentBindingsPageProps) {
	const featureEnabled = (purpose: Purpose): boolean =>
		purpose === "MENTOR" ? mentorEnabled : practicesEnabled;

	return (
		<PageLayout>
			<PageHeader
				icon={<BrainCircuit />}
				title="AI models"
				description="Choose the models that power workspace AI features."
			/>

			<div className="max-w-4xl space-y-6">
				{(usage?.ownProviderPaused === true || usage?.instancePaused === true) && (
					<div className="space-y-3">
						{usage.ownProviderPaused && (
							<BudgetExhaustedAlert
								scope="own"
								verdict={usage.ownProviderBudgetVerdict}
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

				{isError ? (
					<QueryErrorAlert error={loadError} title="Couldn't load AI models" onRetry={onRetry} />
				) : isLoading ? (
					<div className="flex h-40 items-center justify-center">
						<Spinner className="size-6" />
					</div>
				) : (
					<div className="space-y-6">
						<section className="space-y-4">
							<div className="space-y-1">
								<h2 className="text-lg font-semibold">Model assignments</h2>
								<p className="text-sm text-muted-foreground">
									A member's AI choice is a ceiling: the loosest ready row within it serves them,
									and nothing moves them to a looser one.
								</p>
							</div>
							{PURPOSES.map((meta) => (
								<AgentPurposeCard
									key={meta.purpose}
									meta={meta}
									workspaceSlug={workspaceSlug}
									bindings={bindings.filter((binding) => binding.purpose === meta.purpose)}
									availableModels={availableModels}
									aiChoiceRequired={aiChoiceRequired}
									featureEnabled={featureEnabled(meta.purpose)}
									pendingTargets={pendingTargets}
									saveRevisions={saveRevisions}
									saveErrors={saveErrors}
									onSave={onSave}
									onTurnOff={onTurnOff}
								/>
							))}
						</section>

						{providerPanel && <section className="space-y-4">{providerPanel}</section>}
					</div>
				)}
			</div>
		</PageLayout>
	);
}

interface AgentPurposeCardProps {
	meta: PurposeMeta;
	workspaceSlug: string;
	/** This purpose's bindings only. */
	bindings: AgentBinding[];
	availableModels: AvailableLlmModel[];
	aiChoiceRequired: boolean;
	featureEnabled: boolean;
	pendingTargets: ReadonlySet<string>;
	saveRevisions?: Partial<Record<string, number>>;
	saveErrors?: Partial<Record<string, string>>;
	onSave: (target: BindingTarget, body: AgentBindingRequest) => void;
	onTurnOff: (target: BindingTarget) => void;
}

function AgentPurposeCard({
	meta,
	workspaceSlug,
	bindings,
	availableModels,
	aiChoiceRequired,
	featureEnabled,
	pendingTargets,
	saveRevisions,
	saveErrors,
	onSave,
	onTurnOff,
}: AgentPurposeCardProps) {
	const cardLabelId = useId();

	return (
		<Card role="region" aria-labelledby={cardLabelId}>
			<CardHeader>
				<div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
					<div className="min-w-0 flex-1">
						<CardTitle id={cardLabelId}>{PURPOSE_TITLES[meta.purpose]}</CardTitle>
						<CardDescription>
							{meta.description}
							{!featureEnabled &&
								(meta.purpose === "PRACTICE_REVIEW" ? (
									<div>
										<Link
											to="/w/$workspaceSlug/admin/practices/review"
											params={{ workspaceSlug }}
											search={{ section: "when-and-where" }}
											className="underline underline-offset-4"
										>
											Open Review: When and where
										</Link>
									</div>
								) : (
									<div>
										<Link
											to="/w/$workspaceSlug/admin/settings"
											params={{ workspaceSlug }}
											className="underline underline-offset-4"
										>
											Open Workspace settings
										</Link>
									</div>
								))}
						</CardDescription>
					</div>
					{!featureEnabled && (
						<div className="flex flex-wrap justify-end gap-2">
							<Badge variant="secondary">{meta.disabledLabel}</Badge>
						</div>
					)}
				</div>
			</CardHeader>
			<CardContent className="space-y-6">
				<BindingPreview
					bindings={bindings}
					availableModels={availableModels}
					aiChoiceRequired={aiChoiceRequired}
					featureOffLabel={featureEnabled ? undefined : meta.disabledLabel}
				/>
				{DATA_HANDLING_TIERS.map((tier) => {
					const target: BindingTarget = { purpose: meta.purpose, tier };
					const key = bindingTargetKey(target);
					return (
						<BindingRow
							key={`${key}:${saveRevisions?.[key] ?? 0}`}
							target={target}
							binding={bindings.find((binding) => binding.dataHandlingTier === tier)}
							availableModels={availableModels}
							aiChoiceRequired={aiChoiceRequired}
							pending={pendingTargets.has(key)}
							saveError={saveErrors?.[key]}
							onSave={onSave}
							onTurnOff={onTurnOff}
						/>
					);
				})}
			</CardContent>
		</Card>
	);
}

interface BindingPreviewProps {
	bindings: AgentBinding[];
	availableModels: AvailableLlmModel[];
	aiChoiceRequired: boolean;
	/** Set while the purpose is switched off: no row serves anyone, whatever it binds. */
	featureOffLabel?: string;
}

/**
 * Who gets which model, judged the way the server does: the ceiling rule over this purpose's rows,
 * behind the purpose's own switch. The developer's card shows the same answer.
 */
function BindingPreview({
	bindings,
	availableModels,
	aiChoiceRequired,
	featureOffLabel,
}: BindingPreviewProps) {
	const served = (choice: MemberAiChoice | null): ReactNode => {
		if (featureOffLabel !== undefined) return `→ nothing runs for them (${featureOffLabel})`;
		const binding = bindingFor(choice, bindings);
		if (!binding) return "→ nothing runs for them";
		return (
			<>
				→{" "}
				<span className="font-medium text-foreground">
					{DATA_HANDLING_DEFS[binding.dataHandlingTier].label}
				</span>
				: {modelOf(binding, availableModels)?.displayName ?? "a model no longer offered here"}
			</>
		);
	};
	const facts: Fact[] = PREVIEWED_CHOICES.map((choice) => ({
		icon: MEMBER_AI_CHOICE_DEFS[choice].icon,
		term: `Members who chose ${MEMBER_AI_CHOICE_DEFS[choice].label}`,
		detail: served(choice),
	}));
	if (!aiChoiceRequired) {
		facts.push({ icon: CircleHelpIcon, term: UNCHOSEN_ROW_TITLE, detail: served(null) });
	}
	return (
		<div className="space-y-2">
			<h3 className="text-sm font-medium">Preview</h3>
			<FactList facts={facts} />
		</div>
	);
}

interface BindingRowProps {
	target: BindingTarget;
	binding?: AgentBinding;
	availableModels: AvailableLlmModel[];
	aiChoiceRequired: boolean;
	pending: boolean;
	saveError?: string;
	onSave: (target: BindingTarget, body: AgentBindingRequest) => void;
	onTurnOff: (target: BindingTarget) => void;
}

function BindingRow({
	target,
	binding,
	availableModels,
	aiChoiceRequired,
	pending,
	saveError,
	onSave,
	onTurnOff,
}: BindingRowProps) {
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
	const boundModel = modelOf(binding, availableModels);
	// A bound model whose facts were re-declared has dropped out of this row: the picker still names
	// it on the trigger, and the row says why it stopped serving.
	const boundModelMoved =
		boundModel !== undefined && pickerTier !== undefined && boundModel.dataHandlingTier !== tier;

	const [selection, setSelection] = useState<ModelSelection | null>(bindingToSelection(binding));
	const [enabled, setEnabled] = useState(binding?.enabled ?? true);
	const [timeoutSeconds, setTimeoutSeconds] = useState(
		String(binding?.timeoutSeconds ?? MAX_TIMEOUT_SECONDS),
	);
	const [maxConcurrentJobs, setMaxConcurrentJobs] = useState(
		String(binding?.maxConcurrentJobs ?? 3),
	);
	const [allowInternet, setAllowInternet] = useState(binding?.allowInternet ?? false);
	const [showAdvanced, setShowAdvanced] = useState(false);
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
	const saveErrorId = `${rowId}-save-error`;
	const timeoutErrorId = `${rowId}-timeout-error`;
	const concurrencyErrorId = `${rowId}-concurrency-error`;
	const modelDescribedBy =
		[
			noModels || boundModelMoved ? modelHintId : null,
			modelError ? modelErrorId : null,
			saveError ? saveErrorId : null,
		]
			.filter(Boolean)
			.join(" ") || undefined;

	useEffect(() => {
		if (submitAttempt > 0) {
			formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
		}
	}, [submitAttempt]);

	const handleSubmit = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		if (!selection || timeout.value == null || concurrency.value == null) {
			setSubmitAttempt((attempt) => attempt + 1);
			if (timeout.value == null || concurrency.value == null) setShowAdvanced(true);
			return;
		}
		onSave(target, {
			instanceModelId: selection.scope === "SHARED" ? selection.id : undefined,
			workspaceModelId: selection.scope === "WORKSPACE" ? selection.id : undefined,
			timeoutSeconds: timeout.value,
			maxConcurrentJobs: concurrency.value,
			allowInternet,
			enabled,
		});
	};

	// Every row repeats the same controls, so each control names its row for a screen reader.
	const forRow = <span className="sr-only"> for {rowTitle}</span>;

	return (
		<div role="group" aria-labelledby={headingId} className="space-y-4 rounded-lg border p-4">
			<div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
				<div className="min-w-0 flex-1 space-y-1">
					{/* A declared row is titled by its tier, which the badge already shows. */}
					<div className="flex flex-wrap items-center gap-2">
						<h3 id={headingId} className={cn("text-sm font-medium", !undeclared && "sr-only")}>
							{rowTitle}
						</h3>
						<DataHandlingBadge tier={tier} />
					</div>
					<p className="text-sm text-muted-foreground">
						{undeclared
							? aiChoiceRequired
								? "Serves only members who have not chosen yet, where the choice is optional. Every member here must choose, so it serves no one now."
								: "Serves only members who have not chosen yet, where the choice is optional. Never serves a member who chose."
							: `For members whose answer allows ${tierLabel}.`}
					</p>
				</div>
				{binding && (
					<div className="flex flex-wrap justify-end gap-2">
						<StatusBadge def={BINDING_READINESS_DEFS[bindingReadiness(binding)]} />
					</div>
				)}
			</div>
			<form ref={formRef} onSubmit={handleSubmit} noValidate className="space-y-4">
				<FieldGroup>
					<Field data-invalid={Boolean(modelError ?? saveError)}>
						<FieldLabel id={modelLabelId} htmlFor={modelId}>
							Model{forRow}
						</FieldLabel>
						<ModelPicker
							id={modelId}
							aria-labelledby={modelLabelId}
							availableModels={availableModels}
							tier={pickerTier}
							value={selection}
							onChange={setSelection}
							disabled={pending || noModels}
							invalid={Boolean(modelError ?? saveError)}
							aria-describedby={modelDescribedBy}
						/>
						{boundModelMoved ? (
							<FieldDescription id={modelHintId}>
								{boundModel.displayName} is now declared as{" "}
								<span className="font-medium">
									{DATA_HANDLING_DEFS[boundModel.dataHandlingTier].label}
								</span>{" "}
								and no longer serves this row.{" "}
								{noModels ? (
									<>
										Clear the assignment, or ask your host for a model declared as{" "}
										<span className="font-medium">{tierLabel}</span>.
									</>
								) : (
									"Choose another model, or clear the assignment."
								)}
							</FieldDescription>
						) : (
							noModels && (
								<FieldDescription id={modelHintId}>
									{undeclared ? (
										"No models are available yet. Ask your host to share one, or connect your own AI provider below."
									) : (
										<>
											No model declared as <span className="font-medium">{tierLabel}</span> is
											available here yet. Ask your host, or add one under your own providers.
										</>
									)}
								</FieldDescription>
							)
						)}
						{modelError && <FieldError id={modelErrorId}>{modelError}</FieldError>}
						{saveError && <FieldError id={saveErrorId}>{saveError}</FieldError>}
					</Field>

					<Field orientation="horizontal">
						<FieldLabel htmlFor={`${rowId}-enabled`}>Use this model{forRow}</FieldLabel>
						<Switch
							id={`${rowId}-enabled`}
							checked={selection != null && enabled}
							onCheckedChange={setEnabled}
							disabled={pending || selection == null}
						/>
					</Field>
				</FieldGroup>

				<Collapsible open={showAdvanced} onOpenChange={setShowAdvanced}>
					<CollapsibleTrigger
						render={
							<Button type="button" variant="ghost" size="sm" className="-ml-2 group/adv">
								Advanced{forRow}
								<ChevronDown
									className="transition-transform group-aria-expanded/adv:rotate-180"
									aria-hidden
								/>
							</Button>
						}
					/>
					<CollapsibleContent>
						<FieldSet className="pt-4">
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
										aria-describedby={timeoutError ? timeoutErrorId : undefined}
										onChange={(e) => setTimeoutSeconds(e.target.value)}
										disabled={pending}
									/>
									{timeoutError && <FieldError id={timeoutErrorId}>{timeoutError}</FieldError>}
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
											aria-describedby={concurrencyError ? concurrencyErrorId : undefined}
											onChange={(e) => setMaxConcurrentJobs(e.target.value)}
											disabled={pending}
										/>
										{concurrencyError && (
											<FieldError id={concurrencyErrorId}>{concurrencyError}</FieldError>
										)}
									</Field>
								)}
								<Field orientation="horizontal">
									<FieldLabel htmlFor={`${rowId}-internet`}>Internet access{forRow}</FieldLabel>
									<Switch
										id={`${rowId}-internet`}
										checked={allowInternet}
										onCheckedChange={setAllowInternet}
										disabled={pending}
									/>
								</Field>
							</FieldGroup>
						</FieldSet>
					</CollapsibleContent>
				</Collapsible>

				<div className="flex flex-wrap justify-end gap-2">
					{binding && (
						<Button
							type="button"
							variant="outline"
							size="sm"
							onClick={() => onTurnOff(target)}
							disabled={pending}
						>
							Clear assignment{forRow}
						</Button>
					)}
					<Button type="submit" size="sm" disabled={pending}>
						Save assignment{forRow}
					</Button>
				</div>
			</form>
		</div>
	);
}
