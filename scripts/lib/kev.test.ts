import assert from "node:assert/strict";
import { test } from "node:test";

import { parseKev } from "./kev.ts";

const entry = {
	cveID: "CVE-2026-114938",
	vendorProject: "OpenJDK",
	product: "OpenJDK",
	vulnerabilityName: "OpenJDK Base64 decoder remote code execution",
	dateAdded: "2026-11-17",
	dueDate: "2026-12-08",
	knownRansomwareCampaignUse: "Unknown",
};

void test("a catalog yields its entries by CVE ID", () => {
	const catalog = parseKev({ catalogVersion: "2026.11.17", vulnerabilities: [entry] });
	assert.equal(catalog.catalogVersion, "2026.11.17");
	assert.equal(catalog.entries.get("CVE-2026-114938")?.dueDate, "2026-12-08");
});

void test("a catalog without entries or of another shape fails rather than matching nothing", () => {
	assert.throws(
		() => parseKev({ catalogVersion: "x", vulnerabilities: [] }),
		/no vulnerabilities/u,
	);
	assert.throws(() => parseKev({ vulnerabilities: [entry] }), /catalogVersion/u);
	assert.throws(
		() => parseKev({ catalogVersion: "x", vulnerabilities: [{ ...entry, cveID: "GHSA-1" }] }),
		/not a CVE ID/u,
	);
	assert.throws(
		() => parseKev({ catalogVersion: "x", vulnerabilities: [{ ...entry, dueDate: 1 }] }),
		/dueDate/u,
	);
});
