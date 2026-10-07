import assert from "node:assert/strict";
import { test } from "node:test";
import { parseTestList, parseTestName, runFilter } from "../src/testnames";

test("parseTestName splits module, suite and test", () => {
	assert.deepEqual(parseTestName('probe.tests::check("numbers")::TestAdds'), { module: "probe.tests", suite: "numbers", test: "TestAdds" });
	assert.deepEqual(parseTestName('m::check("a (b)")::t'), { module: "m", suite: "a (b)", test: "t" });
	assert.deepEqual(parseTestName('m::check("say \\"hi\\"")::t'), { module: "m", suite: 'say "hi"', test: "t" });
	assert.deepEqual(parseTestName('m::check("a::b")::t'), { module: "m", suite: "a::b", test: "t" });
});

test("parseTestName answers nothing for other names", () => {
	assert.equal(parseTestName("m::bench"), undefined);
	assert.equal(parseTestName(""), undefined);
});

test("parseTestList reads what adm test --list --json prints", () => {
	const output = JSON.stringify([
		{ name: 'probe.tests::check("numbers")::TestAdds', module: "probe.tests", file: "app/main_test.adm", line: 5 },
		{ name: "loose", file: "a_test.adm" },
		{ module: "nameless", file: "b_test.adm", line: 1 },
	]);
	assert.deepEqual(parseTestList(output), [
		{ name: 'probe.tests::check("numbers")::TestAdds', module: "probe.tests", file: "app/main_test.adm", line: 5 },
		{ name: "loose", module: "", file: "a_test.adm", line: 1 },
	]);
	assert.deepEqual(parseTestList("building tests..."), []);
	assert.deepEqual(parseTestList("[]"), []);
});

test("runFilter names each test exactly, or nothing for all of them", () => {
	assert.deepEqual(runFilter(["a", "b"], 2), []);
	assert.deepEqual(runFilter(["a"], 2), ["--run", "=a"]);
	assert.deepEqual(runFilter(['m::check("s")::t', "b"], 5), ["--run", '=m::check("s")::t', "--run", "=b"]);
});
