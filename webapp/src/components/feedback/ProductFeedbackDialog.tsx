import { useId, useState } from "react";

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
	FieldLabel,
	FieldLegend,
	FieldSet,
	FieldTitle,
} from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";

import { FEEDBACK_KIND_COPY, type FeedbackKind, READERS } from "./feedback-copy";

/** The server's `FeedbackRequest` limits; a longer value is refused, so the sender never sees a cut. */
export const MESSAGE_MAX_LENGTH = 5000;
export const PAGE_PATH_MAX_LENGTH = 500;
export const USER_AGENT_MAX_LENGTH = 500;
/** The counter appears once this many characters are left, not before: a count nobody is near is noise. */
const COUNTER_THRESHOLD = 500;

const KINDS: FeedbackKind[] = ["IDEA", "BUG", "FEEDBACK"];

export interface FeedbackContext {
	pagePath: string;
	userAgent: string;
}

export interface ProductFeedbackDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	kind: FeedbackKind;
	onKindChange: (kind: FeedbackKind) => void;
	context?: FeedbackContext;
	isSubmitting: boolean;
	error?: string;
	/** Resolves to whether the submission was accepted; the draft is cleared only then. */
	onSubmit: (request: FeedbackRequest) => Promise<boolean>;
}

/**
 * The message draft lives in this component and survives closing because the header keeps it
 * mounted; only an accepted send clears it. The details box starts ticked for a bug report, where
 * the page and browser are what make it reproducible, and unticked otherwise.
 */
export function ProductFeedbackDialog({
	open,
	onOpenChange,
	kind,
	onKindChange,
	context,
	isSubmitting,
	error,
	onSubmit,
}: ProductFeedbackDialogProps) {
	const id = useId();
	const [message, setMessage] = useState("");
	const [includeContext, setIncludeContext] = useState<boolean>();
	const attachContext = includeContext ?? kind === "BUG";
	const copy = FEEDBACK_KIND_COPY[kind];
	const remaining = MESSAGE_MAX_LENGTH - message.length;
	const submit = async () => {
		if (!message.trim() || isSubmitting) return;
		const accepted = await onSubmit({
			kind,
			message: message.trim(),
			pagePath: attachContext ? context?.pagePath : undefined,
			userAgent: attachContext ? context?.userAgent : undefined,
		});
		if (accepted) {
			setMessage("");
			setIncludeContext(undefined);
			onOpenChange(false);
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
						<DialogTitle>{copy.heading}</DialogTitle>
						<DialogDescription>
							Goes straight to {READERS}, with your name so they can follow up.
						</DialogDescription>
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
									<span>Leave out secrets and other people's personal data.</span>
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
											Attach the page and browser you're on
										</FieldLabel>
										<FieldDescription className="break-all">
											<code className="text-xs">{context.pagePath}</code>
											<br />
											<span className="text-xs">{context.userAgent}</span>
										</FieldDescription>
									</FieldContent>
								</Field>
							)}
						</FieldSet>
						<p role="alert" className="text-sm text-destructive empty:hidden">
							{error}
						</p>
					</DialogBody>
					<DialogFooter>
						<Button type="submit" disabled={!message.trim() || isSubmitting}>
							{isSubmitting && <Spinner />}
							{isSubmitting ? "Sending…" : "Send"}
						</Button>
					</DialogFooter>
				</DialogForm>
			</DialogContent>
		</Dialog>
	);
}
