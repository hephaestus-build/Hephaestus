import {
	ArrowRight,
	Check,
	CheckCircle2,
	Cloud,
	Link2,
	Server,
	ShieldCheck,
	Sparkles,
	CircleOff,
} from "lucide-react";
import { useId, useState } from "react";

import type { WorkspaceOnboarding } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { PageLayout } from "@/components/core/PageLayout";
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
		detail: "Only models assigned to this location in this workspace.",
	},
	{
		value: "PRIVATE_CLOUD",
		title: "Private cloud",
		icon: Cloud,
		description: "AI runs in your workspace’s configured private cloud environment.",
		detail: "Only models assigned to this location in this workspace.",
	},
	{
		value: "NO_AI",
		title: "No AI",
		icon: CircleOff,
		description: "Use the workspace without personal AI assistance.",
		detail: "No new practice reviews about you and no conversations with Heph.",
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
	| { status: "ready"; data: WorkspaceOnboarding };

export interface WorkspaceOnboardingPageProps {
	state: PageState;
	pending?: "choice" | "completion" | "dismissal";
	saveError?: string;
	onChoose: (choice: Choice) => void;
	onLink: (registrationId: string) => void;
	onRefresh: () => void;
	onComplete: () => void;
	onDismiss: () => void;
}

export function WorkspaceOnboardingPage(props: WorkspaceOnboardingPageProps) {
	if (props.state.status === "loading")
		return (
			<PageLayout
				className="max-w-5xl space-y-8"
				aria-label="Loading workspace setup"
				aria-busy="true"
			>
				<div className="space-y-4">
					<Skeleton className="h-12 w-12 rounded-2xl" />
					<Skeleton className="h-10 w-3/4" />
					<Skeleton className="h-5 w-1/2" />
				</div>
				<div className="grid gap-4 md:grid-cols-3">
					{[0, 1, 2].map((key) => (
						<Skeleton key={key} className="h-72 rounded-2xl" />
					))}
				</div>
				<Skeleton className="h-32 rounded-2xl" />
			</PageLayout>
		);
	if (props.state.status === "error")
		return (
			<QueryErrorAlert
				error={props.state.error}
				title="Couldn't load workspace setup"
				onRetry={props.state.onRetry}
			/>
		);
	return (
		<WorkspaceSetup
			key={`${props.state.data.workspaceName}:${props.state.data.aiChoice ?? "unset"}`}
			{...props}
			data={props.state.data}
		/>
	);
}

function WorkspaceSetup({
	data,
	pending,
	saveError,
	onChoose,
	onLink,
	onRefresh,
	onComplete,
	onDismiss,
}: WorkspaceOnboardingPageProps & { data: WorkspaceOnboarding }) {
	const [choice, setChoice] = useState<Choice | undefined>(data.aiChoice);
	const id = useId();
	const allRequiredLinked = data.links.filter((link) => link.required).every((link) => link.linked);
	const changed = choice !== data.aiChoice;
	const selectedOption = data.aiOptions.find((option) => option.choice === choice);
	const choiceAvailable =
		choice === "NO_AI" ||
		selectedOption?.practiceReviewsReady === true ||
		selectedOption?.mentorReady === true;
	const canFinish = data.aiChoice != null && !changed && allRequiredLinked;
	const isChoice = (value: string): value is Choice =>
		CHOICES.some((option) => option.value === value);

	return (
		<PageLayout className="max-w-5xl space-y-8 pb-6">
			<header className="space-y-4">
				<div className="inline-flex size-12 items-center justify-center rounded-2xl border bg-muted/50 text-primary">
					<Sparkles className="size-6" aria-hidden />
				</div>
				<div className="space-y-2">
					<p className="text-sm font-medium text-muted-foreground">Workspace setup</p>
					<h1 className="break-words text-3xl font-semibold tracking-tight sm:text-4xl">
						{data.completed
							? `Your preferences in ${data.workspaceName}`
							: `Welcome to ${data.workspaceName}`}
					</h1>
					<p className="max-w-2xl text-base leading-relaxed text-muted-foreground">
						Get to know your workspace, choose how AI helps you, and connect the accounts your team
						uses. Your membership is already in place.
					</p>
				</div>
				<div className="flex flex-wrap gap-x-5 gap-y-2 text-xs text-muted-foreground">
					<span className="inline-flex items-center gap-1.5">
						<ShieldCheck className="size-4" aria-hidden /> Only this workspace
					</span>
					<span className="inline-flex items-center gap-1.5">
						<CheckCircle2 className="size-4" aria-hidden /> Change your choice at any time
					</span>
				</div>
			</header>

			{data.welcomeMarkdown && (
				<section
					aria-labelledby={`${id}-welcome`}
					className="rounded-2xl border bg-muted/30 p-5 sm:p-6"
				>
					<h2 id={`${id}-welcome`} className="mb-3 text-lg font-semibold">
						A welcome from your team
					</h2>
					<section aria-labelledby={`${id}-team-notes`}>
						<h3 id={`${id}-team-notes`} className="sr-only">
							Workspace guidance
						</h3>
						<UntrustedMarkdown>{data.welcomeMarkdown}</UntrustedMarkdown>
					</section>
				</section>
			)}

			<section aria-labelledby={`${id}-ai-title`} className="space-y-4">
				<div>
					<h2 id={`${id}-ai-title`} className="text-xl font-semibold">
						How would you like to use AI?
					</h2>
					<p
						id={`${id}-ai-help`}
						className="mt-1 max-w-3xl text-sm leading-relaxed text-muted-foreground"
					>
						This controls practice reviews about you and conversations with Heph. Nothing is
						selected for you, and we never switch processing locations without your choice.
					</p>
				</div>
				<RadioGroup
					value={choice ?? null}
					onValueChange={(value) => {
						if (typeof value === "string" && isChoice(value)) setChoice(value);
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
						const selected = choice === option.value;
						return (
							<label
								key={option.value}
								htmlFor={`${id}-${option.value}`}
								className={cn(
									"relative flex min-w-0 cursor-pointer flex-col rounded-2xl border bg-card p-5 transition-colors focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2",
									selected
										? "border-primary bg-primary/5 ring-1 ring-primary"
										: "hover:border-primary/40",
									!available && "cursor-not-allowed bg-muted/30",
								)}
							>
								<div className="mb-5 flex items-center justify-between gap-3">
									<span className="inline-flex size-10 items-center justify-center rounded-xl border bg-background">
										<Icon className="size-5" aria-hidden />
									</span>
									<RadioGroupItem
										id={`${id}-${option.value}`}
										value={option.value}
										disabled={!available}
										aria-labelledby={`${id}-${option.value}-title`}
										aria-describedby={`${id}-${option.value}-detail`}
									/>
								</div>
								<span id={`${id}-${option.value}-title`} className="text-base font-semibold">
									{option.title}
								</span>
								<span className="mt-2 text-sm leading-relaxed text-muted-foreground">
									{option.description}
								</span>
								<span
									id={`${id}-${option.value}-detail`}
									className="mt-4 border-t pt-4 text-xs leading-relaxed text-muted-foreground"
								>
									{option.detail}
								</span>
								<span className="mt-auto pt-4 text-xs font-medium">
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
				<div className="flex flex-wrap items-center gap-3">
					<Button
						disabled={!choice || !changed || !choiceAvailable || Boolean(pending)}
						onClick={() => {
							if (choice) onChoose(choice);
						}}
					>
						{pending === "choice" && <Spinner />}
						{pending === "choice" ? "Saving preference…" : "Save AI preference"}
					</Button>
					{data.aiChoice && !changed && (
						<p className="text-sm text-muted-foreground">
							Your preference is saved for this workspace.
						</p>
					)}
				</div>
				<p className="max-w-3xl text-xs leading-relaxed text-muted-foreground">
					Existing feedback stays available. This choice does not remove your work from shared
					repositories or change other members’ settings. A model request already in progress cannot
					be recalled; your new choice applies to subsequent requests. Research participation is a
					separate setting.
				</p>
			</section>

			<section
				aria-labelledby={`${id}-accounts`}
				className="space-y-4 rounded-2xl border p-5 sm:p-6"
			>
				<div className="flex flex-wrap items-start justify-between gap-3">
					<div>
						<h2 id={`${id}-accounts`} className="text-lg font-semibold">
							Connect your workspace accounts
						</h2>
						<p className="mt-1 text-sm text-muted-foreground">
							Linking identifies you on your team’s services. It does not enable AI.
						</p>
					</div>
					{data.links.length > 0 && (
						<Button variant="outline" size="sm" onClick={onRefresh} disabled={Boolean(pending)}>
							Refresh connections
						</Button>
					)}
				</div>
				{data.links.length === 0 ? (
					<p className="text-sm text-muted-foreground">
						Your workspace has no additional accounts to connect. You’re all set here.
					</p>
				) : (
					<ul className="divide-y">
						{data.links.map((link) => (
							<li
								key={link.connectionId}
								className="flex flex-wrap items-center justify-between gap-3 py-4 first:pt-0 last:pb-0"
							>
								<div className="flex min-w-0 items-start gap-3">
									<span className="mt-0.5 rounded-lg bg-muted p-2">
										<Link2 className="size-4" aria-hidden />
									</span>
									<div className="min-w-0">
										<p className="break-words text-sm font-medium">{link.displayName}</p>
										<p className="break-words text-xs text-muted-foreground">
											{link.teamName ? `${link.teamName} · ` : ""}
											{link.required ? "Required to finish setup" : "Optional"}
										</p>
										{!link.available && (
											<p className="mt-1 text-xs text-muted-foreground">
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
										size="sm"
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
				)}
			</section>

			{saveError && (
				<Alert variant="destructive">
					<AlertTitle>Couldn't save your setup</AlertTitle>
					<AlertDescription>{saveError}</AlertDescription>
				</Alert>
			)}
			<footer className="flex flex-wrap items-center justify-between gap-4 border-t pt-5">
				<div className="max-w-xl text-sm text-muted-foreground">
					{!allRequiredLinked
						? "Finish connecting the required accounts, or continue and return to setup later."
						: !data.aiChoice
							? "Choose an AI preference, or continue without enabling AI for yourself."
							: "You can return to Workspace preferences at any time."}
				</div>
				<div className="flex flex-wrap gap-2">
					<Button variant="ghost" onClick={onDismiss} disabled={Boolean(pending)}>
						{pending === "dismissal" ? "Continuing…" : "Not now"}
					</Button>
					<Button onClick={onComplete} disabled={!canFinish || Boolean(pending)}>
						{pending === "completion" ? <Spinner /> : <ArrowRight aria-hidden />}
						{pending === "completion" ? "Finishing…" : "Continue to workspace"}
					</Button>
				</div>
			</footer>
		</PageLayout>
	);
}
