import { EyeOffIcon } from "lucide-react";
import { useRef, useState } from "react";

import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";

export interface HidePersonActionProps {
	name: string;
	pending: boolean;
	onConfirm: () => void;
}

/**
 * An admin's way to leave a person out of every activity page, public and private, for someone who
 * asks and has no account to hide themselves with. It asks first, since the person then leaves the
 * very list this button sits in.
 */
export function HidePersonAction({ name, pending, onConfirm }: HidePersonActionProps) {
	const [open, setOpen] = useState(false);
	const titleRef = useRef<HTMLHeadingElement>(null);
	return (
		<>
			<Button
				variant="outline"
				size="sm"
				disabled={pending}
				focusableWhenDisabled
				onClick={() => setOpen(true)}
			>
				{pending ? <Spinner /> : <EyeOffIcon aria-hidden />}
				{pending ? "Hiding…" : "Hide from activity"}
			</Button>
			<AlertDialog open={open} onOpenChange={setOpen}>
				<AlertDialogContent initialFocus={titleRef}>
					<AlertDialogHeader>
						<AlertDialogTitle ref={titleRef} tabIndex={-1}>
							Hide {name} from activity?
						</AlertDialogTitle>
						<AlertDialogDescription>
							{name} leaves the public activity page, workspace activity and their totals. Their
							work stays in the source repositories. You can undo this right after, or under Members
							if they are a member.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							onClick={() => {
								setOpen(false);
								onConfirm();
							}}
						>
							Hide
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</>
	);
}
