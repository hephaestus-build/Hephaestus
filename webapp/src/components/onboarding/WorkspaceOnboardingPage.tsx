import { Link } from "@tanstack/react-router";
import {
	ArrowLeft,
	ArrowRight,
	Check,
	CheckCircle2,
	CircleOff,
	Cloud,
	Link2,
	Server,
	ShieldCheck,
} from "lucide-react";
import { type ReactNode, useEffect, useId, useRef, useState } from "react";

import type { WorkspaceOnboarding } from "@/api/types.gen";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { UntrustedMarkdown, UNTRUSTED_MARKDOWN_PROSE } from "@/components/common/UntrustedMarkdown";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";

type Choice = NonNullable<WorkspaceOnboarding["aiChoice"]>;
const CHOICES = [
	{
		value: "ON_PREMISES",
		title: "On-premises",
		icon: Server,
		description: "AI runs on infrastructure managed by your organization.",
		detail: "Uses models assigned to this location by your workspace.",
	},
	{
		value: "PRIVATE_CLOUD",
		title: "Private cloud",
		icon: Cloud,
		description: "AI runs in your workspace’s configured private cloud environment.",
		detail: "Uses models assigned to this location by your workspace.",
	},
	{
		value: "NO_AI",
		title: "No AI",
		icon: CircleOff,
		description: "Use your workspace without personal AI assistance.",
		detail: "No new practice reviews about you or new conversations with Heph.",
	},
] satisfies {
	value: Choice;
	title: string;
	icon: typeof Server;
	description: string;
	detail: string;
}[];

type PageState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			data: WorkspaceOnboarding;
			refresh?: { status: "pending" } | { status: "error"; error: unknown; onRetry: () => void };
	  };

export interface WorkspaceOnboardingPageProps {
	state: PageState;
	initialStep?: "choice" | "accounts";
	pending?: "choice" | "completion" | "dismissal";
	saveError?: string;
	onChoose: (choice: Choice) => Promise<boolean>;
	onLink: (registrationId: string) => void;
	onRefresh: () => void;
	onComplete: () => void;
	onDismiss: () => void;
}

function WorkspaceOnboardingFrame({ children }: { children: ReactNode }) {
	return (
		<div className="relative isolate flex min-h-svh flex-col items-center gap-6 bg-linear-to-br from-primary/5 via-background to-mentor/5 px-4 py-8 sm:px-8 sm:py-12">
			<div
				aria-hidden
				className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(var(--border)_1px,transparent_1px)] bg-size-[24px_24px] opacity-30"
			/>
			<HephaestusLogo markClassName="size-8" wordmarkClassName="text-xl" />
			<div className="w-full max-w-4xl rounded-3xl border bg-card p-5 shadow-sm sm:p-8">
				{children}
			</div>
			<p className="flex flex-wrap justify-center gap-x-4 gap-y-2 text-xs text-muted-foreground">
				<Link
					to="/privacy"
					target="_blank"
					rel="noopener noreferrer"
					className="underline underline-offset-4 hover:text-foreground"
				>
					Privacy notice (opens in a new tab)
				</Link>
				<Link
					to="/imprint"
					target="_blank"
					rel="noopener noreferrer"
					className="underline underline-offset-4 hover:text-foreground"
				>
					Imprint (opens in a new tab)
				</Link>
			</p>
		</div>
	);
}

export function WorkspaceOnboardingPage(props: WorkspaceOnboardingPageProps) {
	return (
		<WorkspaceOnboardingFrame>
			{props.state.status === "loading" ? (
				<div
					role="region"
					aria-label="Loading workspace setup"
					aria-busy="true"
					className="space-y-6"
				>
					<span className="sr-only">Loading workspace setup…</span>
					<Skeleton className="h-5 w-40" />
					<Skeleton className="h-10 w-3/4" />
					<Skeleton className="h-5 w-full" />
					<div className="grid gap-3 md:grid-cols-3">
						{[0, 1, 2].map((key) => (
							<Skeleton key={key} className="h-72 rounded-2xl" />
						))}
					</div>
					<Skeleton className="h-10 w-48" />
				</div>
			) : props.state.status === "error" ? (
				<div className="space-y-6">
					<h1 className="text-2xl font-semibold">Workspace setup</h1>
					<QueryErrorAlert
						error={props.state.error}
						title="Couldn't load workspace setup"
						onRetry={props.state.onRetry}
					/>
					<div aria-live="polite" aria-atomic="true">
						{props.saveError && (
							<Alert variant="destructive">
								<AlertTitle>Couldn't continue to your workspace</AlertTitle>
								<AlertDescription>{props.saveError}</AlertDescription>
							</Alert>
						)}
					</div>
					<Button variant="ghost" onClick={props.onDismiss} disabled={Boolean(props.pending)}>
						{props.pending === "dismissal" && <Spinner />}
						{props.pending === "dismissal" ? "Continuing…" : "Not now"}
					</Button>
				</div>
			) : (
				<WorkspaceSetup {...props} data={props.state.data} />
			)}
		</WorkspaceOnboardingFrame>
	);
}

function WorkspaceSetup({
	state,
	data,
	initialStep,
	pending,
	saveError,
	onChoose,
	onLink,
	onRefresh,
	onComplete,
	onDismiss,
}: WorkspaceOnboardingPageProps & { data: WorkspaceOnboarding }) {
	const [step, setStep] = useState(
		initialStep === "accounts" && data.aiChoice ? "accounts" : "choice",
	);
	const [draftChoice, setDraftChoice] = useState<Choice>();
	const choice = draftChoice ?? data.aiChoice;
	const headingRef = useRef<HTMLHeadingElement>(null);
	const id = useId();
	const isChoiceStep = step === "choice";
	const changed = choice !== data.aiChoice;
	const selectedOption = data.aiOptions.find((option) => option.choice === choice);
	const choiceAvailable =
		choice === "NO_AI" ||
		selectedOption?.practiceReviewsReady === true ||
		selectedOption?.mentorReady === true;
	const allRequiredLinked = data.links.every((link) => !link.required || link.linked);
	const savedChoice = CHOICES.find((option) => option.value === data.aiChoice);
	const savedLocation = data.aiOptions.find((option) => option.choice === data.aiChoice);
	const savedLocationUnavailable =
		data.aiChoice !== "NO_AI" &&
		!savedLocation?.practiceReviewsReady &&
		!savedLocation?.mentorReady;
	const refresh = state.status === "ready" ? state.refresh : undefined;
	const canFinish = data.aiChoice != null && allRequiredLinked;

	// A saved preference must not remount the page and strand keyboard focus on the document body.
	useEffect(() => {
		headingRef.current?.focus();
	}, [step]);

	async function saveChoice() {
		if (choice && (await onChoose(choice))) {
			setDraftChoice(undefined);
			setStep("accounts");
		}
	}

	return (
		<div className="space-y-6">
			<header className="space-y-4">
				<div className="flex flex-wrap items-center justify-between gap-3 text-xs font-medium text-muted-foreground">
					<p>Workspace setup · Step {isChoiceStep ? 1 : 2} of 2</p>
					<span className="inline-flex items-center gap-1.5">
						<ShieldCheck className="size-4" aria-hidden /> Only this workspace
					</span>
				</div>
				<ol aria-label="Workspace setup progress" className="grid grid-cols-2 gap-3 text-sm">
					<li
						aria-current={isChoiceStep ? "step" : undefined}
						className={cn(
							"border-t-2 pt-2",
							isChoiceStep
								? "border-primary font-medium text-foreground"
								: "border-border text-muted-foreground",
						)}
					>
						1. Your AI choice
					</li>
					<li
						aria-current={!isChoiceStep ? "step" : undefined}
						className={cn(
							"border-t-2 pt-2",
							!isChoiceStep
								? "border-primary font-medium text-foreground"
								: "border-border text-muted-foreground",
						)}
					>
						2. {data.links.length ? "Connect accounts" : "Ready to go"}
					</li>
				</ol>
				<h1
					ref={headingRef}
					tabIndex={-1}
					className="break-words text-3xl font-semibold tracking-tight outline-none sm:text-4xl"
				>
					{isChoiceStep
						? data.completed
							? `Your preferences in ${data.workspaceName}`
							: `Welcome to ${data.workspaceName}`
						: data.links.length
							? "Connect your workspace accounts"
							: "You're ready"}
				</h1>
				<p className="max-w-prose text-sm leading-relaxed text-muted-foreground">
					{isChoiceStep
						? "Choose whether AI reviews your work against your team’s practices and helps you reflect with Heph. You’re already a member, whichever option you choose."
						: data.links.length
							? "Connect your identity on the services your workspace uses. Linking an account does not enable AI or change your research choice."
							: "Your AI preference is saved. There are no additional accounts to connect in this workspace."}
				</p>
			</header>

			{isChoiceStep ? (
				<>
					{data.welcomeMarkdown && (
						<details className="rounded-xl border bg-muted/30 p-4">
							<summary className="cursor-pointer text-sm font-medium">
								A welcome from your team
							</summary>
							<div className={cn(UNTRUSTED_MARKDOWN_PROSE, "mt-4")}>
								<UntrustedMarkdown>{data.welcomeMarkdown}</UntrustedMarkdown>
							</div>
						</details>
					)}
					<section aria-labelledby={`${id}-ai-title`} className="space-y-4">
						<div className="space-y-2">
							<h2 id={`${id}-ai-title`} className="text-xl font-semibold">
								How would you like to use AI?
							</h2>
							<p
								id={`${id}-ai-help`}
								className="max-w-prose text-sm leading-relaxed text-muted-foreground"
							>
								This is your AI preference, separate from research participation.{" "}
								{data.aiChoiceRequired || data.aiChoice
									? "We never switch processing locations without your choice."
									: "Until you save a preference, your workspace’s existing AI defaults apply. Choose No AI to stop personal AI activity."}
							</p>
						</div>
						<RadioGroup
							value={choice ?? null}
							onValueChange={(value) => {
								const option = CHOICES.find((entry) => entry.value === value);
								if (option)
									setDraftChoice(option.value === data.aiChoice ? undefined : option.value);
							}}
							aria-labelledby={`${id}-ai-title`}
							aria-describedby={`${id}-ai-help`}
							disabled={Boolean(pending)}
							className="grid items-stretch gap-3 md:grid-cols-3"
						>
							{CHOICES.map((option) => {
								const availability = data.aiOptions.find((entry) => entry.choice === option.value);
								const available =
									option.value === "NO_AI" ||
									availability?.practiceReviewsReady === true ||
									availability?.mentorReady === true;
								const Icon = option.icon;
								return (
									<label
										key={option.value}
										htmlFor={`${id}-${option.value}`}
										className={cn(
											"flex min-w-0 cursor-pointer flex-col rounded-2xl border bg-background p-4 transition-colors focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2",
											choice === option.value
												? "border-primary bg-primary/5 ring-1 ring-primary"
												: "hover:border-primary/40",
											!available && "cursor-not-allowed bg-muted/30",
										)}
									>
										<span className="mb-4 flex items-center justify-between gap-3">
											<Icon className="size-6 text-muted-foreground" aria-hidden />
											<RadioGroupItem
												id={`${id}-${option.value}`}
												value={option.value}
												disabled={!available}
												aria-labelledby={`${id}-${option.value}-title`}
												aria-describedby={`${id}-${option.value}-detail ${id}-${option.value}-availability`}
											/>
										</span>
										<span id={`${id}-${option.value}-title`} className="font-semibold">
											{option.title}
										</span>
										<span className="mt-2 text-sm leading-relaxed text-muted-foreground">
											{option.description}
										</span>
										<span
											id={`${id}-${option.value}-detail`}
											className="mt-4 text-xs leading-relaxed text-muted-foreground"
										>
											{option.detail}
										</span>
										<span
											id={`${id}-${option.value}-availability`}
											className="mt-auto border-t pt-3 text-xs font-medium"
										>
											{option.value === "NO_AI"
												? "Always available · membership unchanged"
												: !available
													? "Not available in this workspace"
													: [
															availability?.practiceReviewsReady && "Practice reviews",
															availability?.mentorReady && "Heph",
														]
															.filter(Boolean)
															.join(" · ")}
										</span>
										{data.aiChoice === option.value && (
											<span className="mt-2 inline-flex items-center gap-1 text-xs font-medium">
												<Check className="size-3.5" aria-hidden /> Saved choice
												{!available ? " · currently unavailable" : ""}
											</span>
										)}
									</label>
								);
							})}
						</RadioGroup>
						<p className="text-sm text-muted-foreground">
							{!data.aiChoice && "Nothing is selected for you. "}
							You can change your preference at any time.
						</p>
					</section>
					<details className="text-sm text-muted-foreground">
						<summary className="cursor-pointer font-medium text-foreground">
							What this choice does—and doesn’t—change
						</summary>
						<p className="mt-3 max-w-prose leading-relaxed">
							Existing feedback and earlier conversations stay available. This choice does not
							remove your work from shared repositories or change other members’ settings. A model
							request already in progress cannot be recalled; your new choice applies to subsequent
							requests.
						</p>
					</details>
				</>
			) : (
				<>
					<div className="flex items-start gap-3 rounded-xl border bg-muted/30 p-4">
						<CheckCircle2 className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden />
						<div>
							<p className="font-medium">{savedChoice?.title} · Saved choice</p>
							<p className="mt-1 text-sm text-muted-foreground">
								{savedChoice?.detail} You can change this in Workspace preferences.
							</p>
							{savedLocationUnavailable && (
								<p className="mt-2 text-sm font-medium">
									Your saved location is currently unavailable. No other processing location will be
									used.
								</p>
							)}
						</div>
					</div>
					{data.links.length > 0 && (
						<section aria-label="Workspace accounts" className="space-y-4">
							<ul className="divide-y">
								{data.links.map((link) => (
									<li
										key={link.connectionId}
										className="flex flex-wrap items-center justify-between gap-3 py-4"
									>
										<div className="flex min-w-0 items-start gap-3">
											<Link2 className="mt-1 size-5 shrink-0 text-muted-foreground" aria-hidden />
											<div className="min-w-0">
												<p className="break-words font-medium">{link.displayName}</p>
												<p className="break-words text-sm text-muted-foreground">
													{link.teamName ? `${link.teamName} · ` : ""}
													{link.required ? "Required to finish setup" : "Optional"}
												</p>
												{!link.available && !link.linked && (
													<p className="mt-1 text-sm text-muted-foreground">
														Unavailable. Ask a workspace owner to check this integration.
													</p>
												)}
											</div>
										</div>
										{link.linked ? (
											<span className="inline-flex items-center gap-1.5 text-sm">
												<CheckCircle2 className="size-4" aria-hidden /> Connected
											</span>
										) : (
											<Button
												variant="outline"
												disabled={!link.available || !link.registrationId || Boolean(pending)}
												onClick={() => {
													if (link.registrationId) onLink(link.registrationId);
												}}
											>
												Connect {link.displayName}
											</Button>
										)}
									</li>
								))}
							</ul>
							<Button
								variant="outline"
								size="sm"
								onClick={onRefresh}
								disabled={Boolean(pending) || refresh?.status === "pending"}
							>
								{refresh?.status === "pending" && <Spinner />}
								{refresh?.status === "pending" ? "Refreshing connections…" : "Refresh connections"}
							</Button>
						</section>
					)}
				</>
			)}

			<div aria-live="polite" aria-atomic="true">
				{refresh?.status === "error" && (
					<QueryErrorAlert
						error={refresh.error}
						title="Couldn't refresh workspace setup"
						onRetry={refresh.onRetry}
					/>
				)}
				{saveError && (
					<Alert variant="destructive">
						<AlertTitle>Couldn't save your setup</AlertTitle>
						<AlertDescription>{saveError}</AlertDescription>
					</Alert>
				)}
			</div>
			<footer className="space-y-4 border-t pt-5">
				<p className="max-w-prose text-xs leading-relaxed text-muted-foreground">
					{!isChoiceStep && !allRequiredLinked
						? "Finish connecting the required accounts, or continue and return to setup later."
						: !data.aiChoice
							? data.aiChoiceRequired
								? "Choose an AI preference, or continue without enabling AI for yourself."
								: "Choose an AI preference, or continue with your workspace’s existing AI defaults."
							: "You can return to Workspace preferences at any time."}
				</p>
				<div className="flex flex-wrap items-center gap-3">
					{!isChoiceStep && (
						<Button variant="outline" onClick={() => setStep("choice")} disabled={Boolean(pending)}>
							<ArrowLeft aria-hidden /> Back
						</Button>
					)}
					<Button variant="ghost" onClick={onDismiss} disabled={Boolean(pending)}>
						{pending === "dismissal" && <Spinner />}
						{pending === "dismissal" ? "Continuing…" : "Not now"}
					</Button>
					{isChoiceStep ? (
						<div className="ml-auto flex flex-wrap gap-3">
							<Button
								disabled={!choice || !changed || !choiceAvailable || Boolean(pending)}
								onClick={() => {
									void saveChoice();
								}}
							>
								{pending === "choice" && <Spinner />}
								{pending === "choice" ? "Saving preference…" : "Save AI preference"}
							</Button>
							{data.aiChoice && !changed && (
								<Button onClick={() => setStep("accounts")} disabled={Boolean(pending)}>
									Continue <ArrowRight aria-hidden />
								</Button>
							)}
						</div>
					) : (
						<Button
							className="ml-auto"
							onClick={onComplete}
							disabled={!canFinish || Boolean(pending)}
						>
							{pending === "completion" ? <Spinner /> : <ArrowRight aria-hidden />}
							{pending === "completion" ? "Finishing…" : "Continue to workspace"}
						</Button>
					)}
				</div>
			</footer>
		</div>
	);
}
