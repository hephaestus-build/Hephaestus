import {
	ClockIcon,
	CircleOffIcon,
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
import { LegalLink } from "@/components/auth/LegalLinks";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageLayout } from "@/components/core/PageLayout";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
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
 * What the research asks of the reader, stated once and outside both answers. Anything that argues
 * for taking part has to sit here rather than inside the "yes" card: an answer that carries more
 * reasons than its opposite is the asymmetry EDPB 03/2022 calls deceptive, whatever the copy says.
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

/**
 * First-login transparency and consent, as one page with two separate decisions rather than a
 * wizard. Accepting the terms is a condition of use; the research answer is consent, and consent has
 * to be refusable at no cost — so the two live in their own sections, neither answer is preselected,
 * and both answers cost the same click.
 *
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
	const outstanding = !termsAccepted
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
			<PageLayout className="max-w-2xl space-y-8 px-6 py-10 md:py-16">
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
							<Skeleton className="h-32 rounded-2xl" />
							<Skeleton className="h-32 rounded-2xl" />
						</div>
					</div>
				) : (
					<>
						<section
							aria-labelledby={`${id}-notice`}
							className="space-y-4 rounded-2xl border bg-card p-5 sm:p-6"
						>
							<h2 id={`${id}-notice`} className="text-xl font-semibold">
								What you're accepting
							</h2>
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
						</section>

						<section aria-labelledby={`${id}-research`} className="space-y-5">
							<div>
								<h2 id={`${id}-research`} className="text-xl font-semibold">
									Take part in the research?
								</h2>
								<p id={`${id}-research-help`} className="mt-1 text-sm text-muted-foreground">
									Nothing is selected for you, and Hephaestus works exactly the same either way.
								</p>
							</div>

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

							<RadioGroup
								value={answer ?? null}
								onValueChange={(value) => {
									if (value === "yes" || value === "no") setAnswer(value);
								}}
								disabled={submitting}
								aria-labelledby={`${id}-research`}
								aria-describedby={`${id}-research-help`}
								className="grid items-stretch gap-3 sm:grid-cols-2"
							>
								{ANSWERS.map(({ value, icon: Icon, title, detail }) => (
									<label
										key={value}
										htmlFor={`${id}-${value}`}
										className={cn(
											"flex min-w-0 cursor-pointer flex-col rounded-2xl border bg-card p-5 transition-colors focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2",
											// `--mentor`, not `--primary`: primary is near-black here, so a card filled
											// with it reads as disabled rather than chosen.
											answer === value
												? "border-mentor bg-mentor/5 ring-1 ring-mentor"
												: "hover:border-mentor/40",
										)}
									>
										<div className="mb-5 flex items-center justify-between gap-3">
											<span className="inline-flex size-10 items-center justify-center rounded-xl border bg-background">
												<Icon className="size-5" aria-hidden="true" />
											</span>
											<RadioGroupItem
												id={`${id}-${value}`}
												value={value}
												aria-labelledby={`${id}-${value}-title`}
											/>
										</div>
										<span id={`${id}-${value}-title`} className="text-base font-semibold">
											{title}
										</span>
										<span className="mt-2 text-sm leading-relaxed text-muted-foreground">
											{detail}
										</span>
									</label>
								))}
							</RadioGroup>
						</section>

						{state.submission.status === "error" && (
							<Alert variant="destructive">
								<AlertTitle>Your answers weren't saved</AlertTitle>
								<AlertDescription>Please try again.</AlertDescription>
							</Alert>
						)}
					</>
				)}

				{/* Sign out sits at the far edge from Continue: the two are one mis-click apart
				    otherwise, and only one of them is recoverable. */}
				<footer className="flex flex-col gap-4 border-t pt-5 sm:flex-row-reverse sm:items-center sm:justify-between">
					<div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:gap-4">
						<p className="text-sm text-muted-foreground">
							{state.status === "ready" ? outstanding : null}
						</p>
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

				<nav aria-label="Legal" className="flex gap-x-3 text-xs text-muted-foreground">
					<LegalLink href="/privacy">Privacy notice</LegalLink>
					<span aria-hidden="true">·</span>
					<LegalLink href="/imprint">Imprint</LegalLink>
				</nav>
			</PageLayout>
		</div>
	);
}
