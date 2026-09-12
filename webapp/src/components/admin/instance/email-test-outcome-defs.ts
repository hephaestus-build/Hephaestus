import {
	BanIcon,
	CircleCheckIcon,
	ClockIcon,
	MailQuestionIcon,
	MailXIcon,
	PlugZapIcon,
	UserRoundXIcon,
	VolumeXIcon,
} from "lucide-react";

import type { EmailTestResponse } from "@/api/types.gen";
import type { StatusDefs } from "@/components/practice-vocabulary/status-def";

export type EmailTestOutcome = EmailTestResponse["outcome"];

export const EMAIL_TEST_OUTCOME_DEFS: StatusDefs<EmailTestOutcome> = {
	SENT: {
		label: "Accepted by relay",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "The relay accepted the message. Look for the message id in the relay's log.",
	},
	EXPIRED: {
		label: "Expired",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description: "The notification is no longer timely and was not sent.",
	},
	NOT_CONFIGURED: {
		label: "Not configured",
		icon: PlugZapIcon,
		badgeVariant: "outline",
		description:
			"No relay host or no sender address is set. Configure SPRING_MAIL_HOST and HEPHAESTUS_EMAIL_FROM.",
	},
	SILENT_MODE: {
		label: "Withheld by silent mode",
		icon: VolumeXIcon,
		badgeVariant: "warning",
		description: "Silent mode withheld this email. Release silent mode before trying again.",
	},
	NO_RECIPIENT: {
		label: "No recipient",
		icon: UserRoundXIcon,
		badgeVariant: "secondary",
		description:
			"Your account has no provider-verified email address. Enter a recipient to test with.",
	},
	INVALID_ADDRESS: {
		label: "Invalid address",
		icon: MailQuestionIcon,
		badgeVariant: "destructive",
		description: "The recipient is not a single valid mailbox.",
	},
	REJECTED: {
		label: "Rejected by relay",
		icon: MailXIcon,
		badgeVariant: "destructive",
		description:
			"The relay rejected this email. Check the recipient and relay policy before trying again.",
	},
	UNAVAILABLE: {
		label: "Relay unavailable",
		icon: BanIcon,
		badgeVariant: "destructive",
		description:
			"The relay could not be reached or refused the credentials. Check host, port, TLS and the account.",
	},
};
