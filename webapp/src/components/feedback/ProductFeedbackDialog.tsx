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

export type FeedbackKind = FeedbackRequest["kind"];

const KINDS: { value: FeedbackKind; title: string; detail: string }[] = [
	{ value: "FEEDBACK", title: "Feedback", detail: "An idea, or what works and what does not." },
	{ value: "BUG", title: "Bug report", detail: "Something broke or behaved unexpectedly." },
];

/** What "page and browser details" attaches, shown to the sender before they choose. */
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
 * Free-text feedback to the instance administrators. The message draft survives closing the dialog
 * (the component stays mounted in the header) and is cleared only by a successful send. Page and
 * browser details are attached only when the sender ticks the box; the box starts ticked for bug
 * reports, where the details are what makes the report reproducible, and unticked otherwise.
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
						<DialogTitle>Send product feedback</DialogTitle>
						<DialogDescription>
							Goes to this instance's administrators, linked to your account. It is not anonymous
							and is not sent to the Hephaestus project.
						</DialogDescription>
					</DialogHeader>
					<DialogBody className="flex flex-col gap-5 py-1">
						<FieldSet disabled={isSubmitting} className="gap-5">
							<FieldLegend className="sr-only">Product feedback</FieldLegend>
							<RadioGroup
								aria-label="Type"
								value={kind}
								onValueChange={onKindChange}
								className="grid gap-2 sm:grid-cols-2"
							>
								{KINDS.map((option) => (
									<FieldLabel key={option.value} htmlFor={`${id}-${option.value}`}>
										<Field orientation="horizontal" className="rounded-md border px-3 py-2">
											<RadioGroupItem id={`${id}-${option.value}`} value={option.value} />
											<FieldContent>
												<FieldTitle>{option.title}</FieldTitle>
												<FieldDescription>{option.detail}</FieldDescription>
											</FieldContent>
										</Field>
									</FieldLabel>
								))}
							</RadioGroup>
							<Field>
								<FieldLabel htmlFor={`${id}-message`}>Message</FieldLabel>
								<Textarea
									id={`${id}-message`}
									name="message"
									required
									value={message}
									maxLength={5000}
									rows={5}
									aria-describedby={`${id}-hint`}
									placeholder={
										kind === "BUG"
											? "What were you doing? What happened, and what did you expect?"
											: "What worked well, or what would make Hephaestus more useful?"
									}
									onChange={(event) => setMessage(event.target.value)}
								/>
								<FieldDescription id={`${id}-hint`}>
									{message.length.toLocaleString()} / 5,000 · Leave out secrets and personal data
									about others. Closing keeps this draft until you reload or sign out.
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
											Include page and browser details
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
