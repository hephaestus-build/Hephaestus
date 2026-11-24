import { isRecord } from "./is-record";

/** Compare draft fields by value, preserving array order and explicit undefined fields. */
export function equalDraftValues(left: unknown, right: unknown): boolean {
	if (left === right || (Number.isNaN(left) && Number.isNaN(right))) {
		return true;
	}

	if (Array.isArray(left) && Array.isArray(right)) {
		if (left.length !== right.length) {
			return false;
		}
		for (let index = 0; index < left.length; index += 1) {
			if (!equalDraftValues(left[index], right[index])) {
				return false;
			}
		}
		return true;
	}

	if (!isRecord(left) || !isRecord(right)) {
		return false;
	}
	const fields = Object.entries(left);
	return (
		fields.length === Object.keys(right).length &&
		fields.every(([key, value]) => Object.hasOwn(right, key) && equalDraftValues(value, right[key]))
	);
}
