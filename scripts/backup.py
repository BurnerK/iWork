#!/usr/bin/env python3
"""Offline coordinated backup; restore only into an empty, separate Compose project."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile


def digest(path):
    with path.open('rb') as stream:
        value = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
        return value.hexdigest()


def archive_members(archive):
    members = archive.getmembers()
    for item in members:
        path = PurePosixPath(item.name)
        if path.is_absolute() or '..' in path.parts or not (item.isfile() or item.isdir()):
            raise ValueError('Archive contains an unsafe path or file type')
    return members


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['backup', 'restore', 'verify'])
    parser.add_argument('--compose', action='append', required=True)
    parser.add_argument('--env-file', required=True)
    parser.add_argument('--project', required=True)
    parser.add_argument('--database', help='Business schema when it differs from MYSQL_DATABASE')
    parser.add_argument('--files', type=Path, required=True)
    parser.add_argument('--backup', type=Path, required=True)
    parser.add_argument('--writers-stopped', action='store_true', help='Operator has stopped every writer, including local Java processes')
    args = parser.parse_args()
    if not args.writers_stopped:
        parser.error('Stop all application writers before backup, restore, or verification; pass --writers-stopped afterward')
    if not re.fullmatch('[a-z0-9][a-z0-9_-]{0,62}', args.project):
        parser.error('Invalid Compose project name')
    if args.database is not None and not re.fullmatch('[a-zA-Z0-9_]{1,64}', args.database):
        parser.error('Invalid MySQL database name')
    os.umask(0o077)
    files = args.files.absolute()
    if files != files.resolve() or not files.is_dir():
        raise ValueError('Files must be an existing directory without symlink ancestors')
    backup = args.backup.absolute()
    if backup != backup.resolve() or backup == files or files in backup.parents or backup in files.parents:
        raise ValueError('Backup and application files must be separate, non-symlink directories')
    command = ['docker', 'compose', '--env-file', str(Path(args.env_file).resolve())]
    for path in args.compose:
        command += ['-f', str(Path(path).resolve())]
    command += ['-p', args.project]

    def run(tail, *, output=subprocess.PIPE):
        result = subprocess.run(command + tail, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.PIPE)
        if result.returncode:
            raise RuntimeError('Compose operation failed; inspect the selected project locally (no private output printed)')
        return result.stdout

    config = json.loads(run(['config', '--format', 'json']))
    if config['name'] != args.project or 'mysql' not in config['services']:
        raise ValueError('Unexpected Compose project or missing mysql service')
    running = run(['ps', '--status', 'running', '--services']).decode().splitlines()
    if 'mysql' not in running or 'app' in running:
        raise ValueError('MySQL must be running and the app must be stopped')
    if 'app' in config['services']:
        mounts = config['services']['app'].get('volumes', [])
        if not any(m.get('target') == '/data/files' and m.get('type') == 'bind' and Path(m['source']).resolve() == files for m in mounts):
            raise ValueError('Files path does not match this Compose app mount')
    mysql_exec = ['exec', '-T']
    if args.database is not None:
        mysql_exec += ['-e', 'IWORK_BACKUP_DATABASE=' + args.database]
    mysql_exec += ['mysql', 'sh', '-c']
    mysql = mysql_exec + ['MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u "$MYSQL_USER" -D "${IWORK_BACKUP_DATABASE:-$MYSQL_DATABASE}" --batch --skip-column-names "$@"', 'mysql']

    def sql(query):
        return run(mysql + ['-e', query]).decode().strip()

    def inspect():
        tables = sql('SHOW TABLES').splitlines()
        if not {'users', 'tasks', 'model_calls', 'materials', 'document_versions', 'conversation_contexts'}.issubset(tables):
            raise ValueError('Required iWork business tables are missing')
        if any(not re.fullmatch('[a-zA-Z0-9_]+', table) for table in tables):
            raise ValueError('Unexpected table name')
        statements = '\n'.join(f"SELECT '{table}', COUNT(*) FROM `{table}`;" for table in tables)
        counts = {table: int(count) for table, count in
                  (line.split('\t') for line in sql(statements).splitlines())}
        references = sql("SELECT CONCAT('originals/',id),byte_size,sha256 FROM materials UNION ALL SELECT CONCAT('documents/',id,'.md'),byte_size,sha256 FROM document_versions").splitlines()
        for line in references:
            relative, size, expected = line.split('\t')
            path = files / relative
            if path.is_symlink() or not path.is_file() or path.stat().st_size != int(size) or digest(path) != expected:
                raise ValueError('A database file reference failed its size/checksum check')
        return counts, len(references)

    if args.action == 'backup':
        if backup.exists():
            raise ValueError('Backup destination must not exist; never overwrite a prior backup')
        counts, references = inspect()
        backup.mkdir(parents=True, mode=0o700)
        with (backup / 'database.sql').open('xb') as output:
            run(mysql_exec + ['MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --default-character-set=utf8mb4 -u "$MYSQL_USER" --single-transaction --no-tablespaces --set-gtid-purged=OFF --routines --triggers --events --hex-blob "${IWORK_BACKUP_DATABASE:-$MYSQL_DATABASE}"'], output=output)
        with tarfile.open(backup / 'files.tar.gz', 'w:gz') as archive:
            for path in sorted(files.rglob('*')):
                if path.is_symlink() or not (path.is_file() or path.is_dir()):
                    raise ValueError('Application files contain a symlink or special file')
                archive.add(path, arcname=path.relative_to(files), recursive=False)
        if inspect() != (counts, references):
            raise ValueError('Data changed during backup; keep writers stopped and retry to a new directory')
        # 清单最后写入，恢复时才有依据区分完整备份和中途失败留下的目录。
        manifest = {'format': 1, 'project': args.project, 'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'counts': counts, 'references': references,
                    'sha256': {name: digest(backup / name) for name in ['database.sql', 'files.tar.gz']}}
        (backup / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        print(json.dumps({'status': 'BACKUP_COMPLETE', 'tables': len(counts), 'fileReferences': references}))
        return

    manifest = json.loads((backup / 'manifest.json').read_text())
    if manifest.get('format') != 1:
        raise ValueError('Unsupported backup format')
    for name in ['database.sql', 'files.tar.gz']:
        if (backup / name).is_symlink() or digest(backup / name) != manifest['sha256'][name]:
            raise ValueError('Backup checksum mismatch')
    if args.action == 'restore':
        if args.project == manifest['project'] or sql('SHOW TABLES') or any(files.iterdir()):
            raise ValueError('Restore requires a separate project, empty database and empty files directory')
        with tarfile.open(backup / 'files.tar.gz', 'r:gz') as archive:
            members = archive_members(archive)
            for member in members:
                target = files / member.name
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True, mode=0o700)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    with archive.extractfile(member) as source, target.open('xb') as output:
                        shutil.copyfileobj(source, output)
        # Keep partial restores offline on error; never automatically erase or restart them.
        with (backup / 'database.sql').open('rb') as source:
            result = subprocess.run(command + mysql, stdin=source, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            if result.returncode:
                raise RuntimeError('Database restore failed; target remains offline and partial')
    counts, references = inspect()
    if counts != manifest['counts'] or references != manifest['references']:
        raise ValueError('Restored table counts or file references differ from backup')
    print(json.dumps({'status': 'VERIFIED', 'tables': len(counts), 'fileReferences': references}))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, tarfile.TarError) as failure:
        raise SystemExit(str(failure))
