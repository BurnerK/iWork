#!/usr/bin/env python3
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('backup', Path(__file__).with_name('backup.py'))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class ArchiveSafety(unittest.TestCase):
    def archive(self, name, kind=tarfile.REGTYPE):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode='w') as archive:
            item = tarfile.TarInfo(name)
            item.type = kind
            item.linkname = '/outside'
            archive.addfile(item)
        stream.seek(0)
        return tarfile.open(fileobj=stream, mode='r')

    def test_rejects_paths_and_links_before_any_extraction(self):
        for name, kind in [('/outside', tarfile.REGTYPE), ('../outside', tarfile.REGTYPE), ('documents/../../outside', tarfile.REGTYPE), ('documents/link', tarfile.SYMTYPE), ('documents/hardlink', tarfile.LNKTYPE), ('device', tarfile.CHRTYPE)]:
            with self.subTest(name=name), self.archive(name, kind) as archive:
                with self.assertRaises(ValueError):
                    backup.archive_members(archive)

    def test_accepts_ordinary_documents_and_detects_byte_changes(self):
        with self.archive('documents/owned.md') as archive:
            self.assertEqual(backup.archive_members(archive)[0].name, 'documents/owned.md')
        root = Path(tempfile.gettempdir()) / 'codex'
        root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as work:
            file = Path(work) / 'document.md'
            file.write_bytes(b'old')
            old = backup.digest(file)
            file.write_bytes(b'new')
            self.assertNotEqual(backup.digest(file), old)


if __name__ == '__main__':
    unittest.main()
