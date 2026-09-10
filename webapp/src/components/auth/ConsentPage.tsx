import { CheckIcon } from "lucide-react";
import { useId, useState } from "react";

import type { ConsentStatus } from "@/api/types.gen";
import { LegalLinks } from "@/components/auth/LegalLinks";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { HephIcon } from "@/components/brand/HephIcon";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageLayout } from "@/components/core/PageLayout";
import { Section } from "@/components/core/Section";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldLabel,
	FieldTitle,
} from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";

export interface ConsentChoice {
	noticeVersion: string;
	termsAccepted: boolean;
	participateInResearch: boolean;
}

export type ConsentSubmission = { status: "idle" } | { status: "saving" } | { status: "error" };

export interface ConsentPageProps {
	onSignOut: () => void;
	state:
		| { status: "loading" }
		| { status: "error"; error: unknown; onRetry: () => void }
		| {
				status: "ready";
				notice: ConsentStatus;
				submission: ConsentSubmission;
				onSubmit: (choice: ConsentChoice) => void;
		  };
}

/**
 * Everything that argues for taking part lives out here rather than inside the "yes" answer. An
 * answer carrying more reasons than its opposite is the asymmetry EDPB 03/2022 calls deceptive.
 */
const RESEARCH_FACTS = [
	{
		term: "Why it matters",
		detail: "What we learn from real teams is what makes the feedback better.",
	},
	{
		term: "What you share",
		detail: "How you use Hephaestus, and how you respond to its feedback.",
	},
	{
		term: "What it asks of you",
		detail: "Nothing extra to do. Occasionally, an optional survey.",
	},
];

const ANSWERS = [
	{
		value: "yes",
		title: "Yes, take part",
		detail: "My usage and feedback data may be used for the research described above.",
	},
	{
		value: "no",
		title: "No, don't take part",
		detail: "None of my data is used for research. Everything else works the same.",
	},
] as const;

type Answer = (typeof ANSWERS)[number]["value"];

/** Hidden: the number is a visual index, and the state it shows is announced by the control it tracks. */
function StepMarker({ step, done }: { step: number; done: boolean }) {
	return (
		<span
			aria-hidden="true"
			className={cn(
				"mt-0.5 inline-flex size-6 shrink-0 items-center justify-center rounded-full border text-xs font-medium transition-colors",
				done ? "border-primary bg-primary text-primary-foreground" : "text-muted-foreground",
			)}
		>
			{done ? <CheckIcon className="size-3.5" /> : step}
		</span>
	);
}

/**
 * The numbers count the two decisions on this screen and nothing beyond it. A member is handed
 * workspace setup afterwards and a changed notice brings an existing account back here, so a counter
 * that claimed to measure the whole of onboarding would be wrong for most of the people reading it.
 */
export function ConsentPage({ state, onSignOut }: ConsentPageProps) {
	const submitting = state.status === "ready" && state.submission.status === "saving";
	const [termsAccepted, setTermsAccepted] = useState(false);
	const [answer, setAnswer] = useState<Answer>();
	const id = useId();

	const ready = state.status === "ready" && termsAccepted && answer !== undefined;

	// Heph narrates, and only Heph is a live region. The footer hint says the same thing factually
	// and reaches the button through `aria-describedby`, so focusing Continue does not replay it.
	const narration =
		state.status === "loading"
			? "Give me a moment — I'm fetching the notice."
			: state.status === "error"
				? "I couldn't fetch the notice just now."
				: termsAccepted && answer !== undefined
					? "That's everything. Let's get to work."
					: termsAccepted
						? "Thanks. One question to go, and either answer is fine by me."
						: answer !== undefined
							? "Noted. Just the terms left."
							: "Two things first: what happens to your data, and whether you'd like to take part in the research.";

	const hint =
		state.status !== "ready"
			? undefined
			: !termsAccepted && answer === undefined
				? "Accept the terms and answer the research question."
				: !termsAccepted
					? "Accept the terms to continue."
					: answer === undefined
						? "Answer the research question to continue."
						: "You can change your research answer later in settings.";

	function submit() {
		if (state.status !== "ready" || !ready || submitting) return;
		state.onSubmit({
			noticeVersion: state.notice.noticeVersion,
			termsAccepted: true,
			participateInResearch: answer === "yes",
		});
	}

	return (
		<div className="min-h-svh bg-background">
			{/* Narrower than `PageLayout`'s default: this surface has no sidebar taking the other half. */}
			<PageLayout className="max-w-2xl px-6 py-10">
				<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />

				{/* Heph's own greeting rather than `PageHeader`: this is the one screen where it speaks,
				    and the app chrome that header belongs to is not up yet. Illustration scale, not icon
				    scale, so it reads as the character talking rather than as the mark printed twice. */}
				<header className="flex items-start gap-4">
					<HephIcon className="shrink-0" size={64} pad={2} />
					<div className="min-w-0 space-y-1">
						<h1 className="break-words text-2xl font-semibold tracking-tight">
							Let's get you set up
						</h1>
						<p className="max-w-2xl text-sm text-muted-foreground">
							I'm Heph. I read the work your team already does and give you feedback on the
							practices your project cares about.
						</p>
						<p aria-live="polite" className="max-w-2xl text-sm font-medium">
							{narration}
						</p>
					</div>
				</header>

				<Separator />

				{state.status === "error" ? (
					<QueryErrorAlert
						error={state.error}
						title="Couldn't load the notice"
						onRetry={state.onRetry}
					/>
				) : state.status === "loading" ? (
					<div className="space-y-6" aria-busy="true">
						<span className="sr-only">Loading the notice…</span>
						<Skeleton className="h-64 w-full" />
						<div className="grid gap-3 sm:grid-cols-2">
							<Skeleton className="h-20" />
							<Skeleton className="h-20" />
						</div>
					</div>
				) : (
					<>
						<Section
							title={
								<span className="flex items-start gap-2">
									<StepMarker step={1} done={termsAccepted} />
									<span className="min-w-0">What Hephaestus does with your data</span>
								</span>
							}
							description="Your acceptance is recorded together with this exact notice."
						>
							<div className="space-y-4 rounded-lg border p-4">
								{/* The archived notice, verbatim. It is the text the acceptance is recorded against,
								    so nothing may summarise or reorder it here. */}
								<div className="max-w-prose space-y-4 text-sm leading-relaxed">
									{state.notice.noticeText.split("\n\n").map((paragraph, index) => (
										<p key={index}>{paragraph}</p>
									))}
								</div>
								<Separator />
								<Field orientation="horizontal">
									<Checkbox
										id={`${id}-terms`}
										checked={termsAccepted}
										disabled={submitting}
										onCheckedChange={setTermsAccepted}
									/>
									<FieldContent>
										<FieldLabel htmlFor={`${id}-terms`}>I accept the terms of use</FieldLabel>
										<FieldDescription>Accepting is not consent to research.</FieldDescription>
									</FieldContent>
								</Field>
							</div>
						</Section>

						<Separator />

						<Section
							id={`${id}-research`}
							title={
								<span className="flex items-start gap-2">
									<StepMarker step={2} done={answer !== undefined} />
									<span className="min-w-0">Take part in the research?</span>
								</span>
							}
							description="Optional, and reversible in settings. Nothing is selected for you, and Hephaestus works exactly the same either way."
						>
							<dl className="grid gap-4 sm:grid-cols-3">
								{RESEARCH_FACTS.map(({ term, detail }) => (
									<div key={term} className="space-y-1">
										<dt className="text-sm font-medium">{term}</dt>
										<dd className="text-sm leading-relaxed text-muted-foreground">{detail}</dd>
									</div>
								))}
							</dl>

							<RadioGroup
								value={answer ?? null}
								onValueChange={(value) => setAnswer(value ?? undefined)}
								disabled={submitting}
								aria-labelledby={`${id}-research-title`}
								aria-describedby={`${id}-research-description`}
								className="grid gap-3 sm:grid-cols-2"
							>
								{ANSWERS.map(({ value, title, detail }) => (
									<FieldLabel key={value} htmlFor={`${id}-${value}`}>
										<Field orientation="horizontal">
											<FieldContent>
												<FieldTitle id={`${id}-${value}-title`}>{title}</FieldTitle>
												<FieldDescription id={`${id}-${value}-detail`}>{detail}</FieldDescription>
											</FieldContent>
											<RadioGroupItem
												id={`${id}-${value}`}
												value={value}
												aria-labelledby={`${id}-${value}-title`}
												aria-describedby={`${id}-${value}-detail`}
											/>
										</Field>
									</FieldLabel>
								))}
							</RadioGroup>
						</Section>

						{state.submission.status === "error" && (
							<Alert variant="destructive">
								<AlertTitle>Your answers weren't saved</AlertTitle>
								<AlertDescription>Please try again.</AlertDescription>
							</Alert>
						)}
					</>
				)}

				<Separator />

				{/* Sign out sits at the far edge from Continue: only one of the two is recoverable. */}
				<footer className="flex flex-col gap-4 sm:flex-row-reverse sm:items-center sm:justify-between">
					<div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:gap-4">
						{hint && (
							<p id={`${id}-hint`} className="text-sm text-muted-foreground">
								{hint}
							</p>
						)}
						{state.status === "ready" && (
							<Button
								disabled={!ready || submitting}
								onClick={submit}
								aria-describedby={`${id}-hint`}
							>
								{submitting && <Spinner />}
								{submitting ? "Saving…" : "Continue"}
							</Button>
						)}
					</div>
					<Button
						variant="ghost"
						disabled={submitting}
						onClick={onSignOut}
						className="self-start text-muted-foreground sm:-ml-3"
					>
						Sign out
					</Button>
				</footer>

				<LegalLinks />
			</PageLayout>
		</div>
	);
}
