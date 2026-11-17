import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { MessageSquarePlus, Minus, XIcon } from "lucide-react";
import { type ReactNode, type RefObject, useEffect, useId, useRef, useState } from "react";

import type { FeedbackRequest } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { DialogForm } from "@/components/ui/dialog";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldError,
	FieldLabel,
	FieldSet,
} from "@/components/ui/field";
import { Kbd } from "@/components/ui/kbd";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import {
	FEEDBACK_SHORTCUT_LABEL,
	isComposing,
	SEND_SHORTCUT_ARIA,
	SEND_SHORTCUT_LABEL,
} from "@/lib/keyboard-shortcuts";
import { formatNumber } from "@/lib/numbers";
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

/**
 * `open`: the panel shows. `minimized`: only a bar in the corner shows. `closed`: nothing shows.
 * The draft survives all three; only an accepted send clears it.
 */
export type ProductFeedbackComposerView = "open" | "minimized" | "closed";

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
	view: ProductFeedbackComposerView;
	onViewChange: (view: ProductFeedbackComposerView) => void;
	/** The page's route, such as `/w/$workspaceSlug/activity`, never its address: no workspace slug or id. */
	pagePath?: string;
	sharing: ProductFeedbackSharingChoice;
	isSubmitting: boolean;
	error?: string;
	/** Resolves to whether the submission was accepted; the draft is cleared only then. */
	onSubmit: (request: FeedbackRequest) => Promise<boolean>;
	reportsLink: ReactNode;
	/** The control that opens the form; Close returns focus there. */
	returnFocus?: RefObject<HTMLElement | null>;
	/** Changes with each request to open the form, so a request while it is open moves focus into it. */
	focusRequest?: number;
}

/**
 * A non-modal panel docked in the bottom-right corner. The person reports on the page they are
 * looking at, so the page stays visible and usable: no backdrop, no blur, no focus trap, and a press
 * on the page does not dismiss it. A modal dialog was rejected because it hid the very screen the
 * report is about. Kept mounted by the header so closing or navigating inside the app preserves the
 * draft.
 */
export function ProductFeedbackDialog({
	view,
	onViewChange,
	pagePath,
	sharing,
	isSubmitting,
	error,
	onSubmit,
	reportsLink,
	returnFocus,
	focusRequest,
}: ProductFeedbackDialogProps) {
	const id = useId();
	const messageField = useRef<HTMLTextAreaElement>(null);
	const popup = useRef<HTMLDivElement>(null);
	const draftButton = useRef<HTMLButtonElement>(null);
	// Where focus goes when the panel leaves: the corner button after Minimize, the opening control after Close.
	const leavingTo = useRef<"minimized" | "closed">("closed");
	const [message, setMessage] = useState("");
	// A resumed draft still describes its original page, even after navigation to report history.
	const [draftPage, setDraftPage] = useState<string>();
	const [shareChoice, setShareChoice] = useState<boolean>();
	const [attempted, setAttempted] = useState(false);
	const attachedPage = draftPage ?? pagePath;
	const share = shareChoice ?? (sharing.status === "public" && sharing.remembered);
	const messageInvalid = attempted && !message.trim();
	const remaining = MESSAGE_MAX_LENGTH - message.length;
	// A panel that is not modal cannot hold focus, so each request to open it, also one while it is open,
	// brings focus back to the text.
	useEffect(() => {
		if (view !== "open") {
			return;
		}
		leavingTo.current = "closed";
		if (focusRequest !== undefined) {
			messageField.current?.focus();
		}
	}, [focusRequest, view]);
	const leave = (next: "minimized" | "closed") => {
		leavingTo.current = next;
		onViewChange(next);
	};
	// An empty form has no draft to keep in the corner, so setting it aside closes it.
	const setAside = () => leave(hasText(message) ? "minimized" : "closed");
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
		<>
			{view === "minimized" && (
				// Above the corner's Get started pill, not over it: a tucked-away draft must not hide another
				// control. The open panel may cover the pill, because the reader is writing in it.
				<div className="fixed right-4 bottom-20 z-50">
					<Button
						ref={draftButton}
						variant="outline"
						shape="pill"
						size="lg"
						className="shadow-lg"
						onClick={() => onViewChange("open")}
					>
						<MessageSquarePlus data-icon="inline-start" aria-hidden />
						Feedback draft
					</Button>
				</div>
			)}
			<DialogPrimitive.Root
				open={view === "open"}
				modal={false}
				disablePointerDismissal
				onOpenChange={(next, details) => {
					if (next) {
						onViewChange("open");
						return;
					}
					if (details.reason === "escape-key") {
						// Base UI hears Escape on the whole page. One the page itself handles, such as clearing a
						// search field, is not meant for a panel that the person works beside.
						const { target } = details.event;
						if (!(target instanceof Node) || popup.current?.contains(target) !== true) {
							details.cancel();
							return;
						}
						setAside();
						return;
					}
					leave("closed");
				}}
			>
				<DialogPrimitive.Portal>
					<DialogPrimitive.Popup
						ref={popup}
						data-slot="product-feedback-composer"
						initialFocus={messageField}
						finalFocus={() =>
							(leavingTo.current === "minimized" ? draftButton.current : returnFocus?.current) ??
							true
						}
						className="fixed right-4 bottom-4 z-50 flex max-h-[calc(100svh-2rem)] w-[min(28rem,calc(100vw-2rem))] flex-col overflow-hidden rounded-xl bg-background text-sm shadow-xl ring-1 ring-foreground/10 transition-[opacity,transform] duration-280 ease-enter focus-visible:outline-hidden data-ending-style:translate-y-2 data-ending-style:opacity-0 data-ending-style:duration-200 data-ending-style:ease-exit data-starting-style:translate-y-2 data-starting-style:opacity-0 motion-reduce:data-ending-style:translate-y-0 motion-reduce:data-starting-style:translate-y-0"
					>
						<DialogForm
							aria-busy={isSubmitting}
							onSubmit={(event) => {
								event.preventDefault();
								void submit();
							}}
							// On the form, so that it also sends from the box and not only from the text.
							onKeyDown={(event) => {
								if (
									(event.metaKey || event.ctrlKey) &&
									!isComposing(event.nativeEvent) &&
									event.key === "Enter"
								) {
									event.preventDefault();
									event.currentTarget.requestSubmit();
								}
							}}
						>
							<div className="flex shrink-0 items-center gap-2 border-b py-2 pr-2 pl-4">
								<DialogPrimitive.Title className="flex min-w-0 flex-1 items-center gap-2 text-base leading-none font-medium">
									Send feedback
									<Kbd aria-hidden className="pointer-coarse:hidden">
										{FEEDBACK_SHORTCUT_LABEL}
									</Kbd>
								</DialogPrimitive.Title>
								<Tooltip>
									<TooltipTrigger
										render={
											<Button
												type="button"
												variant="ghost"
												size="icon-sm"
												aria-label="Minimize"
												onClick={setAside}
											/>
										}
									>
										<Minus />
									</TooltipTrigger>
									<TooltipContent>Minimize</TooltipContent>
								</Tooltip>
								<Tooltip>
									<TooltipTrigger
										render={
											<DialogPrimitive.Close
												render={
													<Button type="button" variant="ghost" size="icon-sm" aria-label="Close" />
												}
											/>
										}
									>
										<XIcon />
									</TooltipTrigger>
									<TooltipContent>Close</TooltipContent>
								</Tooltip>
							</div>
							<div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto overscroll-contain p-4">
								<DialogPrimitive.Description className="text-muted-foreground">
									{destinationCopy(sharing)}
								</DialogPrimitive.Description>
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
												`${formatNumber(remaining)} characters left`}
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
							</div>
							<div className="flex shrink-0 justify-end border-t bg-muted/50 px-4 py-3">
								<Button pending={isSubmitting} type="submit" aria-keyshortcuts={SEND_SHORTCUT_ARIA}>
									{isSubmitting ? "Sending…" : "Send"}
									{!isSubmitting && (
										<span aria-hidden className="text-xs opacity-70 pointer-coarse:hidden">
											{SEND_SHORTCUT_LABEL}
										</span>
									)}
								</Button>
							</div>
						</DialogForm>
					</DialogPrimitive.Popup>
				</DialogPrimitive.Portal>
			</DialogPrimitive.Root>
		</>
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
