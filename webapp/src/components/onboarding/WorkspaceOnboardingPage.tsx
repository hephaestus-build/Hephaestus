import {
	BotIcon,
	CheckIcon,
	CircleOffIcon,
	Link2Icon,
	RefreshCwIcon,
	SparklesIcon,
} from "lucide-react";
import { type SubmitEvent, useEffect, useId, useRef, useState } from "react";

import type { WorkspaceOnboarding } from "@/api/types.gen";
import { type Fact, FactList } from "@/components/auth/FactList";
import { LegalLinks } from "@/components/auth/LegalLinks";
import { StepMarker } from "@/components/auth/StepMarker";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageLayout } from "@/components/core/PageLayout";
import { Section } from "@/components/core/Section";
import { getProviderIcon } from "@/components/icons/provider-icons";
import { HephSays } from "@/components/mentor/HephSays";
import {
	MEMBER_AI_CHOICE_DEFS,
	type MemberAiChoice,
	memberAiChoiceTitle,
} from "@/components/practice-vocabulary/data-handling-defs";
import { statusValues } from "@/components/practice-vocabulary/status-def";
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
	QuestionnaireChoice,
	QuestionnaireChoiceDescription,
	QuestionnaireChoices,
	QuestionnaireDescription,
	QuestionnaireItem,
	QuestionnaireTitle,
} from "@/components/ui/questionnaire";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { openRequiredLinks } from "@/lib/onboarding-links";

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
				/** First visit: dismiss (writes `welcomedAt`) then leave. Return visit: plain navigation, no write. */
				onLeave: () => void;
		  };
}

/**
 * Everything that explains the choice, addressed to every answer at once; the answers themselves
 * carry a title and one sentence each. The stories' meta block has the reasoning.
 */
const AI_FACTS: readonly Fact[] = [
	{
		icon: BotIcon,
		term: "What AI does",
		detail:
			"Practice reviews about your work, and Heph to talk it through — only with AI within your answer. Nothing switches you elsewhere.",
	},
	{
		icon: CircleOffIcon,
		term: "No AI",
		detail:
			"Stops new practice reviews about you, new Heph requests and feedback delivery. Requests already sent cannot be recalled.",
	},
	{
		icon: RefreshCwIcon,
		term: "What never changes",
		detail:
			"Your membership, existing feedback and earlier conversations. Change your answer any time under Your AI choice in the sidebar.",
	},
];

const AI_CHOICES = statusValues(MEMBER_AI_CHOICE_DEFS);

/**
 * What the workspace has set up under this answer's ceiling, per purpose. An answer the server has
 * no option for counts as set up for neither: a card may claim readiness only from the server's
 * word. No AI needs nothing set up, so it is always covered.
 */
function coverage(data: WorkspaceOnboarding, choice: MemberAiChoice) {
	if (choice === "NO_AI") return { practiceReviews: true, mentor: true };
	const option = data.aiOptions.find((entry) => entry.choice === choice);
	return {
		practiceReviews: option?.practiceReviewsReady === true,
		mentor: option?.mentorReady === true,
	};
}

/** The sentence a card appends when part of the answer runs nothing here; `undefined` when all of it runs. */
function readinessSentence(data: WorkspaceOnboarding, choice: MemberAiChoice): string | undefined {
	const { practiceReviews, mentor } = coverage(data, choice);
	if (!practiceReviews && !mentor)
		return "Not set up here yet — nothing runs for you until a workspace owner adds a model.";
	if (!mentor) return "Heph isn't set up for this answer yet.";
	if (!practiceReviews) return "Practice reviews aren't set up for this answer yet.";
	return undefined;
}

function joinNames(names: readonly string[]): string {
	return names.join(" and ");
}

/** One workspace decision, with account linking kept separate from saving the answer. */
export function WorkspaceOnboardingPage({ focus, state }: WorkspaceOnboardingPageProps) {
	const [draft, setDraft] = useState<MemberAiChoice>();
	const id = useId();
	const accountsRef = useRef<HTMLElement>(null);
	const alertRef = useRef<HTMLDivElement>(null);

	const ready = state.status === "ready" ? state : undefined;
	const data = ready?.data;
	const firstVisit = data?.needsWelcome === true;
	const choice = draft ?? data?.aiChoice;
	const changed = choice !== data?.aiChoice;
	const savedCoverage =
		data !== undefined && data.aiChoice != null ? coverage(data, data.aiChoice) : undefined;
	const savedUncovered =
		savedCoverage !== undefined && !(savedCoverage.practiceReviews && savedCoverage.mentor);
	const savedFullyUncovered =
		savedCoverage !== undefined && !savedCoverage.practiceReviews && !savedCoverage.mentor;
	const links = data?.links ?? [];
	const openRequired = openRequiredLinks(links);
	const openRequiredNames = joinNames(openRequired.map((link) => link.displayName));
	const requiredSatisfied = openRequired.length === 0;
	// Saving an AI choice is independent of connecting accounts, even on a first visit.
	const canSubmit = choice !== undefined && (changed || (firstVisit && requiredSatisfied));
	const submission: OnboardingSubmission = ready?.submission ?? { status: "idle" };
	const saving = submission.status === "saving";
	const savingAction = submission.status === "saving" ? submission.action : undefined;
	const submissionStatus = submission.status;
	const accountsMounted = links.length > 0;

	// The Section is not in the DOM until setup has loaded, so this waits for it rather than for
	// mount: after an OAuth round-trip the page is a fresh document and loads before it renders.
	useEffect(() => {
		if (focus === "accounts" && accountsMounted) accountsRef.current?.focus();
	}, [focus, accountsMounted]);

	useEffect(() => {
		if (submissionStatus === "error") alertRef.current?.focus();
	}, [submissionStatus]);

	const heading =
		data === undefined
			? "Workspace setup"
			: firstVisit
				? `Welcome to ${data.workspaceName}`
				: `Your AI choice in ${data.workspaceName}`;

	const intro = `Choose how AI may handle your work in ${data?.workspaceName ?? "this workspace"}. This choice is separate from your account setup and applies only here.`;

	// Heph narrates the reader's answers; the footer hint says the same thing factually and reaches
	// the button through `aria-describedby`, so focusing it does not replay the line.
	const narration =
		state.status === "loading"
			? "Give me a moment — I'm fetching your setup."
			: state.status === "error"
				? "I couldn't fetch your setup just now."
				: changed && firstVisit
					? requiredSatisfied
						? "Noted. Press Continue and I'll remember that."
						: "Save your AI choice now. You can connect your accounts separately."
					: changed
						? "Save to apply your new choice. Requests already sent cannot be recalled."
						: choice === undefined
							? "One question: which AI may handle your work. Any answer is fine by me, including none."
							: savedFullyUncovered
								? "Your choice isn't set up here yet. I won't switch you anywhere else."
								: savedUncovered
									? "Part of your choice isn't set up here yet. I won't switch you anywhere else."
									: !firstVisit
										? `You chose ${memberAiChoiceTitle(choice)}. Change it whenever you like.`
										: openRequired.length > 0
											? `Noted. Connect ${openRequiredNames} and you're in.`
											: "That's everything. Let's get to work.";

	const hint =
		data === undefined
			? undefined
			: choice === undefined
				? firstVisit
					? "Choose an answer to continue."
					: "Choose an answer, then save."
				: firstVisit && openRequired.length > 0
					? changed
						? `Save your AI choice now; connect ${openRequiredNames} to finish setup.`
						: `Connect ${openRequiredNames} to finish setup.`
					: !firstVisit && !changed
						? "You can change this any time from the sidebar."
						: undefined;

	const accountsRequired = links.some((link) => link.required && link.available);

	function submit(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();
		if (ready === undefined || choice === undefined || !canSubmit || saving) return;
		ready.onSubmit(choice);
	}

	return (
		<div className="min-h-svh bg-background">
			<Questionnaire onSubmit={submit}>
				<PageLayout className="max-w-2xl px-6 py-10">
					<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />

					<header className="space-y-4">
						<h1 className="break-words text-2xl font-semibold tracking-tight">{heading}</h1>
						<HephSays intro={intro} narration={narration} />
					</header>

					<Separator />

					{state.status === "loading" ? (
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
					) : state.status === "error" ? (
						<QueryErrorAlert
							error={state.error}
							title="Couldn't load your setup"
							onRetry={state.onRetry}
						/>
					) : (
						<>
							<QuestionnaireItem name="ai-choice" required disabled={saving}>
								<QuestionnaireTitle className="flex items-start gap-3 text-lg">
									<StepMarker icon={SparklesIcon} done={data?.aiChoice != null} />
									Which AI may handle your work?
								</QuestionnaireTitle>
								<QuestionnaireDescription>
									For you, in this workspace only. Allowing a provider also allows in-house models.
									Nothing outside your choice is used.
								</QuestionnaireDescription>
								<FactList facts={AI_FACTS} />
								<QuestionnaireChoices className="gap-3 sm:grid-cols-2">
									{AI_CHOICES.map((value) => {
										const { icon: Icon, label, description } = MEMBER_AI_CHOICE_DEFS[value];
										const readiness = readinessSentence(state.data, value);
										return (
											<QuestionnaireChoice
												key={value}
												value={value}
												checked={choice === value}
												onChange={(event) => {
													if (event.target.checked) setDraft(value);
												}}
												className="p-4"
											>
												<span className="mb-2 flex items-center gap-2 font-medium">
													<Icon className="size-5 shrink-0 text-mentor" aria-hidden="true" />
													{label}
												</span>{" "}
												<QuestionnaireChoiceDescription>
													{description}{" "}
													{readiness && <span className="mt-2 block font-medium">{readiness}</span>}
												</QuestionnaireChoiceDescription>
											</QuestionnaireChoice>
										);
									})}
								</QuestionnaireChoices>
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
												const prefix = link.teamName ? `${link.teamName} · ` : "";
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
																	disabled={!link.available || !link.registrationId || saving}
																	aria-describedby={descriptionId}
																	aria-label={`${changed ? "Save AI choice and connect" : "Connect"} ${link.displayName}`}
																	onClick={() => {
																		if (link.registrationId)
																			state.onLink(link.registrationId, draft);
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
									{hint && (
										<p id={`${id}-hint`} className="text-sm text-muted-foreground">
											{hint}
										</p>
									)}
									{ready !== undefined && (
										<Button
											type="submit"
											disabled={!canSubmit || saving}
											aria-describedby={hint ? `${id}-hint` : undefined}
										>
											{savingAction === "save" && <Spinner />}
											{savingAction === "save"
												? "Saving…"
												: firstVisit && !requiredSatisfied && changed
													? "Save AI choice"
													: firstVisit
														? "Continue"
														: "Save"}
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
									{savingAction === "continue"
										? "Skipping…"
										: firstVisit
											? "Skip for now"
											: "Back to workspace"}
								</Button>
							</footer>
							{firstVisit && (
								<p id={`${id}-skip-hint`} className="text-sm text-muted-foreground">
									Skipping does not save an answer selected above.{" "}
									{data.aiChoice
										? `Your saved choice (${memberAiChoiceTitle(data.aiChoice)}) stays in effect.`
										: "AI stays off until you save a choice."}
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
