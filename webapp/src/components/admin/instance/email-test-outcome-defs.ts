import {
	BanIcon,
	CircleCheckIcon,
	CircleMinusIcon,
	ClockIcon,
	GaugeIcon,
	MailQuestionIcon,
	MailXIcon,
	PlugZapIcon,
	UserRoundXIcon,
	VolumeXIcon,
} from "lucide-react";

import type { EmailTestResponse } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

export type EmailTestOutcome = EmailTestResponse["outcome"];

export const EMAIL_TEST_OUTCOME_DEFS: StatusDefs<EmailTestOutcome> = {
	SENT: {
		label: "Handed to mail server",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "The mail server accepted the message. Look for the message ID in its log.",
	},
	EXPIRED: {
		label: "Expired",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description: "The notification is too old to be useful, so it was not sent.",
	},
	NOT_CONFIGURED: {
		label: "Not configured",
		icon: PlugZapIcon,
		badgeVariant: "outline",
		description:
			"The mail server host or the sender address is not set. Configure SPRING_MAIL_HOST and HEPHAESTUS_EMAIL_FROM.",
	},
	SILENT_MODE: {
		label: "Withheld by silent mode",
		icon: VolumeXIcon,
		badgeVariant: "warning",
		description: "Silent mode withheld this email. Turn off silent mode before trying again.",
	},
	NO_RECIPIENT: {
		label: "No recipient",
		icon: UserRoundXIcon,
		badgeVariant: "secondary",
		description:
			"Your account has no provider-verified email address. Enter a recipient to test with.",
	},
	INVALID_ADDRESS: {
		label: "Address not valid",
		icon: MailQuestionIcon,
		badgeVariant: "destructive",
		description: "The recipient must be one valid email address. Check it and try again.",
	},
	REJECTED: {
		label: "Rejected by mail server",
		icon: MailXIcon,
		badgeVariant: "destructive",
		description:
			"The mail server rejected this email. Check the recipient and the server’s policy, then try again.",
	},
	UNSUBSCRIBED: {
		label: "Not subscribed",
		icon: CircleMinusIcon,
		badgeVariant: "secondary",
		description: "The recipient has not opted in to this optional email.",
	},
	RATE_LIMITED: {
		label: "Rate limited",
		icon: GaugeIcon,
		badgeVariant: "warning",
		description:
			"Sending is paused. The email attempt limit was reached, or capacity could not be checked. Try again in a moment.",
	},
	UNAVAILABLE: {
		label: "Mail server unavailable",
		icon: BanIcon,
		badgeVariant: "destructive",
		description:
			"We could not reach the mail server, or it refused the credentials. Check the host, port, TLS and the account.",
	},
};
