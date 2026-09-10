import { ShieldCheckIcon } from "lucide-react";
import { useId, useState } from "react";

import type { ConsentStatus } from "@/api/types.gen";
import { LegalLinks } from "@/components/auth/LegalLinks";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
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

/**
 * The page deliberately does not number itself. A member is handed workspace setup after this, and a
 * changed notice brings an existing user back through it, so "step 1 of n" is a claim this screen
 * cannot make about a flow it cannot see.
 */
export function ConsentPage({ state, onSignOut }: ConsentPageProps) {
	const submitting = state.status === "ready" && state.submission.status === "saving";
	const [termsAccepted, setTermsAccepted] = useState(false);
	const [answer, setAnswer] = useState<Answer>();
	const id = useId();

	const ready = state.status === "ready" && termsAccepted && answer !== undefined;
	const outstanding =
		state.status !== "ready"
			? undefined
			: !termsAccepted
				? "Accept the terms, then answer the research question."
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
			<PageLayout className="max-w-3xl px-6 py-10">
				<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />

				<PageHeader
					icon={<ShieldCheckIcon />}
					title="Before you continue"
					description="Two things: what Hephaestus does with your data, and whether you want to take part in the research."
				/>

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
							title="What you're accepting"
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
							title="Take part in the research?"
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
						{outstanding && <p className="text-sm text-muted-foreground">{outstanding}</p>}
						{state.status === "ready" && (
							<Button disabled={!ready || submitting} onClick={submit}>
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
