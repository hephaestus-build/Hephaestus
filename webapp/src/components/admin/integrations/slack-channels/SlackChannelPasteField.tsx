import { LinkIcon } from "lucide-react";

import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";

export interface SlackChannelPasteFieldProps {
	id: string;
	/** The raw text the admin typed. Parsing (and the resolved id) belongs to the caller. */
	value: string;
	onChange: (value: string) => void;
	disabled?: boolean;
	/** The text is non-empty and does not parse to a Slack channel reference. */
	invalid?: boolean;
	description?: string;
}

/**
 * The escape hatch behind the combobox: a channel Slack did not list (typically a private one
 * the bot has not been invited to) can still be reached by pasting its link, mention or id.
 * Kept out of the main flow so there is exactly one primary control for the value.
 */
export function SlackChannelPasteField({
	id,
	value,
	onChange,
	disabled = false,
	invalid = false,
	description = "For a channel that is not in the list yet. Private channels only appear after someone invites Hephaestus to them in Slack.",
}: SlackChannelPasteFieldProps) {
	return (
		<Field data-invalid={invalid}>
			<FieldLabel htmlFor={id}>Paste a channel link or ID</FieldLabel>
			<InputGroup>
				<InputGroupAddon>
					<LinkIcon />
				</InputGroupAddon>
				<InputGroupInput
					id={id}
					value={value}
					disabled={disabled}
					onChange={(e) => onChange(e.target.value)}
					placeholder="https://…slack.com/archives/C0974LJBPBK"
					autoComplete="off"
					aria-invalid={invalid}
				/>
			</InputGroup>
			<FieldDescription>{description}</FieldDescription>
			{invalid && (
				<FieldError>Paste a Slack channel link, mention, or ID. IDs start with C or G.</FieldError>
			)}
		</Field>
	);
}
