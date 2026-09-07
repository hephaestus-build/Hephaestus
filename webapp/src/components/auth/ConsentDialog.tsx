import { GraduationCapIcon } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";

import type { ConsentStatus } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
	Dialog,
	DialogBody,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
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

export interface ConsentDialogProps {
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

export function ConsentDialog({ state, onSignOut }: ConsentDialogProps) {
	const savingChoice =
		state.status === "ready" && state.submission.status === "saving"
			? state.submission.participateInResearch
			: undefined;
	const submitting = savingChoice !== undefined;
	const [step, setStep] = useState<"notice" | "research">("notice");
	const [termsAccepted, setTermsAccepted] = useState(false);
	const termsId = useId();
	const titleRef = useRef<HTMLHeadingElement>(null);
	const bodyRef = useRef<HTMLDivElement>(null);

	useEffect(() => {
		titleRef.current?.focus();
	}, [step]);

	function changeStep(next: typeof step) {
		setStep(next);
		if (bodyRef.current) bodyRef.current.scrollTop = 0;
	}

	function submit(research: boolean) {
		if (state.status !== "ready" || !termsAccepted || submitting) return;
		state.onSubmit({
			noticeVersion: state.notice.noticeVersion,
			termsAccepted: true,
			participateInResearch: research,
		});
	}

	return (
		<Dialog open>
			<DialogContent
				showCloseButton={false}
				className="sm:max-w-xl"
				aria-describedby={undefined}
				initialFocus={titleRef}
			>
				<DialogHeader>
					<DialogDescription>
						{step === "notice" ? "1 of 2 · Your data" : "2 of 2 · Optional research"}
					</DialogDescription>
					<DialogTitle ref={titleRef} tabIndex={-1} className="text-xl outline-none">
						{step === "notice"
							? "How Hephaestus uses your data"
							: "Help shape better feedback for developers"}
					</DialogTitle>
				</DialogHeader>
				<DialogBody ref={bodyRef} className="space-y-4 leading-relaxed">
					{state.status === "error" ? (
						<p role="alert">We couldn't load the notice. Try again before continuing.</p>
					) : state.status === "loading" ? (
						<div className="space-y-4" aria-label="Loading the privacy notice">
							<Skeleton className="h-16 w-full" />
							<Skeleton className="h-24 w-full" />
							<Skeleton className="h-10 w-full" />
						</div>
					) : step === "notice" ? (
						<>
							<div className="space-y-3 text-muted-foreground">
								{state.notice.noticeText.split("\n\n").map((paragraph) => (
									<p key={paragraph}>{paragraph}</p>
								))}
							</div>
							<p>
								<a
									href="/privacy"
									target="_blank"
									rel="noreferrer"
									className="underline underline-offset-4"
								>
									Full privacy notice (opens in a new tab)
								</a>
							</p>
							<Field orientation="horizontal">
								<Checkbox id={termsId} checked={termsAccepted} onCheckedChange={setTermsAccepted} />
								<FieldContent>
									<FieldLabel htmlFor={termsId}>
										I accept the terms of use and acknowledge the privacy notice
									</FieldLabel>
									<FieldDescription>This does not give consent to research.</FieldDescription>
								</FieldContent>
							</Field>
						</>
					) : (
						<>
							<div className="bg-primary/5 rounded-lg p-4 space-y-3">
								<GraduationCapIcon className="size-7 text-primary" aria-hidden />
								<p>
									Hephaestus is a research project at the Technical University of Munich (TUM). Your
									participation helps AET study how software-engineering feedback can support
									developers.
								</p>
							</div>
							<dl className="space-y-3">
								<div>
									<dt className="font-medium">What you contribute</dt>
									<dd className="text-muted-foreground">
										Your Hephaestus usage and feedback data for academic research. You may also be
										invited to occasional, optional surveys.
									</dd>
								</div>
								<div>
									<dt className="font-medium">Your choice, your pace</dt>
									<dd className="text-muted-foreground">
										Take the time you need. You get the same service either way. You can change your
										choice in Settings → Academic research participation.
									</dd>
								</div>
								<div>
									<dt className="font-medium">You can withdraw at any time</dt>
									<dd className="text-muted-foreground">
										Withdrawal stops new research processing. It does not affect the lawfulness of
										processing before withdrawal.
									</dd>
								</div>
							</dl>
							<p>
								<a
									href="/privacy"
									target="_blank"
									rel="noreferrer"
									className="underline underline-offset-4"
								>
									Privacy, retention and contact details (opens in a new tab)
								</a>
							</p>
						</>
					)}
					{state.status === "ready" && state.submission.status === "error" && (
						<p role="alert" className="text-destructive">
							Your choice wasn't saved. Try again.
						</p>
					)}
				</DialogBody>
				<DialogFooter className="flex-col items-stretch sm:flex-col sm:items-stretch">
					{state.status === "error" ? (
						<Button onClick={state.onRetry}>Try again</Button>
					) : state.status === "ready" ? (
						step === "notice" ? (
							<Button disabled={!termsAccepted} onClick={() => changeStep("research")}>
								Continue
							</Button>
						) : (
							<>
								<div className="grid gap-2 sm:grid-cols-2">
									<Button variant="outline" disabled={submitting} onClick={() => submit(true)}>
										{savingChoice === true && <Spinner />}
										{savingChoice === true ? "Saving…" : "Yes, I'll take part"}
									</Button>
									<Button variant="outline" disabled={submitting} onClick={() => submit(false)}>
										{savingChoice === false && <Spinner />}
										{savingChoice === false ? "Saving…" : "Continue without research"}
									</Button>
								</div>
								<Button variant="ghost" disabled={submitting} onClick={() => changeStep("notice")}>
									Back to your data
								</Button>
							</>
						)
					) : null}
					<Button variant="ghost" onClick={onSignOut} disabled={submitting}>
						Sign out instead
					</Button>
				</DialogFooter>
			</DialogContent>
		</Dialog>
	);
}
