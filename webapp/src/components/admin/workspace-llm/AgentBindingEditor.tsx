import { ChevronDown, InfoIcon } from "lucide-react";
import { type ReactNode, type SubmitEvent, useId, useRef, useState } from "react";
import { flushSync } from "react-dom";
import { z } from "zod";
import type { AgentBinding, AgentBindingRequest, AvailableLlmModel } from "@/api/types.gen";
import { StatusBadge } from "@/components/common/StatusBadge";
import { isPracticeScriptPurpose } from "@/components/practice-vocabulary/agent-purpose-defs";
import {
	BINDING_READINESS_DEFS,
	type BindingReadiness,
	bindingReadiness,
} from "@/components/practice-vocabulary/binding-readiness-defs";
import {
	DATA_HANDLING_DEFS,
	type DataHandlingTier,
	UNCHOSEN_MEMBERS,
} from "@/components/practice-vocabulary/data-handling-defs";
import { Button } from "@/components/ui/button";
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
import { capitalise, hasText } from "@/lib/text";
import type { BindingTarget, BindingWrite } from "./AgentBindingsPage";
import { ModelPicker, type ModelSelection } from "./ModelPicker";

const UNCHOSEN_ROW_TITLE = capitalise(UNCHOSEN_MEMBERS);

const MIN_TIMEOUT_SECONDS = 30;
const MAX_TIMEOUT_SECONDS = 10_800;
const MIN_CONCURRENT_JOBS = 1;

const CONVERSATION_TURN_SECONDS = 300;

/** The longest one attempt may last: a review attempt up to three hours, one Hephaestus answer five minutes. */
function timeoutCeiling(purpose: BindingTarget["purpose"]) {
	return purpose === "MENTOR"
		? {
				max: CONVERSATION_TURN_SECONDS,
				error: `Hephaestus stops an answer after five minutes, so enter ${CONVERSATION_TURN_SECONDS} seconds or less.`,
			}
		: {
				max: MAX_TIMEOUT_SECONDS,
				error: `One attempt of a review stops after three hours, so enter ${MAX_TIMEOUT_SECONDS} seconds or less.`,
			};
}

function bindingToSelection(binding?: AgentBinding): ModelSelection | null {
	if (binding?.instanceModelId != null) {
		return { scope: "SHARED", id: binding.instanceModelId };
	}
	if (binding?.workspaceModelId != null) {
		return { scope: "WORKSPACE", id: binding.workspaceModelId };
	}
	return null;
}

export function modelOf(
	binding: AgentBinding | undefined,
	models: AvailableLlmModel[],
): AvailableLlmModel | undefined {
	const selection = bindingToSelection(binding);
	return models.find((m) => m.scope === selection?.scope && m.id === selection.id);
}

const numberInput = (input: unknown) => (input === "" ? undefined : input);

const runLimitsSchema = (ceiling: ReturnType<typeof timeoutCeiling>) =>
	z.object({
		timeoutSeconds: z.preprocess(
			numberInput,
			z.coerce
				.number({ error: "Enter a number of seconds." })
				.int({ error: "Enter a whole number of seconds, 30 or more." })
				.min(MIN_TIMEOUT_SECONDS, { error: "Enter a whole number of seconds, 30 or more." })
				.max(ceiling.max, { error: ceiling.error }),
		),
		maxConcurrentJobs: z.preprocess(
			numberInput,
			z.coerce
				.number({ error: "Enter a number of reviews." })
				.int({ error: "Enter a whole number of reviews, 1 or more." })
				.min(MIN_CONCURRENT_JOBS, { error: "Enter a whole number of reviews, 1 or more." }),
		),
	});

function limitErrors(limits: ReturnType<ReturnType<typeof runLimitsSchema>["safeParse"]>) {
	return limits.success ? undefined : z.flattenError(limits.error).fieldErrors;
}

// A refusal belongs to the rejected model; changing the model must clear that refusal.
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

interface AgentBindingEditorProps {
	target: BindingTarget;
	binding?: AgentBinding;
	availableModels: AvailableLlmModel[];
	aiChoiceRequired: boolean;
	ownProviderAllowed: boolean;
	practiceReviewsOff: boolean;
	/**
	 * A Cloud assignment for practice scripts that Cloud members do not get: their reviews run on an In-house
	 * model, and a script model is never looser than its review's.
	 */
	unusedForCloudMembers: boolean;
	pendingWrite?: BindingWrite;
	saveError?: string;
	onSave: (target: BindingTarget, body: AgentBindingRequest) => void;
	onTurnOff: (target: BindingTarget) => void;
}

export function AgentBindingEditor({
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
}: AgentBindingEditorProps) {
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
	const pickerExplainsEmpty = isPracticeScriptPurpose(purpose) && availableModels.length === 0;
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
	const ceiling = timeoutCeiling(purpose);
	const [timeoutSeconds, setTimeoutSeconds] = useState(() =>
		String(binding?.timeoutSeconds ?? ceiling.max),
	);
	const [maxConcurrentJobs, setMaxConcurrentJobs] = useState(() =>
		String(binding?.maxConcurrentJobs ?? 3),
	);
	const [showAdvanced, setShowAdvanced] = useState(false);
	const hasRunLimits = purpose === "PRACTICE_REVIEW" || purpose === "MENTOR";
	const [showErrors, setShowErrors] = useState(false);
	const limits = runLimitsSchema(ceiling).safeParse({ timeoutSeconds, maxConcurrentJobs });
	const fieldErrors = limitErrors(limits);
	const modelError = showErrors && !selection ? "Choose the model this runs on." : null;
	const timeoutError = showErrors ? fieldErrors?.timeoutSeconds?.[0] : undefined;
	const concurrencyError = showErrors ? fieldErrors?.maxConcurrentJobs?.[0] : undefined;

	const modelId = `${rowId}-model`;
	const modelLabelId = `${rowId}-model-label`;
	const modelHintId = `${rowId}-model-hint`;
	const modelErrorId = `${rowId}-model-error`;
	const noteId = `${rowId}-note`;
	const saveErrorId = `${rowId}-save-error`;
	const timeoutErrorId = `${rowId}-timeout-error`;
	const concurrencyErrorId = `${rowId}-concurrency-error`;
	const modelDescribedBy = Object.entries({
		[noteId]: unusedForCloudMembers,
		[modelHintId]: hintShown,
		[modelErrorId]: hasText(modelError),
		[saveErrorId]: hasText(refusal),
	})
		.filter(([, shown]) => shown)
		.map(([id]) => id)
		.join(" ");

	const handleSubmit = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		if (!selection || !limits.success) {
			flushSync(() => {
				setShowErrors(true);
				if (!limits.success) {
					setShowAdvanced(true);
				}
			});
			formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
			return;
		}
		setSubmitted(selection);
		onSave(target, {
			instanceModelId: selection.scope === "SHARED" ? selection.id : undefined,
			workspaceModelId: selection.scope === "WORKSPACE" ? selection.id : undefined,
			timeoutSeconds: limits.data.timeoutSeconds,
			maxConcurrentJobs: limits.data.maxConcurrentJobs,
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
								<FieldLegend variant="label">Limits</FieldLegend>
								<FieldGroup>
									<Field data-invalid={Boolean(timeoutError)}>
										<FieldLabel htmlFor={`${rowId}-timeout`}>Timeout (seconds){forRow}</FieldLabel>
										<Input
											id={`${rowId}-timeout`}
											type="number"
											inputMode="numeric"
											min={MIN_TIMEOUT_SECONDS}
											max={ceiling.max}
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
												Max concurrent reviews{forRow}
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
	movedModel?: AvailableLlmModel;
	noModels: boolean;
	undeclared: boolean;
	tierLabel: string;
	/** False, only an instance admin can add a model, so the hint sends the admin to one. */
	ownProviderAllowed: boolean;
}

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
				No model for this use is available yet.{" "}
				{ownProviderAllowed
					? "Ask an instance admin to share one, or connect a provider under Your providers."
					: "Ask an instance admin to share one."}
			</FieldDescription>
		);
	}
	return (
		<FieldDescription id={id}>
			No model for this use declared as <span className="font-medium">{tierLabel}</span> is
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
	forRow: ReactNode;
	onClear: () => void;
}

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
