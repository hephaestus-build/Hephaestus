import {
	CircleOffIcon,
	ClockIcon,
	FileCheck2Icon,
	FlaskConicalIcon,
	GraduationCapIcon,
	LineChartIcon,
	ShieldCheckIcon,
	Undo2Icon,
} from "lucide-react";
import { useId, useState } from "react";

import type { ConsentStatus } from "@/api/types.gen";
import { AuthWash } from "@/components/auth/AuthWash";
import { LegalLinks } from "@/components/auth/LegalLinks";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
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
		icon: GraduationCapIcon,
		term: "Why it matters",
		detail: "What we learn from real teams is what makes the feedback better.",
	},
	{
		icon: LineChartIcon,
		term: "What you share",
		detail: "How you use Hephaestus, and how you respond to its feedback.",
	},
	{
		icon: ClockIcon,
		term: "What it asks of you",
		detail: "Nothing extra to do. Occasionally, an optional survey.",
	},
];

const ANSWERS = [
	{
		value: "yes",
		icon: FlaskConicalIcon,
		title: "Yes, take part",
		detail: "My usage and feedback data may be used for the research described above.",
	},
	{
		value: "no",
		icon: CircleOffIcon,
		title: "No, don't take part",
		detail: "None of my data is used for research. Everything else works the same.",
	},
] as const;

type Answer = (typeof ANSWERS)[number]["value"];

/** One string for the heading and the group's name, which have to stay identical. */
const RESEARCH_QUESTION = "Take part in the research?";

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
		<div className="relative isolate min-h-svh overflow-hidden bg-background">
			<AuthWash />
			<div className="mx-auto w-full max-w-2xl space-y-8 px-6 py-10 md:py-16">
				<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />

				<header className="space-y-4">
					<span className="inline-flex size-12 items-center justify-center rounded-2xl border bg-muted/50 text-mentor">
						<ShieldCheckIcon className="size-6" aria-hidden="true" />
					</span>
					<div className="space-y-2">
						<p className="text-sm font-medium text-muted-foreground">Account setup</p>
						<h1 className="text-3xl font-semibold tracking-tight text-balance sm:text-4xl">
							Before you continue
						</h1>
					</div>
					{state.status === "ready" && (
						<>
							<p className="max-w-2xl text-base leading-relaxed text-muted-foreground">
								Two things: what Hephaestus does with your data, and whether you want to take part
								in the research.
							</p>
							<div className="flex flex-wrap gap-x-5 gap-y-2 text-xs text-muted-foreground">
								<span className="inline-flex items-center gap-1.5">
									<FileCheck2Icon className="size-4" aria-hidden="true" />
									Recorded with the exact notice you see here
								</span>
								<span className="inline-flex items-center gap-1.5">
									<Undo2Icon className="size-4" aria-hidden="true" />
									Your research answer is reversible in settings
								</span>
							</div>
						</>
					)}
				</header>

				{state.status === "error" ? (
					<QueryErrorAlert
						error={state.error}
						title="Couldn't load the notice"
						onRetry={state.onRetry}
					/>
				) : state.status === "loading" ? (
					<div className="space-y-8" aria-busy="true">
						<span className="sr-only">Loading the notice…</span>
						<Skeleton className="h-72 w-full rounded-2xl" />
						<div className="grid gap-3 sm:grid-cols-2">
							<Skeleton className="h-32 rounded-lg" />
							<Skeleton className="h-32 rounded-lg" />
						</div>
					</div>
				) : (
					<>
						<Section
							title="What you're accepting"
							className="space-y-4 rounded-2xl border bg-card p-5 text-card-foreground sm:p-6"
						>
							{/* The archived notice, verbatim. It is the text the acceptance is recorded against,
							    so nothing may summarise or reorder it here. */}
							<div className="max-w-prose space-y-4 text-sm leading-relaxed">
								{state.notice.noticeText.split("\n\n").map((paragraph, index) => (
									<p key={index}>{paragraph}</p>
								))}
							</div>
							<div className="border-t pt-4">
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

						<Section
							title={RESEARCH_QUESTION}
							description={
								<span id={`${id}-research-help`}>
									Nothing is selected for you, and Hephaestus works exactly the same either way.
								</span>
							}
							className="space-y-5"
						>
							<dl className="grid gap-4 sm:grid-cols-3">
								{RESEARCH_FACTS.map(({ icon: Icon, term, detail }) => (
									<div key={term}>
										<dt className="flex items-center gap-2 text-sm font-medium">
											<span className="inline-flex size-8 shrink-0 items-center justify-center rounded-lg border bg-background text-mentor">
												<Icon className="size-4" aria-hidden="true" />
											</span>
											{term}
										</dt>
										<dd className="mt-2 text-sm leading-relaxed text-muted-foreground">{detail}</dd>
									</div>
								))}
							</dl>

							{/* A `role="radiogroup"` is not named by an enclosing heading, so it needs its own
							    name, and the "nothing is selected for you" guidance has to reach it as a
							    description rather than as loose text beside the answers. */}
							<RadioGroup
								value={answer ?? null}
								onValueChange={(value) => setAnswer(value ?? undefined)}
								disabled={submitting}
								aria-label={RESEARCH_QUESTION}
								aria-describedby={`${id}-research-help`}
								className="grid gap-3 sm:grid-cols-2"
							>
								{ANSWERS.map(({ value, icon: Icon, title, detail }) => (
									// `--mentor`, not `FieldLabel`'s `--primary`, which is near-black in light and
									// near-white in dark — either way a card filled with it reads as disabled
									// rather than chosen. Both themes have to be overridden: the primitive ships
									// `dark:has-data-checked:*` rules that outrank an unprefixed override.
									// oxlint-disable-next-line jsx-a11y/label-has-associated-control -- The rule cannot fold the mapped `title` into label text; the radio is nested and named by `aria-labelledby`.
									<FieldLabel
										key={value}
										htmlFor={`${id}-${value}`}
										className="transition-colors has-data-checked:border-mentor has-data-checked:bg-mentor/5 has-data-unchecked:hover:border-mentor/40 dark:has-data-checked:border-mentor/60 dark:has-data-checked:bg-mentor/10"
									>
										<Field>
											<div className="flex items-center justify-between gap-3">
												<span className="inline-flex size-10 items-center justify-center rounded-xl border bg-background">
													<Icon className="size-5" aria-hidden="true" />
												</span>
												<RadioGroupItem
													id={`${id}-${value}`}
													value={value}
													aria-labelledby={`${id}-${value}-title`}
													aria-describedby={`${id}-${value}-detail`}
												/>
											</div>
											<FieldContent>
												<FieldTitle id={`${id}-${value}-title`} className="text-base font-semibold">
													{title}
												</FieldTitle>
												<FieldDescription id={`${id}-${value}-detail`}>{detail}</FieldDescription>
											</FieldContent>
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

				<footer className="flex flex-col gap-4 border-t pt-5 sm:flex-row-reverse sm:items-center sm:justify-between">
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
			</div>
		</div>
	);
}
