import { randomBytes } from 'node:crypto'
import { mkdir, open, stat } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
const path = resolve(process.argv[2] || 'data/secrets/sandbox.key')
await mkdir(dirname(path), { recursive: true, mode: 0o700 })
if ((await stat(dirname(path))).mode & 0o077) throw new Error('Service key directory must be private (0700).')
const file = await open(path, 'wx', 0o444)
try { await file.writeFile(randomBytes(32).toString('hex')); await file.chmod(0o444); await file.sync() } finally { await file.close() }
console.log(`Created service key file: ${path}. Existing keys are never replaced.`)
