/** The samples of one metric in a Prometheus text exposition, keyed by their label set. */
export function samples(exposition: string, metric: string): ReadonlyMap<string, number> {
	const found = new Map<string, number>();
	for (const line of exposition.split("\n")) {
		const match = /^(?<name>[a-zA-Z_:][\w:]*)(?<labels>\{[^}]*\})?\s+(?<value>\S+)/u.exec(line);
		if (match?.groups?.name === metric) {
			found.set(match.groups.labels ?? "", Number(match.groups.value));
		}
	}
	return found;
}

/** The one finite sample of `metric` whose label set contains `label`. */
export function sample(exposition: string, metric: string, label = ""): number {
	const values = [...samples(exposition, metric)].filter(([labels]) => labels.includes(label));
	const [only, ...others] = values;
	if (only === undefined || others.length > 0 || !Number.isFinite(only[1])) {
		throw new Error(`Expected one sample of ${metric}${label}, found ${String(values.length)}`);
	}
	return only[1];
}
