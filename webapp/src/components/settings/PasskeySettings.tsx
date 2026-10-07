import {
	ConfirmAccessDialog,
	type ConfirmAccessDialogProps,
} from "@/components/auth/ConfirmAccessDialog";
import { PasskeySection, type PasskeySectionProps } from "./PasskeySection";

export function PasskeySettings({
	section,
	confirmation,
}: {
	section: PasskeySectionProps;
	confirmation: ConfirmAccessDialogProps;
}) {
	return (
		<div id="passkeys">
			<PasskeySection {...section} />
			<ConfirmAccessDialog {...confirmation} />
		</div>
	);
}
