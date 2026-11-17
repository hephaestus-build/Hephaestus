import assert from "node:assert/strict";
import test from "node:test";

import { sample, samples } from "./prometheus-text.ts";

const exposition = [
	"# HELP jvm_memory_used_bytes The amount of used memory",
	"# TYPE jvm_memory_used_bytes gauge",
	'jvm_memory_used_bytes{area="nonheap",id="Metaspace"} 6.8157440E7',
	'jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1.2E8',
	"jvm_classes_loaded_classes 38550.0",
	"jvm_classes_loaded_classes_total 1.0",
].join("\n");

void test("a metric's samples are keyed by their labels, and a longer name is another metric", () => {
	assert.equal(samples(exposition, "jvm_memory_used_bytes").size, 2);
	assert.equal(sample(exposition, "jvm_classes_loaded_classes"), 38_550);
	assert.equal(sample(exposition, "jvm_memory_used_bytes", 'id="Metaspace"'), 68_157_440);
});

void test("a missing or ambiguous sample is an error, never a number", () => {
	assert.throws(() => sample(exposition, "application_ready_time_seconds"), /found 0/u);
	assert.throws(() => sample(exposition, "jvm_memory_used_bytes"), /found 2/u);
});
