import { Link } from "@tanstack/react-router";
import { type ReactNode, useId, useState } from "react";

import type { FeedbackRequest } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
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
	FieldLabel,
	FieldLegend,
	FieldSet,
	FieldTitle,
} from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";

import { FEEDBACK_KIND_COPY, type FeedbackKind, READERS } from "./feedback-copy";
import { SHARING_COPY } from "./sharing-copy";

/** The server's `FeedbackRequest` limits; a longer value is refused, so the sender never sees a cut. */
export const MESSAGE_MAX_LENGTH = 5000;
export const PAGE_PATH_MAX_LENGTH = 500;
export const USER_AGENT_MAX_LENGTH = 500;
/** The counter appears once this many characters are left, not before: a count nobody is near is noise. */
const COUNTER_THRESHOLD = 500;

const KINDS: FeedbackKind[] = ["IDEA", "BUG", "FEEDBACK"];

export interface FeedbackContext {
	/** The page's route, such as `/w/$workspaceSlug/activity`, never its address: no workspace slug or id. */
	pagePath: string;
	userAgent: string;
}

/**
 * Whether this report may become a public GitHub issue, as far as the composer has to ask.
 * `off`: the instance has no public repository, or the choice could not be read; then nothing is
 * asked and the server decides. `ask`: the person has not chosen for the current notice.
 */
export type ProductFeedbackSharingChoice =
	| { status: "loading" }
	| { status: "off" }
	/** Reports become private GitHub issues; there is no public repository, so nothing is asked. */
	| { status: "private" }
	| { status: "ask" }
	| { status: "remembered"; allowPublic: boolean };

/** Where a report goes, said before Send, from what the instance does with it. */
function destinationCopy(sharing: ProductFeedbackSharingChoice): string {
	if (sharing.status === "off" || sharing.status === "loading") {
		return `This goes straight to ${READERS}. Your name and contact address go with it, so they can follow up.`;
	}
	if (sharing.status === "private") {
		return "Hephaestus drafts a private GitHub issue from your report for the maintainers. Your name and contact address stay on this instance.";
	}
	return "Hephaestus drafts a GitHub issue from your report. The choice below decides who can read it.";
}

export interface ProductFeedbackDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	kind: FeedbackKind;
	onKindChange: (kind: FeedbackKind) => void;
	context?: FeedbackContext;
	sharing: ProductFeedbackSharingChoice;
	isSubmitting: boolean;
	error?: string;
	/** Resolves to whether the submission was accepted; the draft is cleared only then. */
	onSubmit: (request: FeedbackRequest) => Promise<boolean>;
	/** The confirmation of the accepted report, shown in place of the form until the dialog closes. */
	confirmation?: ReactNode;
	/** The workspace the header keeps, so the settings link stays in it. */
	workspaceSlug?: string;
}

/**
 * The message draft lives in this component and survives closing because the header keeps it
 * mounted; only an accepted send clears it. The details box starts ticked for a bug report, where
 * the page and browser are what make it reproducible, and unticked otherwise.
 *
 * The sharing choice is asked once: with no choice yet, two options start unselected and Send waits
 * for one. After that, one switch starts from the remembered choice and applies to this report only.
 */
export function ProductFeedbackDialog({
	open,
	onOpenChange,
	kind,
	onKindChange,
	context,
	sharing,
	isSubmitting,
	error,
	onSubmit,
	confirmation,
	workspaceSlug,
}: ProductFeedbackDialogProps) {
	const id = useId();
	const [message, setMessage] = useState("");
	const [includeContext, setIncludeContext] = useState<boolean>();
	const [shareChoice, setShareChoice] = useState<boolean>();
	const attachContext = includeContext ?? kind === "BUG";
	const share = shareChoice ?? (sharing.status === "remembered" ? sharing.allowPublic : undefined);
	const sharingReady =
		sharing.status === "off" ||
		sharing.status === "private" ||
		(sharing.status !== "loading" && share !== undefined);
	const copy = FEEDBACK_KIND_COPY[kind];
	const remaining = MESSAGE_MAX_LENGTH - message.length;
	const submit = async () => {
		if (!message.trim() || isSubmitting || !sharingReady) {
			return;
		}
		const accepted = await onSubmit({
			kind,
			message: message.trim(),
			pagePath: attachContext ? context?.pagePath : undefined,
			userAgent: attachContext ? context?.userAgent : undefined,
			...(sharing.status === "off" || sharing.status === "private" ? {} : { share }),
		});
		if (accepted) {
			setMessage("");
			setIncludeContext(undefined);
			setShareChoice(undefined);
		}
	};
	if (confirmation !== undefined) {
		return (
			<Dialog open={open} onOpenChange={onOpenChange}>
				<DialogContent className="sm:max-w-lg">
					<DialogHeader>
						<DialogTitle>{copy.heading}</DialogTitle>
						<DialogDescription className="sr-only">What happens to your report</DialogDescription>
					</DialogHeader>
					<DialogBody className="py-1">{confirmation}</DialogBody>
					<DialogFooter>
						<Button type="button" onClick={() => onOpenChange(false)}>
							Done
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>
		);
	}
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
						<DialogTitle>{copy.heading}</DialogTitle>
						<DialogDescription>{destinationCopy(sharing)}</DialogDescription>
					</DialogHeader>
					<DialogBody className="flex flex-col gap-5 py-1">
						<FieldSet disabled={isSubmitting} className="gap-5">
							<FieldLegend className="sr-only">Kind and message</FieldLegend>
							<RadioGroup
								aria-label="What is this about?"
								value={kind}
								onValueChange={onKindChange}
								className="grid gap-2 sm:grid-cols-3"
							>
								{KINDS.map((value) => (
									<FieldLabel key={value} htmlFor={`${id}-${value}`}>
										<Field orientation="horizontal">
											<FieldContent>
												<FieldTitle id={`${id}-${value}-title`}>
													{FEEDBACK_KIND_COPY[value].title}
												</FieldTitle>
												<FieldDescription id={`${id}-${value}-detail`}>
													{FEEDBACK_KIND_COPY[value].detail}
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
							<Field>
								<FieldLabel htmlFor={`${id}-message`}>{copy.label}</FieldLabel>
								<Textarea
									id={`${id}-message`}
									name="message"
									required
									value={message}
									maxLength={MESSAGE_MAX_LENGTH}
									rows={5}
									aria-describedby={`${id}-hint`}
									placeholder={copy.placeholder}
									onChange={(event) => setMessage(event.target.value)}
								/>
								<FieldDescription id={`${id}-hint`} className="flex justify-between gap-3">
									<span>Leave out secrets and other people’s personal data.</span>
									<span aria-live="polite" className="shrink-0 tabular-nums">
										{remaining <= COUNTER_THRESHOLD &&
											`${remaining.toLocaleString()} characters left`}
									</span>
								</FieldDescription>
							</Field>
							{context && (
								<Field orientation="horizontal">
									<Checkbox
										id={`${id}-context`}
										checked={attachContext}
										onCheckedChange={setIncludeContext}
									/>
									<FieldContent>
										<FieldLabel htmlFor={`${id}-context`}>
											Attach the page and browser you’re on
										</FieldLabel>
										<FieldDescription className="break-all">
											<code className="text-xs">{context.pagePath}</code>
											<br />
											<span className="text-xs">{context.userAgent}</span>
										</FieldDescription>
									</FieldContent>
								</Field>
							)}
							<SharingField
								id={id}
								sharing={sharing}
								share={share}
								onShareChange={setShareChoice}
								workspaceSlug={workspaceSlug}
							/>
						</FieldSet>
						<p role="alert" className="text-sm text-destructive empty:hidden">
							{error}
						</p>
					</DialogBody>
					<DialogFooter>
						<Button type="submit" disabled={!message.trim() || isSubmitting || !sharingReady}>
							{isSubmitting && <Spinner />}
							{isSubmitting ? "Sending…" : "Send"}
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
	workspaceSlug?: string;
}

function SharingField({ id, sharing, share, onShareChange, workspaceSlug }: SharingFieldProps) {
	if (sharing.status === "off" || sharing.status === "private") {
		return null;
	}
	if (sharing.status === "loading") {
		return (
			<>
				<span className="sr-only">Loading your sharing choice…</span>
				<Skeleton className="h-10 w-full" />
			</>
		);
	}
	if (sharing.status === "remembered") {
		return (
			<Field orientation="horizontal">
				<Switch
					id={`${id}-share`}
					checked={share === true}
					onCheckedChange={onShareChange}
					aria-describedby={`${id}-share-detail`}
				/>
				<FieldContent>
					<FieldLabel htmlFor={`${id}-share`}>{SHARING_COPY.switchLabel}</FieldLabel>
					<FieldDescription id={`${id}-share-detail`}>
						{SHARING_COPY.switchDetail} ·{" "}
						<InlineLink
							className="underline"
							render={
								<Link
									to="/settings"
									hash="product-feedback"
									search={{ chromeWorkspace: workspaceSlug }}
								/>
							}
						>
							{SHARING_COPY.changeDefault}
						</InlineLink>
					</FieldDescription>
				</FieldContent>
			</Field>
		);
	}
	const options = [
		{ value: "share", copy: SHARING_COPY.share },
		{ value: "private", copy: SHARING_COPY.keepPrivate },
	] as const;
	let picked: (typeof options)[number]["value"] | null = null;
	if (share !== undefined) {
		picked = share ? "share" : "private";
	}
	return (
		<FieldSet className="gap-2">
			<FieldLegend variant="label">{SHARING_COPY.question}</FieldLegend>
			<RadioGroup
				aria-label={SHARING_COPY.question}
				aria-describedby={`${id}-share-footnote`}
				value={picked}
				onValueChange={(value) => onShareChange(value === "share")}
				className="gap-2"
			>
				{options.map(({ value, copy }) => (
					<FieldLabel key={value} htmlFor={`${id}-share-${value}`}>
						<Field orientation="horizontal">
							<RadioGroupItem
								id={`${id}-share-${value}`}
								value={value}
								aria-labelledby={`${id}-share-${value}-title`}
								aria-describedby={`${id}-share-${value}-detail`}
							/>
							<FieldContent>
								<FieldTitle id={`${id}-share-${value}-title`}>{copy.title}</FieldTitle>
								<FieldDescription id={`${id}-share-${value}-detail`}>
									{copy.detail}
								</FieldDescription>
							</FieldContent>
						</Field>
					</FieldLabel>
				))}
			</RadioGroup>
			<FieldDescription id={`${id}-share-footnote`}>{SHARING_COPY.footnote}</FieldDescription>
		</FieldSet>
	);
}
