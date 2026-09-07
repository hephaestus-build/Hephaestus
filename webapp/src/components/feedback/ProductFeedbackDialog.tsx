import { MessageSquarePlus } from "lucide-react";
import { useId, useState } from "react";

import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
	Dialog,
	DialogContent,
	DialogDescription,
	DialogHeader,
	DialogTitle,
	DialogTrigger,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";

const FEEDBACK_KINDS = [
	{ label: "Feedback", value: "FEEDBACK" },
	{ label: "Bug report", value: "BUG" },
] as const;

interface ProductFeedbackDialogProps {
	isSubmitting: boolean;
	error?: string;
	pagePath?: string;
	onSubmit: (
		kind: "FEEDBACK" | "BUG",
		message: string,
		includePagePath: boolean,
	) => Promise<boolean>;
}

export function ProductFeedbackDialog({
	isSubmitting,
	onSubmit,
	error,
	pagePath,
}: ProductFeedbackDialogProps) {
	const id = useId();
	const [includePagePath, setIncludePagePath] = useState(false);
	const [open, setOpen] = useState(false);
	const [kind, setKind] = useState<"FEEDBACK" | "BUG">("FEEDBACK");
	const [message, setMessage] = useState("");
	const submit = async () => {
		if (!message.trim() || isSubmitting) return;
		if (await onSubmit(kind, message.trim(), includePagePath)) {
			setMessage("");
			setIncludePagePath(false);
			setOpen(false);
		}
	};
	return (
		<Dialog open={open} onOpenChange={setOpen}>
			<DialogTrigger
				render={<Button variant="ghost" size="icon" aria-label="Send product feedback" />}
			>
				<MessageSquarePlus />
			</DialogTrigger>
			<DialogContent>
				<DialogHeader>
					<DialogTitle>Send product feedback</DialogTitle>
					<DialogDescription>
						Your message is stored on this Hephaestus instance and is visible to its administrators.
						It is linked to your account, not anonymous. No logs, configuration, or page content are
						attached. Do not include secrets or sensitive personal data. Contact your instance
						administrator to object to or request deletion of a submission.
					</DialogDescription>
				</DialogHeader>
				<form
					className="space-y-4"
					onSubmit={(event) => {
						event.preventDefault();
						void submit();
					}}
				>
					<fieldset disabled={isSubmitting} className="space-y-4">
						<legend className="sr-only">Product feedback</legend>
						<div className="space-y-2">
							<Label id={`${id}-kind-label`} htmlFor={`${id}-kind`}>
								Type
							</Label>
							<Select
								disabled={isSubmitting}
								items={FEEDBACK_KINDS}
								value={kind}
								onValueChange={(value) => value && setKind(value)}
							>
								<SelectTrigger id={`${id}-kind`}>
									<SelectValue />
								</SelectTrigger>
								<SelectContent aria-labelledby={`${id}-kind-label`}>
									{FEEDBACK_KINDS.map((item) => (
										<SelectItem key={item.value} value={item.value}>
											{item.label}
										</SelectItem>
									))}
								</SelectContent>
							</Select>
						</div>
						<div className="space-y-2">
							<Label htmlFor={`${id}-message`}>Message</Label>
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
						</div>
						<p id={`${id}-hint`} className="text-xs text-muted-foreground">
							{message.length.toLocaleString()} / 5,000 characters. Closing keeps this draft until
							you reload, leave this workspace, or sign out.
						</p>
						{pagePath && (
							<div className="space-y-2">
								<div className="flex items-center gap-2">
									<Checkbox
										disabled={isSubmitting}
										id={`${id}-path`}
										checked={includePagePath}
										onCheckedChange={setIncludePagePath}
									/>
									<Label htmlFor={`${id}-path`}>Include current page path</Label>
								</div>
								<p className="break-all text-xs text-muted-foreground">{pagePath}</p>
							</div>
						)}
					</fieldset>
					<p role="alert" className="text-sm text-destructive">
						{error}
					</p>
					<div className="flex justify-end">
						<Button type="submit" disabled={!message.trim() || isSubmitting}>
							{isSubmitting ? "Sending…" : "Send"}
						</Button>
					</div>
				</form>
			</DialogContent>
		</Dialog>
	);
}
