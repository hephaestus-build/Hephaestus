/** The values a label table names, so that a URL can hold no value the server would refuse. */
export function valuesOf<T extends string>(labels: Record<T, string>): T[] {
	const values: T[] = [];
	for (const value in labels) {
		if (Object.hasOwn(labels, value)) {
			values.push(value);
		}
	}
	return values;
}
