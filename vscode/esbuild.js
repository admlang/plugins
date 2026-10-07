// Bundles the extension into dist/extension.js: one file, with the language
// client inside, so the packaged extension carries no node_modules.
const esbuild = require("esbuild");

const production = process.argv.includes("--production");
const watch = process.argv.includes("--watch");

async function main() {
	const context = await esbuild.context({
		entryPoints: ["src/extension.ts"],
		bundle: true,
		format: "cjs",
		platform: "node",
		target: "node18",
		outfile: "dist/extension.js",
		external: ["vscode"],
		minify: production,
		sourcemap: !production,
		logLevel: "info",
	});
	if (watch) {
		await context.watch();
		return;
	}
	await context.rebuild();
	await context.dispose();
}

main().catch((err) => {
	console.error(err);
	process.exit(1);
});
