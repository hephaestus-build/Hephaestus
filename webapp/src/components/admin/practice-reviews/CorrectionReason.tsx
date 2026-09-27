import type { LucideIcon } from "lucide-react";
import { useId, useState } from "react";

import { RelativeTime } from "@/components/common/RelativeTime";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import {
	Popover,
	PopoverContent,
	PopoverDescription,
	PopoverHeader,
	PopoverTitle,
	PopoverTrigger,
} from "@/components/ui/popover";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { hasText } from "@/lib/text";

export interface CorrectionReasonCopy {
	trigger: string;
	title: string;
	description: string;
	placeholder: string;
}

/**
 * An admin correction that needs a written reason: marking an observation incorrect, withdrawing feedback, and
 * their reversals. The reason stays in the form until `onSubmit` succeeded, so a refused change can be retried.
 */
export function CorrectionReasonPopover({
	copy,
	icon: Icon,
	destructive,
	name,
	disabled,
	onSubmit,
}: {
	copy: CorrectionReasonCopy;
	icon: LucideIcon;
	/** Taking something back is destructive; its reversal is not. */
	destructive: boolean;
	/** The textarea's form name. */
	name: string;
	disabled: boolean;
	onSubmit: (reason: string) => Promise<unknown>;
}) {
	const [open, setOpen] = useState(false);
	const [reason, setReason] = useState("");
	const reasonId = useId();
	const submit = async () => {
		try {
			await onSubmit(reason.trim());
			setReason("");
			setOpen(false);
		} catch {
			// The route reports the failure; the reason stays for another try.
		}
	};
	return (
		<Popover open={open} onOpenChange={setOpen}>
			<PopoverTrigger render={<Button variant="outline" disabled={disabled} />}>
				{disabled ? <Spinner /> : <Icon />}
				{copy.trigger}
			</PopoverTrigger>
			<PopoverContent align="end" className="w-[min(24rem,calc(100vw-2rem))] gap-4 p-4">
				<PopoverHeader>
					<PopoverTitle>{copy.title}</PopoverTitle>
					<PopoverDescription>{copy.description}</PopoverDescription>
				</PopoverHeader>
				<Field>
					<FieldLabel htmlFor={reasonId}>Reason</FieldLabel>
					<Textarea
						id={reasonId}
						name={name}
						autoComplete="off"
						value={reason}
						onChange={(event) => setReason(event.target.value)}
						maxLength={500}
						placeholder={copy.placeholder}
					/>
					<FieldDescription>Required · {reason.length}/500</FieldDescription>
				</Field>
				<div className="flex justify-end gap-2 border-t pt-3">
					<Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
						Cancel
					</Button>
					<Button
						variant={destructive ? "destructive" : "default"}
						size="sm"
						disabled={disabled || !hasText(reason.trim())}
						onClick={() => {
							void submit();
						}}
					>
						{disabled && <Spinner />}
						{copy.trigger}
					</Button>
				</div>
			</PopoverContent>
		</Popover>
	);
}

/** One entry in a correction history: who did what, when, and why. */
export function CorrectionEntry({
	action,
	actor,
	at,
	reason,
	note,
}: {
	action: string;
	actor: string | undefined;
	at: Date;
	reason: string | undefined;
	note?: string;
}) {
	return (
		<li className="space-y-1 rounded-lg border p-3">
			<p className="text-muted-foreground">
				<span className="font-medium text-foreground">{action}</span> by{" "}
				{actor ?? "an account that no longer exists"} <RelativeTime value={at} />
			</p>
			{hasText(reason) && <p className="break-words whitespace-pre-wrap">{reason}</p>}
			{hasText(note) && <p className="text-muted-foreground">{note}</p>}
		</li>
	);
}
