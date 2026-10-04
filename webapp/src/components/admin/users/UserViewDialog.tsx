import { type SubmitEvent, useId, useState } from "react";

import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogBody,
	DialogClose,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogForm,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Textarea } from "@/components/ui/textarea";

const REASON_MAX_LENGTH = 500;

export interface UserViewDialogProps {
	name: string;
	isPending?: boolean;
	error?: string;
	onClose: () => void;
	onConfirm: (reason: string) => void;
}

export function UserViewDialog({
	name,
	isPending = false,
	error,
	onClose,
	onConfirm,
}: UserViewDialogProps) {
	const fieldId = useId();
	const descriptionId = useId();
	const [reason, setReason] = useState("");
	const trimmed = reason.trim();

	const handleSubmit = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		if (trimmed) {
			onConfirm(trimmed);
		}
	};

	return (
		<Dialog
			open
			onOpenChange={(open) => {
				if (!open) {
					onClose();
				}
			}}
		>
			<DialogContent>
				<DialogForm onSubmit={handleSubmit}>
					<DialogHeader>
						<DialogTitle>View as {name}</DialogTitle>
						<DialogDescription>
							You can view this user’s workspace pages, including private feedback and saved Heph
							conversations. Access is read-only, and you stay signed in as yourself. Each access is
							audited. You cannot change this user’s account or settings.
						</DialogDescription>
					</DialogHeader>
					<DialogBody className="py-1">
						<FieldGroup>
							{error != null && (
								<Alert variant="destructive">
									<AlertDescription>{error}</AlertDescription>
								</Alert>
							)}
							<Field>
								<FieldLabel htmlFor={fieldId}>Reason for access</FieldLabel>
								<Textarea
									id={fieldId}
									required
									maxLength={REASON_MAX_LENGTH}
									value={reason}
									aria-describedby={descriptionId}
									onChange={(event) => setReason(event.target.value)}
									// oxlint-disable-next-line jsx-a11y/no-autofocus -- The dialog opens to collect this one reason and holds no other writable control.
									autoFocus
								/>
								<FieldDescription id={descriptionId}>
									Describe the support need. Do not include secrets or private feedback.
								</FieldDescription>
							</Field>
						</FieldGroup>
					</DialogBody>
					<DialogFooter>
						<DialogClose render={<Button type="button" variant="outline" />}>Cancel</DialogClose>
						<Button type="submit" disabled={trimmed === "" || isPending}>
							{isPending ? "Opening…" : "View as user"}
						</Button>
					</DialogFooter>
				</DialogForm>
			</DialogContent>
		</Dialog>
	);
}
