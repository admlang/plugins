import assert from "node:assert/strict";
import { test } from "node:test";
import { parseUnits, unitFor } from "../src/units";

const listed = JSON.stringify([
	{ kind: "application", name: "Shop", file: "/w/shop/main.adm", line: 1, dir: "/w/shop", manifest: null, version: "0.1" },
	{ kind: "library", name: "acme.cart", file: "/w/shop/cart/library.adm", line: 3, dir: "/w/shop/cart", manifest: "/w/shop/cart/adm.toml", version: "1.2.0" },
	{ kind: "gadget", name: "x", file: "/w/x.adm", line: 1, dir: "/w" },
	{ kind: "plugin", name: 7, file: "/w/p.adm", dir: "/w/p" },
]);

test("parseUnits keeps the units it understands", () => {
	const units = parseUnits(listed);
	assert.deepEqual(units.map((u) => u.name), ["Shop", "acme.cart"]);
	assert.equal(units[0].manifest, undefined);
	assert.equal(units[1].manifest, "/w/shop/cart/adm.toml");
	assert.equal(units[1].line, 3);
});

test("parseUnits answers nothing for other output", () => {
	assert.deepEqual(parseUnits(""), []);
	assert.deepEqual(parseUnits("error: no such folder"), []);
	assert.deepEqual(parseUnits('{"kind":"application"}'), []);
});

test("unitFor picks the deepest folder that holds the file", () => {
	const units = parseUnits(listed);
	assert.equal(unitFor(units, "/w/shop/main.adm")?.name, "Shop");
	assert.equal(unitFor(units, "/w/shop/cart/items.adm")?.name, "acme.cart");
	assert.equal(unitFor(units, "/w/shop/cart")?.name, "acme.cart");
	assert.equal(unitFor(units, "/w/shopping/main.adm"), undefined);
	assert.equal(unitFor(units, "/elsewhere/a.adm"), undefined);
});

test("unitFor follows the separator it is given", () => {
	const units = parseUnits(JSON.stringify([{ kind: "application", name: "A", file: "C:\\w\\a\\main.adm", line: 1, dir: "C:\\w\\a" }]));
	assert.equal(unitFor(units, "C:\\w\\a\\main.adm", "\\")?.name, "A");
	assert.equal(unitFor(units, "C:\\w\\ab\\main.adm", "\\"), undefined);
});
