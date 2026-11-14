import type { LucideIcon } from "lucide-react";
import { useId, useState } from "react";

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
import { Textarea } from "@/components/ui/textarea";
import { hasText } from "@/lib/text";

export interface CorrectionReasonCopy {
	trigger: string;
	title: string;
	description: string;
	placeholder: string;
}

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
			// The controller reports the failure; the reason stays for another try.
		}
	};
	return (
		<Popover open={open} onOpenChange={setOpen}>
			<PopoverTrigger render={<Button pending={disabled} variant="outline" />}>
				<Icon />
				{copy.trigger}
			</PopoverTrigger>
			<PopoverContent align="end" side="top" className="w-[min(24rem,calc(100vw-2rem))] gap-4 p-4">
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
						pending={disabled}
						variant={destructive ? "destructive" : "default"}
						size="sm"
						disabled={disabled || !hasText(reason.trim())}
						onClick={() => {
							void submit();
						}}
					>
						{copy.trigger}
					</Button>
				</div>
			</PopoverContent>
		</Popover>
	);
}
