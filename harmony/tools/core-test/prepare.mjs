// Copies harmony/entry/src/main/ets/core/*.ets to src/core/*.ts so tsc can type-check and compile them.
// The .ets files stay the source of truth; src/ is regenerated every run (and git-ignored).
import { cpSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const core = join(here, '../../entry/src/main/ets/core');
const out = join(here, 'src/core');
rmSync(join(here, 'src'), { recursive: true, force: true });
rmSync(join(here, 'out'), { recursive: true, force: true });
mkdirSync(out, { recursive: true });
for (const f of readdirSync(core)) {
  if (f.endsWith('.ets')) cpSync(join(core, f), join(out, f.replace(/\.ets$/, '.ts')));
}
