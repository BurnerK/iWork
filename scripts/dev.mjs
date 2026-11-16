import { spawn, execFileSync, spawnSync } from 'node:child_process';
import { randomBytes, createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync, realpathSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { parseEnv } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const configPath = resolve(root, '.env.development');

export function initialize(path, directory) {
  const secret = () => randomBytes(24).toString('hex');
  const config = {
    COMPOSE_PROJECT_NAME: `iwork-dev-${createHash('sha256').update(directory).digest('hex').slice(0, 8)}`,
    IWORK_DB_HOST: '127.0.0.1', IWORK_DB_PORT: '13306',
    IWORK_DB_NAME: 'iwork', IWORK_DB_USER: 'iwork',
    IWORK_DB_PASSWORD: secret(), IWORK_MYSQL_ROOT_PASSWORD: secret(),
    IWORK_REDIS_HOST: '127.0.0.1', IWORK_REDIS_PORT: '16379',
    IWORK_BOOTSTRAP_USERNAME: 'admin', IWORK_BOOTSTRAP_PASSWORD: secret(),
    IWORK_COOKIE_SECURE: 'false', IWORK_FILES_DIRECTORY: `${directory}/data/dev/files`,
    SERVER_ADDRESS: '127.0.0.1', SERVER_PORT: '8080',
  };
  try {
    writeFileSync(path, Object.entries(config).map(([key, value]) => `${key}='${value}'`).join('\n') + '\n',
      { flag: 'wx', mode: 0o600 });
    return true;
  } catch (error) {
    if (error.code === 'EEXIST') return false;
    throw error;
  }
}

export function loadConfig(path) {
  if (!existsSync(path)) throw new Error('缺少 .env.development，请先运行 npm run setup。');
  const env = parseEnv(readFileSync(path, 'utf8'));
  for (const key of ['IWORK_DB_PORT', 'IWORK_REDIS_PORT', 'SERVER_PORT']) {
    if (!/^\d+$/.test(env[key] ?? '') || Number(env[key]) < 1 || Number(env[key]) > 65535)
      throw new Error(`${key} 必须是 1–65535 的端口。`);
  }
  if (env.SERVER_PORT !== '8080') throw new Error('当前 Vite 代理固定为 8080，请保持 SERVER_PORT=8080。');
  if (!/^iwork-dev-[a-z0-9-]+$/.test(env.COMPOSE_PROJECT_NAME ?? ''))
    throw new Error('COMPOSE_PROJECT_NAME 必须使用 iwork-dev- 前缀，隔离交付环境。');
  for (const key of ['IWORK_DB_PASSWORD', 'IWORK_MYSQL_ROOT_PASSWORD']) {
    if (!env[key]) throw new Error(`${key} 不能为空。不要重新生成已有数据库的密码。`);
  }
  if (env.IWORK_DB_HOST !== '127.0.0.1' || env.IWORK_REDIS_HOST !== '127.0.0.1' || env.SERVER_ADDRESS !== '127.0.0.1')
    throw new Error('开发入口仅允许连接/绑定 127.0.0.1。');
  return env;
}

export async function assertFree(port) {
  await new Promise((ok, fail) => {
    const server = createServer();
    server.once('error', error => fail(new Error(error.code === 'EADDRINUSE'
      ? `端口 ${port} 已占用，请先停止占用服务；不会自动终止其他进程。`
      : `无法检查端口 ${port}：${error.code}`)));
    server.listen(Number(port), '127.0.0.1', () => server.close(ok));
  });
}

export function start(command, args, label, env, cwd = root) {
  const child = spawn(command, args, { cwd, env, detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
  for (const stream of [child.stdout, child.stderr]) {
    createInterface({ input: stream }).on('line', line => console.log(`[${label}] ${line}`));
  }
  const done = new Promise((ok, fail) => {
    child.once('error', () => fail(new Error(`无法启动 ${label}，请检查 ${command} 是否可用。`)));
    child.once('exit', (code, signal) => code === 0 ? ok() : fail(new Error(`${label} 已退出 (${signal ?? code})。`)));
  });
  done.catch(() => {});
  return { child, done };
}

export function signalGroup(child, signal) {
  if (!child.pid) return;
  try { process.kill(-child.pid, signal); }
  catch (error) { if (error.code !== 'ESRCH') throw error; }
}

async function main() {
  // shortcut: process groups require macOS/Linux; use WSL until native Windows is needed.
  if (process.platform === 'win32') throw new Error('请在 WSL 中运行开发脚本。');
  const action = process.argv[2];
  if (!['setup', 'all', 'infra', 'backend', 'frontend', 'down'].includes(action)) throw new Error('未知开发命令。');
  const children = new Set();
  let stopping = false;
  const stop = async (code) => {
    if (stopping) return;
    stopping = true;
    for (const { child } of children) signalGroup(child, 'SIGTERM');
    // Maven may exit before its JVM; keep the group ID until the final cleanup.
    await delay(3000);
    for (const { child } of children) signalGroup(child, 'SIGKILL');
    process.exit(code);
  };
  process.on('SIGINT', () => void stop(130));
  process.on('SIGTERM', () => void stop(143));
  process.on('SIGHUP', () => void stop(129));
  const launch = (command, args, label, env, cwd) => {
    if (stopping) throw new Error('正在停止。');
    const job = start(command, args, label, env, cwd);
    children.add(job);
    if (label === 'infra' || label === 'setup') job.done.finally(() => children.delete(job)).catch(() => {});
    return job;
  };
  try {
    if (action === 'setup') {
      const expected = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8')).engines;
      if (process.versions.node !== expected.node) throw new Error(`请使用 Node ${expected.node}（nvm install && nvm use）。`);
      if (execFileSync('npm', ['--version'], { encoding: 'utf8' }).trim() !== expected.npm)
        throw new Error(`请使用 npm ${expected.npm}。`);
      console.log(initialize(configPath, root) ? '已生成 .env.development（权限 0600）。' : '保留已有 .env.development。');
      await launch('npm', ['ci', '--prefix', 'frontend'], 'setup', process.env).done;
      console.log('准备完成。管理员初始密码见 .env.development；运行 npm run dev。');
      return;
    }
    const config = loadConfig(configPath);
    const env = { ...process.env, ...config };
    const compose = (...args) => launch('docker', ['compose', '--env-file', configPath,
      '-p', config.COMPOSE_PROJECT_NAME, '-f', 'compose.dev.yaml', ...args], 'infra', env);
    if (action === 'down') {
      await compose('down').done;
      console.log('开发依赖已停止，数据卷保留。');
      return;
    }
    if (['all', 'backend'].includes(action)) {
      const java = spawnSync(process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, 'bin/java') : 'java', ['-version'], { encoding: 'utf8' });
      if (java.status !== 0 || !/version "21[."]/.test(java.stderr + java.stdout))
        throw new Error('请安装 JDK 21 并设置 JAVA_HOME。');
      await assertFree(8080);
    }
    if (['all', 'frontend'].includes(action)) {
      if (!existsSync(resolve(root, 'frontend/node_modules/.bin/vite'))) throw new Error('前端依赖缺失，请运行 npm run setup。');
      await assertFree(5173);
    }
    if (['all', 'infra'].includes(action)) {
      await compose('up', '-d', '--wait', '--wait-timeout', '180').done;
      console.log('开发 MySQL / Redis 已就绪；数据独立于交付 Compose。');
    }
    const jobs = [];
    if (['all', 'backend'].includes(action)) {
      jobs.push(launch('./backend/mvnw', ['-f', 'backend/pom.xml', 'spring-boot:run'], 'backend', env));
    }
    if (['all', 'frontend'].includes(action)) {
      // Pass no backend credentials to the frontend process.
      jobs.push(launch('npm', ['--prefix', 'frontend', 'run', 'dev', '--', '--port', '5173', '--strictPort'], 'frontend', process.env));
    }
    if (jobs.length) {
      const exited = Promise.race(jobs.map(job => job.done.then(() => { throw new Error('开发进程已结束。'); })));
      if (['all', 'backend'].includes(action)) {
        await Promise.race([exited, (async () => {
          const deadline = Date.now() + 180_000;
          while (Date.now() < deadline) {
            try {
              const response = await fetch('http://127.0.0.1:8080/actuator/health/readiness', { signal: AbortSignal.timeout(1000) });
              if (response.ok && (await response.json()).status === 'UP') return;
            } catch {}
            await delay(1000);
          }
          throw new Error('后端 180 秒内未就绪，请查看上方 Maven/应用日志。首次下载依赖可完成后重试。');
        })()]);
        console.log('后端已就绪：http://127.0.0.1:8080');
      }
      if (action !== 'backend') {
        await Promise.race([exited, (async () => {
          const deadline = Date.now() + 30_000;
          while (Date.now() < deadline) {
            try {
              const response = await fetch('http://127.0.0.1:5173', { signal: AbortSignal.timeout(1000) });
              if (response.ok && (await response.text()).includes('<div id="app">')) return;
            } catch {}
            await delay(1000);
          }
          throw new Error('前端 30 秒内未就绪，请查看上方 Vite 日志。');
        })()]);
      }
      console.log(`${action === 'backend' ? '后端运行中' : '开发入口：http://127.0.0.1:5173'}；Ctrl+C 停止应用，数据库保留。`);
      await exited;
    }
  } catch (error) {
    if (!stopping) {
      console.error(error.message);
      await stop(1);
    }
  }
}

if (process.argv[1] && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
