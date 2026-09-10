import { GraduationCapIcon, LineChartIcon, Undo2Icon } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";

import type { ConsentStatus } from "@/api/types.gen";
import { LegalLinks } from "@/components/auth/LegalLinks";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardFooter, CardHeader } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";

export interface ConsentChoice {
	noticeVersion: string;
	termsAccepted: boolean;
	participateInResearch: boolean;
}

export type ConsentSubmission =
	| { status: "idle" }
	| { status: "saving"; participateInResearch: boolean }
	| { status: "error" };

export interface ConsentPageProps {
	onSignOut: () => void;
	state:
		| { status: "loading" }
		| { status: "error"; onRetry: () => void }
		| {
				status: "ready";
				notice: ConsentStatus;
				submission: ConsentSubmission;
				onSubmit: (choice: ConsentChoice) => void;
		  };
}

const RESEARCH_POINTS = [
	{
		icon: GraduationCapIcon,
		term: "Why it helps",
		detail:
			"What we learn from real teams is what makes the feedback better — for you and for everyone after you.",
	},
	{
		icon: LineChartIcon,
		term: "What you share",
		detail:
			"How you use Hephaestus and how you respond to its feedback. You may also be invited to occasional surveys.",
	},
	{
		icon: Undo2Icon,
		term: "You stay in control",
		detail:
			"Hephaestus works the same either way, and you can change your choice in settings at any time.",
	},
];

/**
 * First-login onboarding: the required acknowledgement, then the research invitation as a decision
 * of its own. Separating them is the point — one is a condition of use, the other is consent that
 * has to be refusable at no cost, so neither choice is preselected and both are one click away.
 */
export function ConsentPage({ state, onSignOut }: ConsentPageProps) {
	const savingChoice =
		state.status === "ready" && state.submission.status === "saving"
			? state.submission.participateInResearch
			: undefined;
	const submitting = savingChoice !== undefined;
	const [step, setStep] = useState<"notice" | "research">("notice");
	const isResearchStep = step === "research";
	const [termsAccepted, setTermsAccepted] = useState(false);
	const termsId = useId();
	const headingRef = useRef<HTMLHeadingElement>(null);

	useEffect(() => {
		headingRef.current?.focus();
	}, [step]);

	function submit(participateInResearch: boolean) {
		if (state.status !== "ready" || !termsAccepted || submitting) return;
		state.onSubmit({
			noticeVersion: state.notice.noticeVersion,
			termsAccepted: true,
			participateInResearch,
		});
	}

	return (
		<div className="flex min-h-svh flex-col items-center justify-center gap-6 bg-background p-6 md:p-10">
			<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />

			<Card className="w-full max-w-2xl">
				<CardHeader className="gap-2">
					<p className="text-muted-foreground">{isResearchStep ? "Step 2 of 2" : "Step 1 of 2"}</p>
					<h1
						ref={headingRef}
						tabIndex={-1}
						className="text-2xl font-semibold tracking-tight outline-none"
					>
						{isResearchStep ? "Take part in the research?" : "How Hephaestus uses your data"}
					</h1>
					<p className="max-w-prose text-muted-foreground">
						{isResearchStep
							? "This is the research described in the notice you just read. Taking part is optional and does not change what Hephaestus does for you."
							: "Please read this before you start — it includes the terms of use."}
					</p>
				</CardHeader>

				<CardContent className="space-y-6 leading-relaxed">
					{state.status === "error" ? (
						<Alert variant="destructive">
							<AlertTitle>We couldn't load the notice</AlertTitle>
							<AlertDescription>Check your connection and try again.</AlertDescription>
						</Alert>
					) : state.status === "loading" ? (
						<div className="space-y-4" aria-busy="true">
							<span className="sr-only">Loading the notice…</span>
							<Skeleton className="h-16 w-full" />
							<Skeleton className="h-24 w-full" />
							<Skeleton className="h-10 w-full" />
						</div>
					) : isResearchStep ? (
						<dl className="max-w-prose space-y-4">
							{/* A `dl` may only hold `dt`/`dd` pairs, wrapped at most one `div` deep. */}
							{RESEARCH_POINTS.map(({ icon: Icon, term, detail }) => (
								<div key={term}>
									<dt className="flex items-center gap-3 font-medium">
										<span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-mentor/10 text-mentor">
											<Icon className="size-4" aria-hidden="true" />
										</span>
										{term}
									</dt>
									<dd className="mt-1 ml-11 text-muted-foreground">{detail}</dd>
								</div>
							))}
						</dl>
					) : (
						<>
							{/* The notice is the document being accepted, so it reads at a document measure. */}
							<div className="max-w-prose space-y-4">
								{state.notice.noticeText.split("\n\n").map((paragraph) => (
									<p key={paragraph}>{paragraph}</p>
								))}
							</div>
							<div className="rounded-lg border p-4">
								<Field orientation="horizontal">
									<Checkbox
										id={termsId}
										checked={termsAccepted}
										disabled={submitting}
										onCheckedChange={setTermsAccepted}
									/>
									<FieldContent>
										<FieldLabel htmlFor={termsId}>
											I accept the terms of use and acknowledge this notice
										</FieldLabel>
										<FieldDescription>Accepting is not consent to research.</FieldDescription>
									</FieldContent>
								</Field>
							</div>
						</>
					)}

					{isResearchStep && state.status === "ready" && state.submission.status === "error" && (
						<Alert variant="destructive">
							<AlertTitle>Your choice wasn't saved</AlertTitle>
							<AlertDescription>Please try again.</AlertDescription>
						</Alert>
					)}
				</CardContent>

				<CardFooter className="flex-col-reverse items-stretch gap-2 sm:flex-row sm:items-center sm:justify-end">
					<Button variant="ghost" disabled={submitting} onClick={onSignOut} className="sm:mr-auto">
						Sign out
					</Button>
					{state.status === "error" ? (
						<Button onClick={state.onRetry}>Try again</Button>
					) : state.status === "ready" ? (
						isResearchStep ? (
							<>
								<Button variant="ghost" disabled={submitting} onClick={() => setStep("notice")}>
									Back
								</Button>
								<Button variant="outline" disabled={submitting} onClick={() => submit(false)}>
									{savingChoice === false && <Spinner />}
									{savingChoice === false ? "Saving…" : "Continue without research"}
								</Button>
								<Button variant="outline" disabled={submitting} onClick={() => submit(true)}>
									{savingChoice === true && <Spinner />}
									{savingChoice === true ? "Saving…" : "Yes, I'll take part"}
								</Button>
							</>
						) : (
							<Button disabled={!termsAccepted} onClick={() => setStep("research")}>
								Continue to the research question
							</Button>
						)
					) : null}
				</CardFooter>
			</Card>

			<LegalLinks />
		</div>
	);
}
