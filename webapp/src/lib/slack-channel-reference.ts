const SLACK_CHANNEL_ID = /^[CG][A-Z0-9]{8,}$/u;
// Anchored to a Slack archive URL path segment so an unrelated all-caps word pasted in prose
// (e.g. shouting "PLEASE HELP ASAP") can't be mistaken for a channel id.
const SLACK_CHANNEL_ID_IN_TEXT = /\/archives\/(?<id>[CG][A-Z0-9]{8,})(?:[/?#]|$)/u;
const SLACK_MENTION = /^<#(?<id>[CG][A-Z0-9]{8,})(?:\|(?<name>[^>]+))?>$/u;

export interface SlackChannelReference {
	channelId: string;
	channelName?: string;
}

export function parseSlackChannelReference(value: string): SlackChannelReference | null {
	const trimmed = value.trim();
	if (SLACK_CHANNEL_ID.test(trimmed)) {
		return { channelId: trimmed };
	}

	const mention = SLACK_MENTION.exec(trimmed)?.groups;
	if (mention?.id !== undefined) {
		return { channelId: mention.id, channelName: mention.name };
	}

	const id = SLACK_CHANNEL_ID_IN_TEXT.exec(trimmed)?.groups?.id;
	return id ? { channelId: id } : null;
}
