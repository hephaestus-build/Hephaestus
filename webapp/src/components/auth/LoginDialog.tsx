import { SignInButtons, type SignInButtonsProps } from "@/components/auth/SignInButtons";
import { SignInNotice } from "@/components/auth/SignInNotice";
import {
	Dialog,
	DialogBody,
	DialogContent,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";

interface LoginDialogProps extends SignInButtonsProps {
	open: boolean;
	onClose: () => void;
}

export function LoginDialog({ open, onClose, ...signIn }: LoginDialogProps) {
	return (
		<Dialog
			open={open}
			onOpenChange={(next) => {
				if (!next) onClose();
			}}
		>
			<DialogContent>
				<DialogHeader className="pr-8">
					<DialogTitle>Sign in to Hephaestus</DialogTitle>
				</DialogHeader>
				<DialogBody className="space-y-4">
					<SignInButtons {...signIn} />
					<SignInNotice />
				</DialogBody>
			</DialogContent>
		</Dialog>
	);
}
