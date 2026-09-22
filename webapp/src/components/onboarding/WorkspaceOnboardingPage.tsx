import {
	BotIcon,
	CheckIcon,
	GraduationCapIcon,
	InfoIcon,
	Link2Icon,
	RefreshCwIcon,
	SparklesIcon,
} from "lucide-react";
import { type SubmitEvent, useEffect, useId, useRef, useState } from "react";
import { hasText } from "@/lib/text";

import type { WorkspaceOnboarding } from "@/api/types.gen";
import { type Fact, FactList } from "@/components/auth/FactList";
import { LegalLinks } from "@/components/auth/LegalLinks";
import { StepMarker } from "@/components/auth/StepMarker";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { getProviderIcon } from "@/components/icons/integration-provider-icons";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import { HephSays } from "@/components/mentor/HephSays";
import {
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

/**
 * Everything that explains the choice, addressed to every answer at once; the answers themselves
 * carry a title and two sentences each. The stories' meta block has the reasoning.
 */
const AI_FACTS: readonly Fact[] = [
	{
		icon: BotIcon,
		term: "What AI does",
		detail: "Reviews your work against your team’s practices, and talks it through as Heph.",
	},
	{
		icon: GraduationCapIcon,
		term: "Never for training",
		detail: "Your work is never used to train a model, whichever answer you give.",
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

/** One account-wide decision, with this workspace's account links kept separate from saving it. */
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
		hint,
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

	const workspaceName = data?.workspaceName ?? "this workspace";
	const intro = `I only read your work in ${workspaceName} within the AI you allow. You answer once, for all your workspaces.`;

	// Heph narrates the reader's answers; the footer hint says the same thing factually and reaches
	// the button through `aria-describedby`, so focusing it does not replay the line.
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
				<PageLayout className="max-w-2xl px-6 py-10">
					<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />

					<header className="space-y-4">
						<h1 className="text-2xl font-semibold tracking-tight break-words">{heading}</h1>
						<HephSays intro={intro} narration={narration} />
					</header>

					<Separator />

					{state.status === "loading" && (
						<div className="space-y-6" aria-busy="true">
							<span className="sr-only">Loading…</span>
							<Skeleton className="h-32 w-full" />
							<div className="grid gap-3 sm:grid-cols-2">
								<Skeleton className="h-20" />
								<Skeleton className="h-20" />
								<Skeleton className="h-20" />
								<Skeleton className="h-20" />
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
									<span className="flex items-start gap-3">
										<StepMarker icon={SparklesIcon} done={answered} />
										Which AI may handle your work?
									</span>
								</QuestionnaireTitle>
								<QuestionnaireDescription>
									Each answer also allows everything stricter than it; the bar on a card shows how
									far your work may travel. Allowing more does not mean better results.
								</QuestionnaireDescription>
								<FactList facts={AI_FACTS} />
								<fieldset disabled={saving} className="min-w-0 disabled:opacity-50">
									<AiChoiceCards choice={choice} onChoice={setDraft} />
								</fieldset>
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
												const prefix = hasText(link.teamName) ? `${link.teamName} · ` : "";
												const rowDescription =
													!link.available && !link.linked
														? `${prefix}Unavailable right now — it doesn't hold you up.`
														: `${prefix}${link.required ? "Required" : "Optional"}`;
												const descriptionId = `${id}-link-${link.connectionId}`;
												return (
													<Item key={link.connectionId} variant="outline" role="listitem">
														<ItemMedia variant="icon">
															<Icon aria-hidden="true" />
														</ItemMedia>
														<ItemContent>
															<ItemTitle className="break-words">{link.displayName}</ItemTitle>
															{/* The reason a link cannot be connected has to be readable in full. */}
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
																	{changed ? "Save & connect" : "Connect"}
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

							{/* The exit sits at the far edge from the primary: only one of the two moves the flow on. */}
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
									Skipping does not save an answer selected above.{" "}
									{data?.aiChoice == null
										? "AI stays off until you save a choice."
										: `Your saved choice (${memberAiChoiceTitle(data.aiChoice)}) stays in effect.`}
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
		return "Give me a moment — I'm fetching your setup.";
	}
	if (status === "error") {
		return "I couldn't fetch your setup just now.";
	}
	if (changed) {
		// A draft nobody has built up to is still an answer worth saving; say so before the cue.
		const uncovered =
			coverage === "none" ? "That isn't set up here yet, and I won't switch you elsewhere. " : "";
		if (firstVisit) {
			return requiredSatisfied
				? `${uncovered}Press Continue and I'll remember it everywhere.`
				: `${uncovered}Save your AI choice now. You can connect your accounts separately.`;
		}
		return `${uncovered}Save to apply your new choice in every workspace. Requests already sent cannot be recalled.`;
	}
	if (choice === undefined) {
		return "One question: which AI may handle your work. Any answer is fine by me, including none.";
	}
	if (coverage === "none") {
		return "Your choice isn't set up here yet. I won't switch you anywhere else.";
	}
	if (coverage === "partial") {
		return "Part of your choice isn't set up here yet. I won't switch you anywhere else.";
	}
	if (firstVisit && answered) {
		// The same line whether the answer was just saved here or made in another workspace.
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
	// A return visit reached through an OAuth round-trip: nothing is owed, so Heph names the exit.
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
	// Saving an AI choice is independent of connecting accounts, even on a first visit.
	const canSubmit = choice !== undefined && (changed || (firstVisit && requiredSatisfied));
	let heading = "Workspace setup";
	if (data !== undefined) {
		heading = firstVisit ? `Welcome to ${data.workspaceName}` : "Your AI choice";
	}
	let hint: string | undefined;
	if (data !== undefined) {
		if (choice === undefined) {
			hint = firstVisit ? "Choose an answer to continue." : "Choose an answer, then save.";
		} else if (firstVisit && !requiredSatisfied) {
			hint = changed
				? `Save your AI choice now; connect ${openRequiredNames} to finish setup.`
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
		hint,
	};
}
