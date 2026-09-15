import { Link } from "@tanstack/react-router";
import { BrainCircuit, ChevronDown } from "lucide-react";
import { type ReactNode, type SubmitEvent, useEffect, useId, useRef, useState } from "react";

import type {
	AgentBinding,
	AgentBindingRequest,
	AvailableLlmModel,
	WorkspaceLlmUsageReport,
} from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
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
	providerPanel?: ReactNode;
	usage?: WorkspaceLlmUsageReport;
	isLoading: boolean;
	isError: boolean;
	loadError: unknown;
	pendingPurposes: ReadonlySet<Purpose>;
	saveRevisions?: Partial<Record<Purpose, number>>;
	onRetry: () => void;
	onSave: (purpose: Purpose, body: AgentBindingRequest) => void;
	onTurnOff: (purpose: Purpose) => void;
}

export function AgentBindingsPage({
	workspaceSlug,
	bindings,
	availableModels,
	practicesEnabled,
	mentorEnabled,
	providerPanel,
	usage,
	isLoading,
	isError,
	loadError,
	pendingPurposes,
	saveRevisions,
	onRetry,
	onSave,
	onTurnOff,
}: AgentBindingsPageProps) {
	const bindingFor = (purpose: Purpose) => bindings.find((b) => b.purpose === purpose);
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
							<h2 className="text-lg font-semibold">Model assignments</h2>
							{PURPOSES.map((meta) => (
								<AgentPurposeCard
									key={`${meta.purpose}:${saveRevisions?.[meta.purpose] ?? 0}`}
									meta={meta}
									workspaceSlug={workspaceSlug}
									binding={bindingFor(meta.purpose)}
									availableModels={availableModels}
									featureEnabled={featureEnabled(meta.purpose)}
									pending={pendingPurposes.has(meta.purpose)}
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
	binding?: AgentBinding;
	availableModels: AvailableLlmModel[];
	featureEnabled: boolean;
	pending: boolean;
	onSave: (purpose: Purpose, body: AgentBindingRequest) => void;
	onTurnOff: (purpose: Purpose) => void;
}

function AgentPurposeCard({
	meta,
	workspaceSlug,
	binding,
	availableModels,
	featureEnabled,
	pending,
	onSave,
	onTurnOff,
}: AgentPurposeCardProps) {
	const cardLabelId = useId();
	const formRef = useRef<HTMLFormElement>(null);
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
	// A practice review always runs on an internal network; the server rejects the flag for it.
	const internetConfigurable = meta.purpose === "MENTOR";
	const [submitAttempt, setSubmitAttempt] = useState(0);

	const noModels = availableModels.length === 0;
	const showErrors = submitAttempt > 0;

	const timeout = parseWholeNumber(timeoutSeconds, MIN_TIMEOUT_SECONDS, "seconds", TIMEOUT_CEILING);
	const concurrency = parseWholeNumber(maxConcurrentJobs, MIN_CONCURRENT_JOBS, "runs");
	const modelError = showErrors && !selection ? "Choose the model this runs on." : null;
	const timeoutError = showErrors ? timeout.error : null;
	const concurrencyError = showErrors ? concurrency.error : null;

	const modelHintId = `${meta.purpose}-model-hint`;
	const modelErrorId = `${meta.purpose}-model-error`;
	const timeoutErrorId = `${meta.purpose}-timeout-error`;
	const concurrencyErrorId = `${meta.purpose}-concurrency-error`;
	const modelDescribedBy =
		[noModels ? modelHintId : null, modelError ? modelErrorId : null].filter(Boolean).join(" ") ||
		undefined;

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
		onSave(meta.purpose, {
			instanceModelId: selection.scope === "SHARED" ? selection.id : undefined,
			workspaceModelId: selection.scope === "WORKSPACE" ? selection.id : undefined,
			timeoutSeconds: timeout.value,
			maxConcurrentJobs: concurrency.value,
			allowInternet: internetConfigurable && allowInternet,
			enabled,
		});
	};

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
					<div className="flex flex-wrap justify-end gap-2">
						{binding &&
							(binding.ready ? (
								<Badge variant="secondary">Ready</Badge>
							) : (
								<Badge variant="destructive">Not ready</Badge>
							))}
						{!featureEnabled && <Badge variant="secondary">{meta.disabledLabel}</Badge>}
					</div>
				</div>
			</CardHeader>
			<form ref={formRef} onSubmit={handleSubmit} noValidate>
				<CardContent className="space-y-4">
					<FieldGroup>
						<Field data-invalid={Boolean(modelError)}>
							<FieldLabel id={`${meta.purpose}-model-label`} htmlFor={`${meta.purpose}-model`}>
								{PURPOSE_TITLES[meta.purpose]} model
							</FieldLabel>
							<ModelPicker
								id={`${meta.purpose}-model`}
								aria-labelledby={`${meta.purpose}-model-label`}
								availableModels={availableModels}
								value={selection}
								onChange={setSelection}
								disabled={pending || noModels}
								invalid={Boolean(modelError)}
								aria-describedby={modelDescribedBy}
							/>
							{noModels && (
								<FieldDescription id={modelHintId}>
									No models are available yet. Ask your host to share one, or connect your own AI
									provider below.
								</FieldDescription>
							)}
							{modelError && <FieldError id={modelErrorId}>{modelError}</FieldError>}
						</Field>

						<Field orientation="horizontal">
							<FieldLabel htmlFor={`${meta.purpose}-enabled`}>Use this model</FieldLabel>
							<Switch
								id={`${meta.purpose}-enabled`}
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
									Advanced
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
										<FieldLabel htmlFor={`${meta.purpose}-timeout`}>Timeout (seconds)</FieldLabel>
										<Input
											id={`${meta.purpose}-timeout`}
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
									<Field data-invalid={Boolean(concurrencyError)}>
										<FieldLabel htmlFor={`${meta.purpose}-concurrency`}>
											Max concurrent runs
										</FieldLabel>
										<Input
											id={`${meta.purpose}-concurrency`}
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
									{internetConfigurable && (
										<Field orientation="horizontal">
											<FieldLabel htmlFor={`${meta.purpose}-internet`}>Internet access</FieldLabel>
											<Switch
												id={`${meta.purpose}-internet`}
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

					<div className="flex justify-end gap-2">
						{binding && (
							<Button
								type="button"
								variant="outline"
								size="sm"
								onClick={() => onTurnOff(meta.purpose)}
								disabled={pending}
							>
								Clear assignment
							</Button>
						)}
						<Button type="submit" size="sm" disabled={pending}>
							Save assignment
						</Button>
					</div>
				</CardContent>
			</form>
		</Card>
	);
}
