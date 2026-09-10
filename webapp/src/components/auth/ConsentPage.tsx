import {
	ActivityIcon,
	CheckIcon,
	ClockIcon,
	FileTextIcon,
	FlaskConicalIcon,
	type LucideIcon,
	ScaleIcon,
	ShieldCheckIcon,
	TrendingUpIcon,
	TriangleAlertIcon,
} from "lucide-react";
import { type ReactNode, type SubmitEvent, useId, useState } from "react";

import type { ConsentStatus } from "@/api/types.gen";
import { LegalLink, LegalLinks } from "@/components/auth/LegalLinks";
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

/**
 * The version of the wording below. This bundle is the archive: what an account accepted is whichever
 * release published these words, and `ConsentService.WORDING_VERSION` holds the same string.
 *
 * The page refuses to render the form when the server reports a different one, because the words on
 * screen would then be a version nobody could truthfully accept. Change any string in `TERMS` or
 * `RESEARCH` and this moves, in the same commit as the server's.
 */
export const WORDING_VERSION = "2026-09-10";

export interface ConsentChoice {
	noticeVersion: string;
	termsAccepted: boolean;
	participateInResearch?: boolean;
	researchOrganization?: string;
}

export type ConsentSubmission = { status: "idle" } | { status: "saving" } | { status: "error" };

export interface ConsentPageProps {
	onSignOut: () => void;
	/** A stale bundle is not something the router can fix; only a document load replaces it. */
	onReload: () => void;
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

interface Fact {
	icon: LucideIcon;
	term: string;
	detail: ReactNode;
}

/**
 * The notice, in full. It names no operator: who runs this instance is the privacy notice and the
 * imprint, which every operator configures and this page links to, so the same words are true on
 * every deployment.
 */
const TERMS: readonly Fact[] = [
	{
		icon: ScaleIcon,
		term: "Use it lawfully",
		detail: "Only for the workspaces and the data you are allowed to access.",
	},
	{
		icon: TriangleAlertIcon,
		term: "Feedback can be wrong",
		detail:
			"It is advisory, and never the only basis for a grading, employment or access decision.",
	},
	{
		icon: ShieldCheckIcon,
		term: "Your data",
		detail: (
			<>
				Who runs this instance, what it stores and for how long is in the{" "}
				<LegalLink to="/privacy">privacy notice</LegalLink>.
			</>
		),
	},
];

/**
 * Everything that argues for taking part lives out here rather than inside the "yes" answer. An
 * answer carrying more reasons than its opposite is the asymmetry EDPB 03/2022 calls deceptive.
 */
const RESEARCH: readonly Fact[] = [
	{
		icon: TrendingUpIcon,
		term: "Why it matters",
		detail: "What we learn from real projects is what makes the feedback better.",
	},
	{
		icon: ActivityIcon,
		term: "What you share",
		detail: "How you use Hephaestus, and how you respond to its feedback.",
	},
	{
		icon: ClockIcon,
		term: "What it asks of you",
		detail: "Nothing extra to do. Occasionally, an optional survey.",
	},
];

/** No icons here. A glyph on one answer and not the other is the thumb on the scale. */
const ANSWERS = [
	{
		value: "yes",
		title: "Yes, take part",
		detail: "Use my usage and feedback for the research.",
	},
	{
		value: "no",
		title: "No, don't take part",
		detail: "Keep my usage and feedback out of the research.",
	},
] as const;

type Answer = (typeof ANSWERS)[number]["value"];

function FactList({ facts }: { facts: readonly Fact[] }) {
	return (
		<dl className="grid gap-4 sm:grid-cols-3">
			{facts.map(({ icon: Icon, term, detail }) => (
				<div key={term} className="space-y-1">
					<dt className="flex items-center gap-2 text-sm font-medium">
						<Icon className="size-4 shrink-0 text-mentor" aria-hidden="true" />
						{term}
					</dt>
					<dd className="text-sm leading-relaxed text-muted-foreground">{detail}</dd>
				</div>
			))}
		</dl>
	);
}

/**
 * The step's own icon until it is answered, then a check. Both are decoration: the heading names the
 * step and the control inside it announces its own state.
 */
function StepMarker({ icon: Icon, done }: { icon: LucideIcon; done: boolean }) {
	return (
		<span
			aria-hidden="true"
			className={cn(
				"inline-flex size-7 shrink-0 items-center justify-center rounded-md transition-colors [&_svg]:size-4",
				done ? "bg-mentor text-mentor-foreground" : "bg-mentor/10 text-mentor",
			)}
		>
			{done ? <CheckIcon /> : <Icon />}
		</span>
	);
}

/**
 * Heph carries the page, so `--mentor` is its accent throughout: the bubble it speaks from, and the
 * marker on each step. `PageHeader` and its lucide icon belong to the app chrome, which is not up yet.
 *
 * The steps are not numbered. A member is handed workspace setup after this and a changed notice
 * brings an existing account back here, so "step 1 of n" would be a claim about a flow this screen
 * cannot see; the markers say what is answered, which is a claim it can make.
 */
export function ConsentPage({ state, onSignOut, onReload }: ConsentPageProps) {
	const submitting = state.status === "ready" && state.submission.status === "saving";
	const [termsAccepted, setTermsAccepted] = useState(false);
	const [answer, setAnswer] = useState<Answer>();
	const id = useId();

	const researchOrganization =
		state.status === "ready" ? state.notice.researchOrganization : undefined;
	const asksAboutResearch = researchOrganization !== undefined;
	const stale = state.status === "ready" && state.notice.noticeVersion !== WORDING_VERSION;
	const answered = !asksAboutResearch || answer !== undefined;
	const ready = state.status === "ready" && !stale && termsAccepted && answered;

	// Heph narrates, and only Heph is a live region. The footer hint says the same thing factually
	// and reaches the button through `aria-describedby`, so focusing Continue does not replay it.
	const narration =
		state.status === "loading"
			? "Give me a moment — I'm fetching your setup."
			: state.status === "error"
				? "I couldn't fetch your setup just now."
				: stale
					? "Hephaestus was updated while this page was open."
					: termsAccepted && answered
						? "That's everything. Let's get to work."
						: !asksAboutResearch
							? "One thing first: the rules."
							: termsAccepted
								? "Thanks. One question to go, and either answer is fine by me."
								: answer !== undefined
									? "Noted. Just the terms left."
									: "Two things first: the rules, and whether you'd like to take part in the research.";

	const hint =
		state.status !== "ready" || stale
			? undefined
			: !termsAccepted && !answered
				? "Accept the terms and answer the research question."
				: !termsAccepted
					? "Accept the terms to continue."
					: !answered
						? "Answer the research question to continue."
						: asksAboutResearch
							? "You can change your answer later in settings."
							: undefined;

	function submit(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();
		if (state.status !== "ready" || !ready || submitting) return;
		state.onSubmit({
			noticeVersion: state.notice.noticeVersion,
			termsAccepted: true,
			// The organisation is rendered from this same response, so echoing it is what binds the
			// answer to the question that was on screen rather than to whatever is configured now.
			...(researchOrganization !== undefined && {
				participateInResearch: answer === "yes",
				researchOrganization,
			}),
		});
	}

	return (
		<div className="min-h-svh bg-background">
			{/* Narrower than `PageLayout`'s default: this surface has no sidebar taking the other half. */}
			<form onSubmit={submit}>
				<PageLayout className="max-w-2xl px-6 py-10">
					<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />

					<header className="space-y-4">
						<h1 className="break-words text-2xl font-semibold tracking-tight">
							Let's get you set up
						</h1>
						<div className="flex items-start gap-3">
							<HephIcon className="shrink-0" size={64} pad={2} />
							{/* The tail is opaque so it covers the bubble's own border; a tinted fill would let
						    that edge show straight through it. */}
							<div className="relative min-w-0 flex-1 rounded-xl border border-mentor/30 bg-card p-3 before:absolute before:top-6 before:-left-1.5 before:size-3 before:rotate-45 before:border-b before:border-l before:border-mentor/30 before:bg-card before:content-[''] sm:p-4">
								<p className="sr-only">Heph says:</p>
								<p className="text-sm leading-relaxed">
									I'm Heph, the mentor in Hephaestus. I read the work you already do, give you
									feedback on the practices your project cares about, and talk it through whenever
									you ask.
								</p>
								<p aria-live="polite" className="mt-2 text-sm font-medium">
									{narration}
								</p>
							</div>
						</div>
					</header>

					<Separator />

					{state.status === "error" ? (
						<QueryErrorAlert
							error={state.error}
							title="Couldn't load your setup"
							onRetry={state.onRetry}
						/>
					) : state.status === "loading" ? (
						<div className="space-y-6" aria-busy="true">
							<span className="sr-only">Loading…</span>
							<Skeleton className="h-32 w-full" />
							<div className="grid gap-3 sm:grid-cols-2">
								<Skeleton className="h-20" />
								<Skeleton className="h-20" />
							</div>
						</div>
					) : stale ? (
						/* The words below come from this bundle and the version comes with them, so a bundle
					   the server has moved past must not be answered — it would record an acceptance of
					   terms nobody was shown. Only a document load replaces the bundle. */
						<Alert>
							<AlertTitle>The terms changed while this page was open</AlertTitle>
							<AlertDescription className="flex flex-col items-start gap-3">
								Reload to read the current version before you accept it.
								<Button type="button" variant="outline" onClick={onReload}>
									Reload
								</Button>
							</AlertDescription>
						</Alert>
					) : (
						<>
							<Section
								title={
									<span className="flex items-start gap-3">
										<StepMarker icon={FileTextIcon} done={termsAccepted} />
										<span className="min-w-0">Terms and privacy</span>
									</span>
								}
							>
								<FactList facts={TERMS} />
								<Field orientation="horizontal">
									<Checkbox
										id={`${id}-terms`}
										checked={termsAccepted}
										disabled={submitting}
										onCheckedChange={setTermsAccepted}
									/>
									<FieldContent>
										<FieldLabel htmlFor={`${id}-terms`}>I accept the terms of use</FieldLabel>
									</FieldContent>
								</Field>
							</Section>

							{asksAboutResearch && (
								<>
									<Separator />

									<Section
										id={`${id}-research`}
										title={
											<span className="flex items-start gap-3">
												<StepMarker icon={FlaskConicalIcon} done={answer !== undefined} />
												<span className="min-w-0">Take part in the research?</span>
											</span>
										}
										description={`Optional, and Hephaestus works the same either way. The research is run by ${researchOrganization}.`}
									>
										<FactList facts={RESEARCH} />

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
															<FieldDescription id={`${id}-${value}-detail`}>
																{detail}
															</FieldDescription>
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
								</>
							)}

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
							{state.status === "ready" && !stale && (
								<Button
									type="submit"
									disabled={!ready || submitting}
									aria-describedby={hint ? `${id}-hint` : undefined}
								>
									{submitting && <Spinner />}
									{submitting ? "Saving…" : "Continue"}
								</Button>
							)}
						</div>
						<Button
							type="button"
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
			</form>
		</div>
	);
}
