import { FileTextIcon, FlaskConicalIcon } from "lucide-react";
import { type ReactNode, type SubmitEvent, useId, useState } from "react";

import type { ConsentStatus } from "@/api/types.gen";
import {
	ResearchDetails,
	ResearchSummary,
	TERMS_FACTS,
	TERMS_LABEL,
	TERMS_OBLIGATIONS,
	WORDING_VERSION,
	researchAnswers,
} from "@/components/auth/consent-wording";
import { FactList } from "@/components/auth/FactList";
import { LegalLinks } from "@/components/auth/LegalLinks";
import { StepMarker } from "@/components/auth/StepMarker";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import { HephSays } from "@/components/mentor/HephSays";
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

type Answer = "yes" | "no";

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
	const submissionFailed = state.status === "ready" && state.submission.status === "error";
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
	// and reaches the button through `aria-describedby`, so focusing the button does not replay it.
	function narrate() {
		if (state.status === "loading") {
			return "One moment while I fetch your setup.";
		}
		if (state.status === "error") {
			return "I could not fetch your setup.";
		}
		if (stale) {
			return "Hephaestus was updated while this page was open.";
		}
		if (termsAccepted && answered) {
			return "That is everything. You can get to work.";
		}
		if (!asksAboutResearch) {
			return "Read the terms, then accept them to continue.";
		}
		if (termsAccepted) {
			return "Thanks. One question left, and either answer is fine.";
		}
		if (answer === undefined) {
			return "Two things first: accept the terms, then answer the research question. Either answer is fine.";
		}
		return "Noted. Only the terms are left.";
	}

	function footerHint() {
		if (state.status !== "ready" || stale) {
			return;
		}
		if (!termsAccepted && !answered) {
			return "Accept the terms and answer the research question.";
		}
		if (!termsAccepted) {
			return "Accept the terms to continue.";
		}
		if (!answered) {
			return "Answer the research question to continue.";
		}
		return asksAboutResearch
			? "You can change your research answer later in User settings."
			: undefined;
	}

	const narration = narrate();
	const hint = footerHint();

	function submit(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();
		if (state.status !== "ready" || !ready || submitting) {
			return;
		}
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
						<h1 className="text-2xl font-semibold tracking-tight break-words">Get set up</h1>
						<HephSays
							intro="I am Heph, the mentor in Hephaestus. I read the work you already do, give you feedback on the practices your project cares about, and talk it through whenever you ask."
							narration={narration}
						/>
					</header>

					<Separator />

					<ConsentBody state={state} stale={stale} onReload={onReload}>
						<Section
							title={
								<span className="flex items-start gap-3">
									<StepMarker icon={FileTextIcon} done={termsAccepted} />
									<span className="min-w-0">Terms and privacy</span>
								</span>
							}
						>
							<FactList facts={TERMS_FACTS} />
							<Field orientation="horizontal">
								<Checkbox
									id={`${id}-terms`}
									checked={termsAccepted}
									disabled={submitting}
									onCheckedChange={setTermsAccepted}
								/>
								<FieldContent>
									<FieldLabel htmlFor={`${id}-terms`}>{TERMS_LABEL}</FieldLabel>
									<FieldDescription>{TERMS_OBLIGATIONS}</FieldDescription>
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
											<span className="min-w-0">Allow research use of your data?</span>
										</span>
									}
									description="This research needs real project work, so it asks developers to take part. It is optional, and Hephaestus works the same either way."
								>
									<ResearchSummary
										organization={researchOrganization}
										id={`${id}-research-summary`}
									/>
									<ResearchDetails organization={researchOrganization} />

									<RadioGroup
										value={answer ?? null}
										onValueChange={(value) => setAnswer(value ?? undefined)}
										disabled={submitting}
										aria-labelledby={`${id}-research-title`}
										aria-describedby={`${id}-research-description ${id}-research-summary`}
										className="grid gap-3 sm:grid-cols-2"
									>
										{researchAnswers(researchOrganization).map(({ value, title, detail }) => (
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

						{submissionFailed && (
							<Alert variant="destructive">
								<AlertTitle>Your answers were not saved</AlertTitle>
								<AlertDescription>Try again.</AlertDescription>
							</Alert>
						)}
					</ConsentBody>

					<Separator />

					{/* Sign out sits at the far edge from the submit button: only one of the two is recoverable. */}
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
									{submitting ? "Saving…" : "Save and continue"}
								</Button>
							)}
						</div>
						<Button
							type="button"
							variant="quiet"
							disabled={submitting}
							onClick={onSignOut}
							className="self-start sm:-ml-3"
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

function ConsentBody({
	state,
	stale,
	onReload,
	children,
}: {
	state: ConsentPageProps["state"];
	stale: boolean;
	onReload: () => void;
	children: ReactNode;
}): ReactNode {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="We could not load your setup"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status === "loading") {
		return (
			<div className="space-y-6" aria-busy="true">
				<span className="sr-only">Loading…</span>
				<Skeleton className="h-32 w-full" />
				<div className="grid gap-3 sm:grid-cols-2">
					<Skeleton className="h-20" />
					<Skeleton className="h-20" />
				</div>
			</div>
		);
	}
	if (stale) {
		// The words in the form come from this bundle and the version comes with them, so a bundle
		// the server has moved past must not be answered — it would record an acceptance of
		// terms nobody was shown. Only a document load replaces the bundle.
		return (
			<Alert>
				<AlertTitle>The terms changed while this page was open</AlertTitle>
				<AlertDescription className="flex flex-col items-start gap-3">
					Reload to read the current version before you accept it.
					<Button type="button" variant="outline" onClick={onReload}>
						Reload
					</Button>
				</AlertDescription>
			</Alert>
		);
	}
	return children;
}
