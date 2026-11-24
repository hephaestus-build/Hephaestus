import { IntlMessageFormat } from "intl-messageformat";

import english from "./conversation-messages.en.json" with { type: "json" };

const messages = new Map(
	Object.entries(english).map(([key, template]) => [
		key,
		new IntlMessageFormat(template, "en", undefined, { ignoreTag: true }),
	]),
);

export function conversationMessage(
	key: keyof typeof english,
	values?: Record<string, string | number>,
): string {
	const result = messages.get(key)?.format(values) ?? english[key];
	return Array.isArray(result) ? result.join("") : String(result);
}

/** A server can send a code that this release has not added to its catalog yet. */
export function conversationLabel(label: { code: string; text: string }): string {
	const formatter = label.code.startsWith("conversation.stage.")
		? messages.get(label.code)
		: undefined;
	if (formatter === undefined) {
		return label.text;
	}
	const result = formatter.format();
	return Array.isArray(result) ? result.join("") : String(result);
}

export function formatCheckInTime(date: Date, timeZone: string, locale?: string): string {
	const time = new Intl.DateTimeFormat(locale, {
		dateStyle: "medium",
		timeStyle: "short",
		timeZone,
	}).format(date);
	return conversationMessage("checkInTime", { time, zone: timeZone });
}
