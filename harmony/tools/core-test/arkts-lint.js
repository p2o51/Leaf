// Runs the ArkTS linter (from ohos-typescript, the compiler DevEco ships) over core/*.ets, so ArkTS rules
// such as no `any`, no destructuring and typed object literals are checked without the HarmonyOS SDK.
// core/ imports no @kit module, which is what makes this possible; the UI files need DevEco.
const ts = require('ohos-typescript');
const fs = require('fs');
const path = require('path');

const core = path.join(__dirname, '../../entry/src/main/ets/core');
const files = fs.readdirSync(core).filter((f) => f.endsWith('.ets')).map((f) => path.join(core, f));
const options = {
  target: ts.ScriptTarget.ES2021, lib: ['lib.es2021.d.ts'], strict: true, noEmit: true, incremental: true,
  tsBuildInfoFile: path.join(__dirname, 'out/arkts.tsbuildinfo'), module: ts.ModuleKind.ES2020,
  moduleResolution: ts.ModuleResolutionKind.NodeJs, compatibleSdkVersion: 12, skipLibCheck: true,
  ets: {
    render: { method: ['build'], decorator: ['Builder'] }, components: [], propertyDecorators: [], emitDecorators: [],
    extend: { decorator: ['Extend'], components: [] },
    styles: { decorator: 'Styles', component: { name: 'Common', type: 'T', instance: 'x' }, property: 'stateStyles' },
    customComponent: 'CustomComponent', syntaxComponents: { paramsUICallback: [], attrUICallback: [] }, libs: [],
  },
};
const program = ts.createIncrementalProgram({ rootNames: files, options, host: ts.createIncrementalCompilerHost(options) });
const diags = ts.ArkTSLinter_1_1.runArkTSLinter(program, undefined, undefined, 'ArkTS_1_1')
  .filter((d) => d.file && files.includes(path.resolve(d.file.fileName)));
for (const d of diags) {
  const p = d.file.getLineAndCharacterOfPosition(d.start);
  const msg = typeof d.messageText === 'string' ? d.messageText : ts.flattenDiagnosticMessageText(d.messageText, ' ');
  console.log(`${path.basename(d.file.fileName)}:${p.line + 1}:${p.character + 1} ${msg}`);
}
console.log(`ArkTS lint: ${files.length} files, ${diags.length} problems`);
process.exit(diags.length > 0 ? 1 : 0);
