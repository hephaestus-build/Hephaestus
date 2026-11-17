/**
 * The CISA Known Exploited Vulnerabilities catalog: the CVEs that CISA has evidence of exploitation for.
 * https://www.cisa.gov/known-exploited-vulnerabilities-catalog documents the feed and its fields.
 *
 * A listed CVE is urgent whatever its severity or fix state, so a scheduled rescan reports a match at once
 * instead of waiting for a fix to make the finding blocking.
 */
import { asArray, asRecord, asString } from "./json.ts";

export const KEV_FEED =
	"https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json";

/** The catalog is about 1.5 MB in 2026. The ceiling stops a runaway response, not a growing catalog. */
const KEV_LIMIT_BYTES = 32 * 1024 * 1024;

const CVE = /^CVE-\d{4}-\d{4,}$/u;

export interface KevEntry {
	readonly cveID: string;
	readonly dateAdded: string;
	readonly dueDate: string;
	readonly knownRansomwareCampaignUse: string;
	readonly vulnerabilityName: string;
}

export interface KevCatalog {
	readonly catalogVersion: string;
	readonly entries: ReadonlyMap<string, KevEntry>;
}

/** A catalog that does not have the documented shape throws: an empty match from a bad feed is a lie. */
export function parseKev(value: unknown): KevCatalog {
	const catalog = asRecord(value, "KEV catalog");
	const catalogVersion = asString(catalog.catalogVersion, "KEV catalog.catalogVersion");
	const vulnerabilities = asArray(catalog.vulnerabilities, "KEV catalog.vulnerabilities");
	if (vulnerabilities.length === 0) {
		throw new Error("KEV catalog lists no vulnerabilities");
	}
	const entries = new Map<string, KevEntry>();
	for (const [index, item] of vulnerabilities.entries()) {
		const label = `KEV catalog.vulnerabilities[${index}]`;
		const entry = asRecord(item, label);
		const cveID = asString(entry.cveID, `${label}.cveID`);
		if (!CVE.test(cveID)) {
			throw new Error(`${label}.cveID is not a CVE ID: ${cveID}`);
		}
		entries.set(cveID, {
			cveID,
			dateAdded: asString(entry.dateAdded, `${label}.dateAdded`),
			dueDate: asString(entry.dueDate, `${label}.dueDate`),
			knownRansomwareCampaignUse: asString(
				entry.knownRansomwareCampaignUse,
				`${label}.knownRansomwareCampaignUse`,
			),
			vulnerabilityName: asString(entry.vulnerabilityName, `${label}.vulnerabilityName`),
		});
	}
	return { catalogVersion, entries };
}

/** One HTTPS request with no redirect, bounded in time and size. */
export async function fetchKev(): Promise<KevCatalog> {
	const response = await fetch(KEV_FEED, {
		redirect: "error",
		signal: AbortSignal.timeout(60_000),
	});
	if (response.status !== 200 || response.body === null) {
		throw new Error(`the KEV catalog answered ${response.status}`);
	}
	const chunks: Uint8Array[] = [];
	let size = 0;
	for await (const chunk of response.body) {
		size += chunk.byteLength;
		if (size > KEV_LIMIT_BYTES) {
			throw new Error(`the KEV catalog exceeds ${KEV_LIMIT_BYTES} bytes`);
		}
		chunks.push(chunk);
	}
	return parseKev(JSON.parse(Buffer.concat(chunks).toString("utf8")));
}
