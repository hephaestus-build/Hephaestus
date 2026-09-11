import {
	BotIcon,
	CheckIcon,
	ChevronRightIcon,
	Link2Icon,
	RefreshCwIcon,
	ShieldCheckIcon,
	SparklesIcon,
} from "lucide-react";
import { type SubmitEvent, useEffect, useId, useRef, useState } from "react";

import type { WorkspaceOnboarding } from "@/api/types.gen";
import { type Fact, FactList } from "@/components/auth/FactList";
import { LegalLinks } from "@/components/auth/LegalLinks";
import { StepMarker } from "@/components/auth/StepMarker";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { UntrustedMarkdown, UNTRUSTED_MARKDOWN_PROSE } from "@/components/common/UntrustedMarkdown";
import { PageLayout } from "@/components/core/PageLayout";
import { Section } from "@/components/core/Section";
import { getProviderIcon } from "@/components/icons/provider-icons";
import { HephSays } from "@/components/mentor/HephSays";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldLabel,
	FieldTitle,
} from "@/components/ui/field";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemGroup,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import {
	MEMBER_AI_CHOICES,
	type MemberAiChoice,
	memberAiChoiceTitle,
} from "@/lib/llm-processing-location";
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
			"Practice reviews about your work, and Heph to talk it through — only on the location you choose. Nothing switches you elsewhere.",
	},
	{
		icon: ShieldCheckIcon,
		term: "No AI",
		detail:
			"No new practice reviews about you and no new conversations with Heph. A review already running finishes where it started.",
	},
	{
		icon: RefreshCwIcon,
		term: "What never changes",
		detail:
			"Your membership, existing feedback and earlier conversations. Change your answer any time from the sidebar.",
	},
];

const INTRO =
	"I'm Heph. Before I read any of your work here, you decide whether I may — and where.";

function available(data: WorkspaceOnboarding, choice: MemberAiChoice): boolean {
	if (choice === "NO_AI") return true;
	const option = data.aiOptions.find((entry) => entry.choice === choice);
	return option?.practiceReviewsReady === true || option?.mentorReady === true;
}

function joinNames(names: readonly string[]): string {
	return names.join(" and ");
}

/**
 * Page two of `ConsentPage`: the same frame, one question, and no step count — a total would be a
 * claim about a flow this screen cannot see. The three answers sit in one stacked column rather
 * than three across, and nothing divides "No AI" from the two locations: the `FactList` already
 * names that difference, and an "or" divider would weight it a second time. A card's availability
 * is a sentence in its own description rather than a footer row, so an unavailable answer reads
 * the same way as the others, just with one more fact.
 */
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
	const choiceAvailable = data !== undefined && choice !== undefined && available(data, choice);
	const savedUnavailable =
		data !== undefined && data.aiChoice != null && !available(data, data.aiChoice);
	const links = data?.links ?? [];
	const openRequired = openRequiredLinks(links);
	const openRequiredNames = joinNames(openRequired.map((link) => link.displayName));
	const requiredSatisfied = openRequired.length === 0;
	const canSubmit =
		choice !== undefined && choiceAvailable && requiredSatisfied && (firstVisit || changed);
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

	// Heph narrates the reader's answers; the footer hint says the same thing factually and reaches
	// the button through `aria-describedby`, so focusing it does not replay the line.
	const narration =
		state.status === "loading"
			? "Give me a moment — I'm fetching your setup."
			: state.status === "error"
				? "I couldn't fetch your setup just now."
				: changed && firstVisit
					? "Noted. Press Continue and I'll remember that."
					: changed
						? "Save and I'll follow your new answer from the next review on."
						: choice === undefined
							? "One question: where AI runs for you. Any answer is fine by me, including none."
							: savedUnavailable
								? "Your saved choice isn't set up here yet. I won't switch you anywhere else."
								: !firstVisit
									? `You chose ${memberAiChoiceTitle(choice)}. Change it whenever you like.`
									: openRequired.length > 0
										? `Noted. Connect ${openRequiredNames} and you're in.`
										: "That's everything. Let's get to work.";

	const hint =
		data === undefined
			? undefined
			: choice === undefined && data.aiChoiceRequired && firstVisit
				? "Choose how you'd like to use AI to continue. Not now leaves without a choice: no practice reviews about you and no Heph until you make one."
				: choice === undefined
					? firstVisit
						? "Choose how you'd like to use AI to continue."
						: "Choose how you'd like to use AI, then save."
					: !choiceAvailable
						? "That location isn't set up here. Choose another, or No AI."
						: openRequired.length > 0
							? `Connect ${openRequiredNames} to finish setup.`
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
			<form onSubmit={submit}>
				<PageLayout className="max-w-2xl px-6 py-10">
					<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />

					<header className="space-y-4">
						<h1 className="break-words text-2xl font-semibold tracking-tight">{heading}</h1>
						<HephSays intro={INTRO} narration={narration} />
					</header>

					{ready !== undefined && ready.data.welcomeMarkdown.length > 0 && (
						<Collapsible defaultOpen={firstVisit}>
							{/* The disclosure pattern: the heading names the owner's words in the outline, and
							    the button inside it is what opens them. */}
							<h2>
								<CollapsibleTrigger
									render={<Button type="button" variant="ghost" className="group -ml-3" />}
								>
									<ChevronRightIcon aria-hidden="true" className="group-aria-expanded:rotate-90" />
									From your team
								</CollapsibleTrigger>
							</h2>
							<CollapsibleContent className="mt-2 rounded-xl border border-mentor/30 p-4">
								<div className={UNTRUSTED_MARKDOWN_PROSE}>
									<UntrustedMarkdown>{ready.data.welcomeMarkdown}</UntrustedMarkdown>
								</div>
							</CollapsibleContent>
						</Collapsible>
					)}

					<Separator />

					{state.status === "loading" ? (
						<div className="space-y-6" aria-busy="true">
							<span className="sr-only">Loading…</span>
							<Skeleton className="h-24 w-full" />
							<div className="grid gap-3">
								<Skeleton className="h-16" />
								<Skeleton className="h-16" />
								<Skeleton className="h-16" />
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
							<Section
								id={`${id}-ai`}
								title={
									<span className="flex items-start gap-3">
										<StepMarker icon={SparklesIcon} done={choice !== undefined} />
										<span className="min-w-0">Where should AI run for you?</span>
									</span>
								}
								description="Your answer applies to you in this workspace only. Nothing is chosen until you choose."
							>
								<FactList facts={AI_FACTS} />

								<RadioGroup
									value={choice ?? null}
									onValueChange={(value) => setDraft(value ?? undefined)}
									// Base UI swallows Enter on a radio so that it cannot double as a click; a native
									// radio submits its form on Enter, and a keyboard-only reader expects that.
									onKeyDown={(event) => {
										if (event.key === "Enter") event.currentTarget.closest("form")?.requestSubmit();
									}}
									disabled={saving}
									aria-labelledby={`${id}-ai-title`}
									aria-describedby={`${id}-ai-description`}
									className="grid gap-3"
								>
									{MEMBER_AI_CHOICES.map(({ value, title, description }) => {
										const offered = available(state.data, value);
										return (
											<FieldLabel key={value} htmlFor={`${id}-${value}`}>
												<Field orientation="horizontal" data-disabled={!offered || undefined}>
													<FieldContent>
														<FieldTitle id={`${id}-${value}-title`}>{title}</FieldTitle>
														<FieldDescription id={`${id}-${value}-detail`}>
															{offered
																? description
																: `${description} Not set up in this workspace yet — a workspace owner can add it.`}
														</FieldDescription>
													</FieldContent>
													<RadioGroupItem
														id={`${id}-${value}`}
														value={value}
														disabled={!offered}
														aria-labelledby={`${id}-${value}-title`}
														aria-describedby={`${id}-${value}-detail`}
													/>
												</Field>
											</FieldLabel>
										);
									})}
								</RadioGroup>
							</Section>

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
												<StepMarker icon={Link2Icon} done={requiredSatisfied} />
												<span className="min-w-0">Connect your accounts</span>
											</span>
										}
										description={
											accountsRequired
												? "Required to finish setup."
												: "Optional. You can do this later from settings."
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
															<Icon />
														</ItemMedia>
														<ItemContent>
															<ItemTitle>{link.displayName}</ItemTitle>
															{/* The reason a link cannot be connected has to be readable in full. */}
															<ItemDescription id={descriptionId} className="line-clamp-none">
																{rowDescription}
															</ItemDescription>
														</ItemContent>
														<ItemActions>
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
																	onClick={() => {
																		if (link.registrationId)
																			state.onLink(link.registrationId, draft);
																	}}
																>
																	Connect {link.displayName}
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
											{savingAction === "save" ? "Saving…" : firstVisit ? "Continue" : "Save"}
										</Button>
									)}
								</div>
								<Button
									type="button"
									variant="ghost"
									disabled={saving}
									onClick={state.onLeave}
									className="self-start text-muted-foreground sm:-ml-3"
								>
									{savingAction === "continue" && <Spinner />}
									{savingAction === "continue"
										? "Leaving…"
										: firstVisit
											? "Not now"
											: "Back to workspace"}
								</Button>
							</footer>
						</>
					)}

					<LegalLinks />
				</PageLayout>
			</form>
		</div>
	);
}
