const NUMBER_FORMAT = new Intl.NumberFormat("en-US");

/** Product counts use the same English number grammar on every browser. */
export function formatNumber(value: number): string {
	return NUMBER_FORMAT.format(value);
}
