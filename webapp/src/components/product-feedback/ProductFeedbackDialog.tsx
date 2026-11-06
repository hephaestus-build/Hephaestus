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

import { ProductFeedbackSharingField } from "./ProductFeedbackSharingField";

/** The server's `FeedbackRequest` limits; a longer value is refused, so the sender never sees a cut. */
export const MESSAGE_MAX_LENGTH = 5000;
export const PAGE_PATH_MAX_LENGTH = 500;
export const USER_AGENT_MAX_LENGTH = 500;
/** The counter appears once this many characters are left, not before: a count nobody is near is noise. */
const COUNTER_THRESHOLD = 500;

export interface FeedbackContext {
	/** The page's route, such as `/w/$workspaceSlug/activity`, never its address: no workspace slug or id. */
	pagePath: string;
	userAgent: string;
}

/**
 * Whether this report may become a public GitHub issue, as far as the composer has to ask.
 * `off`: the instance does not file to GitHub; then nothing is asked. `ask`: the person has not chosen for the current notice.
 */
export type ProductFeedbackSharingChoice =
	| { status: "loading" }
	| { status: "error" }
	| { status: "off" }
	/** Reports become private GitHub issues; there is no public repository, so nothing is asked. */
	| { status: "private" }
	| { status: "ask" }
	| { status: "remembered"; allowPublic: boolean };

/** Where a report goes, said before Send, from what the instance does with it. */
function destinationCopy(sharing: ProductFeedbackSharingChoice): string {
	if (sharing.status === "off") {
		return "Your instance admins receive this report with your name and contact address.";
	}
	if (sharing.status === "private") {
		return "An AI model drafts a private GitHub issue for the Hephaestus maintainers. Your name and contact address stay on this instance.";
	}
	if (sharing.status === "loading" || sharing.status === "error") {
		return "Share a problem or an idea about Hephaestus.";
	}
	return "Share a problem or an idea with the Hephaestus maintainers.";
}

export interface ProductFeedbackDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	context?: FeedbackContext;
	sharing: ProductFeedbackSharingChoice;
	isSubmitting: boolean;
	error?: string;
	/** Resolves to whether the submission was accepted; the draft is cleared only then. */
	onSubmit: (request: FeedbackRequest) => Promise<boolean>;
	onRetrySharing: () => void;
	reportsLink: ReactNode;
}

/** Kept mounted by the header so closing or navigating inside the app preserves the draft. */
export function ProductFeedbackDialog({
	open,
	onOpenChange,
	context,
	sharing,
	isSubmitting,
	error,
	onSubmit,
	onRetrySharing,
	reportsLink,
}: ProductFeedbackDialogProps) {
	const id = useId();
	const messageField = useRef<HTMLTextAreaElement>(null);
	const sharingRegion = useRef<HTMLDivElement>(null);
	const [message, setMessage] = useState("");
	const [includeContext, setIncludeContext] = useState(false);
	// A resumed draft still describes its original page, even after navigation to report history.
	const [draftContext, setDraftContext] = useState<FeedbackContext>();
	const [shareChoice, setShareChoice] = useState<boolean>();
	const [attempted, setAttempted] = useState(false);
	const attachedContext = draftContext ?? context;
	const share = shareChoice ?? (sharing.status === "remembered" ? sharing.allowPublic : undefined);
	const needsChoice = sharing.status === "ask" && share === undefined;
	const messageInvalid = attempted && !message.trim();
	const sharingUnavailable = sharing.status === "loading" || sharing.status === "error";
	const remaining = MESSAGE_MAX_LENGTH - message.length;
	const submit = async () => {
		if (isSubmitting || sharingUnavailable) {
			return;
		}
		setAttempted(true);
		if (!message.trim()) {
			messageField.current?.focus();
			return;
		}
		if (needsChoice) {
			sharingRegion.current?.querySelector<HTMLElement>('[role="radio"]')?.focus();
			return;
		}
		const accepted = await onSubmit({
			kind: "FEEDBACK",
			message: message.trim(),
			pagePath: includeContext ? attachedContext?.pagePath : undefined,
			userAgent: includeContext ? attachedContext?.userAgent : undefined,
			...(sharing.status === "off" || sharing.status === "private" ? {} : { share }),
		});
		if (accepted) {
			setMessage("");
			setIncludeContext(false);
			setDraftContext(undefined);
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
									placeholder="Tell us what happened, or share an idea…"
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
										setDraftContext((previous) =>
											hasText(next) || includeContext ? (previous ?? context) : undefined,
										);
										setMessage(next);
									}}
								/>
								<FieldDescription id={`${id}-hint`}>
									Leave out passwords, tokens, and other people’s personal details.
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
							<div ref={sharingRegion} className="empty:hidden">
								<SharingField
									id={id}
									sharing={sharing}
									share={share}
									onShareChange={setShareChoice}
									invalid={attempted && needsChoice}
									onRetry={onRetrySharing}
								/>
							</div>
							{attachedContext && (
								<details className="text-sm">
									<summary className="cursor-pointer rounded-sm text-muted-foreground hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-hidden">
										{includeContext ? "Page details included" : "Add page details (optional)"}
									</summary>
									<div className="mt-3 flex flex-col gap-3">
										<Field orientation="horizontal">
											<Checkbox
												id={`${id}-context`}
												checked={includeContext}
												onCheckedChange={(next) => {
													setDraftContext((previous) =>
														next || hasText(message) ? (previous ?? context) : undefined,
													);
													setIncludeContext(next);
												}}
											/>
											<FieldContent>
												<FieldLabel htmlFor={`${id}-context`}>
													Include page and browser details
												</FieldLabel>
												<FieldDescription>
													Only the page route and browser details shown below are attached.
												</FieldDescription>
											</FieldContent>
										</Field>
										<dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-2 text-xs text-muted-foreground">
											<dt>Page</dt>
											<dd className="break-all">
												<code>{attachedContext.pagePath}</code>
											</dd>
											<dt>Browser</dt>
											<dd className="break-words">{attachedContext.userAgent}</dd>
										</dl>
									</div>
								</details>
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
						<Button type="submit" disabled={isSubmitting || sharingUnavailable}>
							{isSubmitting && <Spinner />}
							{isSubmitting ? "Sending…" : "Send feedback"}
						</Button>
					</DialogFooter>
				</DialogForm>
			</DialogContent>
		</Dialog>
	);
}

interface SharingFieldProps {
	id: string;
	sharing: ProductFeedbackSharingChoice;
	share: boolean | undefined;
	onShareChange: (share: boolean) => void;
	invalid: boolean;
	onRetry: () => void;
}

function SharingSkeleton() {
	return (
		<>
			<span className="sr-only">Loading your sharing choice…</span>
			<Skeleton className="h-10 w-full" />
		</>
	);
}

function SharingField({ id, sharing, share, onShareChange, invalid, onRetry }: SharingFieldProps) {
	if (sharing.status === "off" || sharing.status === "private") {
		return null;
	}
	if (sharing.status === "loading") {
		return <SharingSkeleton />;
	}
	if (sharing.status === "error") {
		return (
			<div className="text-sm" role="alert">
				<p>
					We could not load the sharing options. Your draft is still here. Try again before sending.
				</p>
				<Button type="button" variant="outline" size="sm" onClick={onRetry}>
					Retry
				</Button>
			</div>
		);
	}
	return (
		<ProductFeedbackSharingField
			id={id}
			status={sharing.status}
			share={share}
			onShareChange={onShareChange}
			invalid={invalid}
		/>
	);
}
