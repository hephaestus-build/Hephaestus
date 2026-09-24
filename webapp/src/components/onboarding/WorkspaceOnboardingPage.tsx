import { BotIcon, CheckIcon, InfoIcon, Link2Icon, RefreshCwIcon, SparklesIcon } from "lucide-react";
import { type SubmitEvent, useEffect, useId, useRef, useState } from "react";
import { hasText } from "@/lib/text";

import type { WorkspaceAiModel, WorkspaceOnboarding } from "@/api/types.gen";
import { type Fact, FactList } from "@/components/auth/FactList";
import { LegalLinks } from "@/components/auth/LegalLinks";
import { StepMarker } from "@/components/auth/StepMarker";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { AI_CONNECTION_PLATFORM_META } from "@/components/icons/ai-connection-platform-logos";
import { AI_MODEL_BRAND_META } from "@/components/icons/ai-model-brand-logos";
import { getProviderIcon } from "@/components/icons/integration-provider-icons";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import { HephSays } from "@/components/mentor/HephSays";
import {
	DATA_HANDLING_DEFS,
	type MemberAiChoice,
	memberAiChoiceTitle,
} from "@/components/practice-vocabulary/data-handling-defs";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemGroup,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import {
	Questionnaire,
	QuestionnaireDescription,
	QuestionnaireItem,
	QuestionnaireTitle,
} from "@/components/ui/questionnaire";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { openRequiredLinks } from "@/lib/onboarding-links";
import { type WorkspaceCoverage, workspaceCoverage } from "@/lib/workspace-coverage";

import { AiChoiceCards } from "./AiChoiceCards";

export type OnboardingAction = "save" | "continue";
export type OnboardingSubmission =
	| { status: "idle" }
	| { status: "saving"; action: OnboardingAction }
	| { status: "error"; action: OnboardingAction; message: string };

export interface WorkspaceOnboardingPageProps {
	/** `?step=accounts` after an OAuth round-trip: focus lands on the accounts Section, not the h1. */
	focus?: "accounts";
	state:
		| { status: "loading" }
		| {
				status: "error";
				error: unknown;
				onRetry: () => void;
				/** Plain navigation: with nothing loaded there is nothing to record. */
				onLeave: () => void;
		  }
		| {
				status: "ready";
				data: WorkspaceOnboarding;
				submission: OnboardingSubmission;
				/** A background refetch that failed. A pending refetch shows nothing (the < 1 s rule). */
				refresh?: { status: "error"; error: unknown; onRetry: () => void };
				/** Saves `choice` if it differs from `data.aiChoice`; on a first visit then finishes setup and leaves. */
				onSubmit: (choice: MemberAiChoice) => void;
				/** Persists `draft` before the OAuth redirect so the round-trip never loses an answer. */
				onLink: (registrationId: string, draft: MemberAiChoice | undefined) => void;
				/** First visit: dismiss (records the setup as seen) then leave. Return visit: plain navigation, no write. */
				onLeave: () => void;
		  };
}

const AI_FACTS: readonly Fact[] = [
	{
		icon: BotIcon,
		term: "What AI does",
		detail:
			"Hephaestus reviews your work against your team’s practices. Heph talks it through with you.",
	},
	{
		icon: InfoIcon,
		term: "What this choice controls",
		detail: "Future AI requests for practice reviews and Heph. Sync and stored work are separate.",
	},
	{
		icon: RefreshCwIcon,
		term: "Change it any time",
		detail: "From the sidebar or User settings. It applies in all your workspaces.",
	},
];

function joinNames(names: readonly string[]): string {
	return names.join(" and ");
}

export function WorkspaceOnboardingPage({ focus, state }: WorkspaceOnboardingPageProps) {
	const [draft, setDraft] = useState<MemberAiChoice>();
	const id = useId();
	const accountsRef = useRef<HTMLElement>(null);
	const alertRef = useRef<HTMLDivElement>(null);

	const ready = state.status === "ready" ? state : undefined;
	const data = ready?.data;
	const {
		firstVisit,
		afterLink,
		answered,
		choice,
		changed,
		links,
		openRequiredNames,
		requiredSatisfied,
		canSubmit,
		coverage,
		heading,
		intro,
		hint,
		models,
	} = memberSetupState(data, draft, focus);
	const submission: OnboardingSubmission = ready?.submission ?? { status: "idle" };
	const saving = submission.status === "saving";
	const savingAction = submission.status === "saving" ? submission.action : undefined;
	const submissionStatus = submission.status;
	const accountsMounted = links.length > 0;

	// The Section is not in the DOM until setup has loaded, so this waits for it rather than for
	// mount: after an OAuth round-trip the page is a fresh document and loads before it renders.
	useEffect(() => {
		if (focus === "accounts" && accountsMounted) {
			accountsRef.current?.focus();
		}
	}, [focus, accountsMounted]);

	useEffect(() => {
		if (submissionStatus === "error") {
			alertRef.current?.focus();
		}
	}, [submissionStatus]);

	const narration = onboardingNarration({
		status: state.status,
		changed,
		firstVisit,
		answered,
		requiredSatisfied,
		choice,
		coverage: coverage?.level ?? "covered",
		openRequiredNames,
		afterLink,
	});

	let saveLabel = firstVisit ? "Continue" : "Save";
	if (firstVisit && !requiredSatisfied && changed) {
		saveLabel = "Save AI choice";
	}
	if (savingAction === "save") {
		saveLabel = "Saving…";
	}
	let leaveLabel = firstVisit ? "Skip for now" : "Back to workspace";
	if (savingAction === "continue") {
		leaveLabel = "Skipping…";
	}

	const accountsRequired = links.some((link) => link.required && link.available);

	function submit(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();
		if (ready === undefined || choice === undefined || !canSubmit || saving) {
			return;
		}
		ready.onSubmit(choice);
	}

	return (
		<div className="min-h-svh bg-background">
			<Questionnaire onSubmit={submit}>
				<PageLayout className="max-w-4xl px-6 py-10">
					<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />

					<header className="space-y-4">
						<h1 className="text-2xl font-semibold tracking-tight break-words">{heading}</h1>
						<HephSays intro={intro} narration={narration} />
					</header>

					<Separator />

					{state.status === "loading" && (
						<div className="space-y-6" aria-busy="true">
							<span className="sr-only">Loading…</span>
							<Skeleton className="h-20 w-full" />
							<div className="grid gap-3 md:grid-cols-3">
								<Skeleton className="h-64" />
								<Skeleton className="h-64" />
								<Skeleton className="h-64" />
							</div>
						</div>
					)}
					{state.status === "error" && (
						<QueryErrorAlert
							error={state.error}
							title="Couldn't load your setup"
							onRetry={state.onRetry}
						/>
					)}
					{state.status === "ready" && (
						<>
							{/* Not `disabled` on the item: the primitive hides a disabled item (`hidden` + `inert`)
							    as "not the current question", so a nested fieldset is what holds the cards still. */}
							<QuestionnaireItem name="ai-choice" required>
								<QuestionnaireTitle>
									<span className="flex items-center gap-3">
										<StepMarker icon={SparklesIcon} done={answered} />
										Which AI may handle your work?
									</span>
								</QuestionnaireTitle>
								<QuestionnaireDescription>
									Compare the three and pick one. Cloud also allows in-house AI.
								</QuestionnaireDescription>
								<FactList facts={AI_FACTS} />
								<fieldset disabled={saving} className="min-w-0 disabled:opacity-50">
									<AiChoiceCards
										choice={choice}
										saved={data?.aiChoice}
										onChoice={setDraft}
										modelsByChoice={Object.fromEntries(
											state.data.aiOptions.map((option) => [option.choice, option.models]),
										)}
									/>
								</fieldset>
								{models.length > 0 && (
									<WorkspaceModels workspaceName={state.data.workspaceName} models={models} />
								)}
								{hasText(coverage?.sentence) && (
									<p className="flex items-start gap-2 text-sm text-muted-foreground">
										<InfoIcon className="mt-0.5 size-4 shrink-0 text-mentor" aria-hidden="true" />
										{coverage.sentence}
									</p>
								)}
							</QuestionnaireItem>

							{links.length > 0 && (
								<>
									<Separator />

									<Section
										ref={accountsRef}
										tabIndex={-1}
										className="outline-none"
										id={`${id}-accounts`}
										title={
											<span className="flex items-start gap-3">
												<StepMarker icon={Link2Icon} done={links.every((link) => link.linked)} />
												<span className="min-w-0">Connect your accounts</span>
											</span>
										}
										description={
											accountsRequired
												? "Connect the accounts marked Required to finish setup. Your AI choice can be saved without connecting them."
												: "Optional. You can do this later from User settings."
										}
									>
										<ItemGroup>
											{links.map((link) => {
												const Icon = getProviderIcon(link.providerType);
												const team = hasText(link.teamName) ? ` for ${link.teamName}` : "";
												const rowDescription =
													!link.available && !link.linked
														? `Unavailable right now${team}. It doesn't hold you up.`
														: `${link.required ? "Required" : "Optional"}${team}`;
												const descriptionId = `${id}-link-${link.connectionId}`;
												return (
													<Item key={link.connectionId} variant="outline" role="listitem">
														<ItemMedia variant="icon">
															<Icon aria-hidden="true" />
														</ItemMedia>
														<ItemContent>
															<ItemTitle className="break-words">{link.displayName}</ItemTitle>

															<ItemDescription id={descriptionId} className="line-clamp-none">
																{rowDescription}
															</ItemDescription>
														</ItemContent>
														<ItemActions className="w-full justify-end sm:w-auto">
															{link.linked ? (
																<Badge variant="secondary">
																	<CheckIcon aria-hidden="true" /> Connected
																</Badge>
															) : (
																<Button
																	type="button"
																	variant="outline"
																	disabled={
																		!link.available || !hasText(link.registrationId) || saving
																	}
																	aria-describedby={descriptionId}
																	aria-label={`${changed ? "Save AI choice and connect" : "Connect"} ${link.displayName}`}
																	onClick={() => {
																		if (hasText(link.registrationId)) {
																			state.onLink(link.registrationId, draft);
																		}
																	}}
																>
																	{changed ? "Save and connect" : "Connect"}
																</Button>
															)}
														</ItemActions>
													</Item>
												);
											})}
										</ItemGroup>
									</Section>
								</>
							)}
						</>
					)}

					{/* Each alert announces itself on insertion through its role, so nothing else is live;
					    the focus move that follows a failed write reads it again for anyone who missed it. */}
					{ready?.refresh?.status === "error" && (
						<QueryErrorAlert
							error={ready.refresh.error}
							title="Couldn't refresh your setup"
							onRetry={ready.refresh.onRetry}
						/>
					)}
					{submission.status === "error" && (
						<Alert ref={alertRef} tabIndex={-1} variant="destructive">
							<AlertTitle>
								{submission.action === "save"
									? "Couldn't save your AI choice"
									: "Couldn't continue to your workspace"}
							</AlertTitle>
							<AlertDescription>{submission.message}</AlertDescription>
						</Alert>
					)}

					{state.status !== "loading" && (
						<>
							<Separator />

							<footer className="flex flex-col gap-4 sm:flex-row-reverse sm:items-center sm:justify-between">
								<div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:gap-4">
									{hasText(hint) && (
										<p id={`${id}-hint`} className="text-sm text-muted-foreground">
											{hint}
										</p>
									)}
									{ready !== undefined && (
										<Button
											type="submit"
											disabled={!canSubmit || saving}
											aria-describedby={hasText(hint) ? `${id}-hint` : undefined}
										>
											{savingAction === "save" && <Spinner />}
											{saveLabel}
										</Button>
									)}
								</div>
								<Button
									type="button"
									aria-describedby={firstVisit ? `${id}-skip-hint` : undefined}
									variant="ghost"
									disabled={saving}
									onClick={state.onLeave}
									className="self-start text-muted-foreground sm:-ml-3"
								>
									{savingAction === "continue" && <Spinner />}
									{leaveLabel}
								</Button>
							</footer>
							{firstVisit && (
								<p id={`${id}-skip-hint`} className="text-sm text-muted-foreground">
									Skipping does not save an answer selected above. {skipHint(data)}
								</p>
							)}
						</>
					)}

					<LegalLinks />
				</PageLayout>
			</Questionnaire>
		</div>
	);
}

function skipHint(data: WorkspaceOnboarding | undefined): string {
	if (data?.aiChoice != null) {
		return `Your saved choice (${memberAiChoiceTitle(data.aiChoice)}) stays in effect.`;
	}
	return data?.aiChoiceRequired === true
		? "This workspace cannot use AI for you until you save an answer."
		: "Workspaces that do not require an answer may use a model whose handling is not declared.";
}

function onboardingNarration({
	status,
	changed,
	firstVisit,
	answered,
	requiredSatisfied,
	choice,
	coverage,
	openRequiredNames,
	afterLink,
}: {
	status: WorkspaceOnboardingPageProps["state"]["status"];
	changed: boolean;
	firstVisit: boolean;
	answered: boolean;
	requiredSatisfied: boolean;
	choice: MemberAiChoice | undefined;
	coverage: WorkspaceCoverage["level"];
	openRequiredNames: string;
	/** A return visit reached through an OAuth round-trip: nothing is owed, the exit is the ghost button. */
	afterLink: boolean;
}): string {
	if (status === "loading") {
		return "Give me a moment. Hephaestus is fetching your setup.";
	}
	if (status === "error") {
		return "Hephaestus couldn't fetch your setup just now.";
	}
	if (changed) {
		const uncovered =
			coverage === "none" ? "That isn't set up here yet. Nothing switches you elsewhere. " : "";
		if (firstVisit) {
			return requiredSatisfied
				? `${uncovered}Press Continue and it holds in every workspace.`
				: `${uncovered}Save your AI choice now. You can connect your accounts separately.`;
		}
		return `${uncovered}Save to apply your new choice in every workspace. Requests already sent cannot be recalled.`;
	}
	if (choice === undefined) {
		return "Which AI may handle your work? Any answer is fine, including none.";
	}
	if (coverage === "none") {
		return "Your choice isn't set up here yet. Nothing switches you anywhere else.";
	}
	if (coverage === "partial") {
		return "Part of your choice isn't set up here yet. Nothing switches you anywhere else.";
	}
	if (firstVisit && answered) {
		return requiredSatisfied
			? "Your AI choice is set and holds in all your workspaces. Let's get to work."
			: `Your AI choice is set and holds in all your workspaces. Connect ${openRequiredNames} and you're in.`;
	}
	if (firstVisit) {
		return requiredSatisfied
			? "That's everything. Let's get to work."
			: `Noted. Connect ${openRequiredNames} and you're in.`;
	}
	if (afterLink) {
		return "Your accounts are connected. Head back to your workspace whenever you're ready.";
	}
	return `You chose ${memberAiChoiceTitle(choice)}. Change it whenever you like.`;
}

function memberSetupState(
	data: WorkspaceOnboarding | undefined,
	draft: MemberAiChoice | undefined,
	focus: WorkspaceOnboardingPageProps["focus"],
) {
	const firstVisit = data?.needsSetup === true;
	const afterLink = focus === "accounts" && data !== undefined && !firstVisit;
	const answered = data?.aiChoice != null;
	const choice = draft ?? data?.aiChoice;
	const changed = choice !== data?.aiChoice;
	const coverage =
		data !== undefined && choice !== undefined ? workspaceCoverage(data, choice) : undefined;
	const links = data?.links ?? [];
	const openRequired = openRequiredLinks(links);
	const openRequiredNames = joinNames(openRequired.map((link) => link.displayName));
	const requiredSatisfied = openRequired.length === 0;
	const canSubmit = choice !== undefined && (changed || (firstVisit && requiredSatisfied));
	const heading = "Your AI choice";
	const models = data?.aiOptions.find((option) => option.choice === choice)?.models ?? [];
	const workspaceName = data?.workspaceName ?? "this workspace";
	const intro = firstVisit
		? `Before you start in ${workspaceName}, one question. Your answer controls future AI requests for practice reviews and Heph across all your workspaces.`
		: `Your answer controls future AI requests for practice reviews and Heph in ${workspaceName}, and holds in all your workspaces.`;
	let hint: string | undefined;
	if (data !== undefined) {
		if (choice === undefined) {
			hint = firstVisit ? "Choose an answer to continue." : "Choose an answer, then save.";
		} else if (firstVisit && !requiredSatisfied) {
			hint = changed
				? `Save your AI choice now. Connect ${openRequiredNames} to finish setup.`
				: `Connect ${openRequiredNames} to finish setup.`;
		} else if (!firstVisit && !changed) {
			hint = "Applies in all your workspaces. Change it any time.";
		}
	}

	return {
		firstVisit,
		afterLink,
		answered,
		choice,
		changed,
		links,
		openRequiredNames,
		requiredSatisfied,
		canSubmit,
		coverage,
		heading,
		intro,
		hint,
		models,
	};
}

function WorkspaceModels({
	workspaceName,
	models,
}: {
	workspaceName: string;
	models: readonly WorkspaceAiModel[];
}) {
	return (
		<section
			aria-label={`Models for this answer in ${workspaceName}`}
			className="rounded-lg border border-border bg-muted/30 p-4"
		>
			<p className="text-sm font-semibold text-foreground">Ready models in {workspaceName}</p>
			<p className="mt-1 text-sm text-muted-foreground">
				These models are set up for this answer. The marks show the model and connection; data
				handling is declared separately.
			</p>
			<ul className="mt-3 grid gap-2 sm:grid-cols-2">
				{models.map((model) => {
					const brand = model.brand ? AI_MODEL_BRAND_META[model.brand] : undefined;
					const platform = model.connectionPlatform
						? AI_CONNECTION_PLATFORM_META[model.connectionPlatform]
						: undefined;
					const tier = DATA_HANDLING_DEFS[model.dataHandlingTier];
					return (
						<li
							key={`${model.name}-${model.brand ?? "unknown"}-${model.connectionPlatform ?? "unknown"}`}
							className="min-w-0 rounded-md border border-border bg-background p-3 text-sm"
						>
							<div className="flex items-center gap-2">
								{brand !== undefined && (
									<img
										src={brand.src}
										alt=""
										className="size-6 shrink-0 dark:rounded-sm dark:bg-white dark:p-0.5"
									/>
								)}
								<span className="min-w-0 font-medium break-words text-foreground">
									{model.name}
								</span>
							</div>
							<div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-2 text-muted-foreground">
								{brand !== undefined && <span>Model: {brand.label}</span>}
								{platform !== undefined && (
									<span className="inline-flex items-center gap-1.5">
										<img
											src={platform.src}
											alt=""
											className="size-4 shrink-0 dark:rounded-sm dark:bg-white dark:p-0.5"
										/>
										via {platform.label}
									</span>
								)}
								<Badge variant={tier.badgeVariant}>
									{model.dataHandlingTier === "UNDECLARED"
										? tier.label
										: `Declared ${tier.label.toLowerCase()}`}
								</Badge>
							</div>
						</li>
					);
				})}
			</ul>
		</section>
	);
}
