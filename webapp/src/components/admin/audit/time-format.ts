import { asDate } from "@/lib/dates";

/** The generated client types these as `Date`; at runtime they are ISO strings. */
type Timestamp = Date | string | null | undefined;

export interface FormattedTimestamp {
	local: string;
	isoUtc: string;
}

/** `null` for a missing or invalid timestamp — an audit surface must not invent a time. */
export function formatTimestamp(value: Timestamp): FormattedTimestamp | null {
	const date = asDate(value);
	if (!date) {
		return null;
	}
	return {
		local: date.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "medium" }),
		isoUtc: date.toISOString(),
	};
}
