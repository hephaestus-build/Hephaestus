import { type ReactNode, useId, useRef, useState } from "react";

import type { FeedbackRequest } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
	Dialog,
	DialogBody,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogForm,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldError,
	FieldLabel,
	FieldSet,
} from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { hasText } from "@/lib/text";

import { SHARING_COPY } from "./sharing-copy";

/** The server's `FeedbackRequest` limits; a longer value is refused, so the sender never sees a cut. */
export const MESSAGE_MAX_LENGTH = 5000;
export const PAGE_PATH_MAX_LENGTH = 500;
/** The counter appears once this many characters are left, not before: a count nobody is near is noise. */
const COUNTER_THRESHOLD = 500;

/**
 * What the composer can offer about a public issue. `off`: the instance does not file to GitHub.
 * `private`: reports become private issues, because there is no public repository. `public`: the
 * person can tick the box; `remembered` is their earlier tick. No state blocks Send: a report whose
 * box was not shown stays private.
 */
export type ProductFeedbackSharingChoice =
	| { status: "loading" }
	| { status: "error" }
	| { status: "off" }
	| { status: "private" }
	| { status: "public"; remembered: boolean };

/** Where a report goes, said before Send, from what the instance does with it. */
function destinationCopy(sharing: ProductFeedbackSharingChoice): string {
	if (sharing.status === "off") {
		return "This report is saved for your instance admins, with your name and email address. They can later send it to GitHub as a private issue, without your name. An AI model then drafts that issue.";
	}
	if (sharing.status === "private") {
		return "An AI model drafts a private GitHub issue for the Hephaestus maintainers. Your name and email address stay on this instance.";
	}
	if (sharing.status === "public") {
		return "An AI model drafts a GitHub issue for the Hephaestus maintainers. It stays private unless you select the public option below.";
	}
	return "Your report stays private. Instance admins can read it. An AI model may draft a private GitHub issue for the Hephaestus maintainers.";
}

export interface ProductFeedbackDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	/** The page's route, such as `/w/$workspaceSlug/activity`, never its address: no workspace slug or id. */
	pagePath?: string;
	sharing: ProductFeedbackSharingChoice;
	isSubmitting: boolean;
	error?: string;
	/** Resolves to whether the submission was accepted; the draft is cleared only then. */
	onSubmit: (request: FeedbackRequest) => Promise<boolean>;
	reportsLink: ReactNode;
}

/** Kept mounted by the header so closing or navigating inside the app preserves the draft. */
export function ProductFeedbackDialog({
	open,
	onOpenChange,
	pagePath,
	sharing,
	isSubmitting,
	error,
	onSubmit,
	reportsLink,
}: ProductFeedbackDialogProps) {
	const id = useId();
	const messageField = useRef<HTMLTextAreaElement>(null);
	const [message, setMessage] = useState("");
	// A resumed draft still describes its original page, even after navigation to report history.
	const [draftPage, setDraftPage] = useState<string>();
	const [shareChoice, setShareChoice] = useState<boolean>();
	const [attempted, setAttempted] = useState(false);
	const attachedPage = draftPage ?? pagePath;
	const share = shareChoice ?? (sharing.status === "public" && sharing.remembered);
	const messageInvalid = attempted && !message.trim();
	const remaining = MESSAGE_MAX_LENGTH - message.length;
	const submit = async () => {
		if (isSubmitting) {
			return;
		}
		setAttempted(true);
		if (!message.trim()) {
			messageField.current?.focus();
			return;
		}
		const accepted = await onSubmit({
			kind: "FEEDBACK",
			message: message.trim(),
			pagePath: attachedPage,
			// Explicit for each report: a box the person did not see never publishes by a remembered choice.
			share: sharing.status === "public" && share,
		});
		if (accepted) {
			setMessage("");
			setDraftPage(undefined);
			setShareChoice(undefined);
			setAttempted(false);
		}
	};
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			<DialogContent className="sm:max-w-lg">
				<DialogForm
					aria-busy={isSubmitting}
					onSubmit={(event) => {
						event.preventDefault();
						void submit();
					}}
				>
					<DialogHeader>
						<DialogTitle>Send feedback</DialogTitle>
						<DialogDescription>{destinationCopy(sharing)}</DialogDescription>
					</DialogHeader>
					<DialogBody className="flex flex-col gap-6 py-1">
						<FieldSet disabled={isSubmitting} className="gap-6">
							<Field data-invalid={messageInvalid || undefined}>
								<FieldLabel htmlFor={`${id}-message`}>Your feedback</FieldLabel>
								<Textarea
									ref={messageField}
									id={`${id}-message`}
									name="message"
									autoComplete="off"
									required
									value={message}
									maxLength={MESSAGE_MAX_LENGTH}
									className="min-h-32"
									aria-invalid={messageInvalid || undefined}
									aria-describedby={`${id}-hint${messageInvalid ? ` ${id}-message-error` : ""}`}
									placeholder="What happened, or what would you change?"
									onKeyDown={(event) => {
										if (
											(event.metaKey || event.ctrlKey) &&
											!event.nativeEvent.isComposing &&
											event.key === "Enter"
										) {
											event.preventDefault();
											event.currentTarget.form?.requestSubmit();
										}
									}}
									onChange={(event) => {
										const next = event.target.value;
										setDraftPage((previous) =>
											hasText(next) ? (previous ?? pagePath) : undefined,
										);
										setMessage(next);
									}}
								/>
								<FieldDescription id={`${id}-hint`}>
									Leave out passwords and other people’s personal details.
									{attachedPage !== undefined &&
										" Hephaestus includes the page where you started this report and the app version."}
								</FieldDescription>
								<span
									aria-live="polite"
									className="text-xs text-muted-foreground tabular-nums empty:sr-only"
								>
									{remaining <= COUNTER_THRESHOLD &&
										`${remaining.toLocaleString()} characters left`}
								</span>
								{messageInvalid && (
									<FieldError id={`${id}-message-error`}>
										Write your feedback before sending.
									</FieldError>
								)}
							</Field>
							{sharing.status === "loading" && <SharingSkeleton />}
							{sharing.status === "public" && (
								<Field orientation="horizontal">
									<Checkbox
										id={`${id}-share`}
										checked={share}
										onCheckedChange={setShareChoice}
										aria-describedby={`${id}-share-detail`}
									/>
									<FieldContent>
										<FieldLabel htmlFor={`${id}-share`}>{SHARING_COPY.label}</FieldLabel>
										<FieldDescription id={`${id}-share-detail`}>
											{SHARING_COPY.detail}
										</FieldDescription>
									</FieldContent>
								</Field>
							)}
						</FieldSet>
						{hasText(error) && (
							<div className="flex flex-col items-start gap-2">
								<FieldError>{error}</FieldError>
								{reportsLink}
							</div>
						)}
					</DialogBody>
					<DialogFooter>
						<Button type="submit" disabled={isSubmitting}>
							{isSubmitting && <Spinner />}
							{isSubmitting ? "Sending…" : "Send"}
						</Button>
					</DialogFooter>
				</DialogForm>
			</DialogContent>
		</Dialog>
	);
}

function SharingSkeleton() {
	return (
		<>
			<span className="sr-only">Loading the sharing option…</span>
			<Skeleton className="h-16 w-full" />
		</>
	);
}
