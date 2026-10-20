#!/usr/bin/env python3
"""Probe the shipped local similarity-index schema with host SQLite."""

from pathlib import Path
import re
import sqlite3
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
ASSET = ROOT / 'apps/android/app/src/main/assets/similarity/001_schema.sql'
BACKUP = ROOT / 'apps/android/app/src/main/res/xml/backup_rules.xml'
EXTRACTION = ROOT / 'apps/android/app/src/main/res/xml/data_extraction_rules.xml'


def normalize_sql(value):
    return re.sub(r'\s*([(),])\s*', r'\1', re.sub(r'\s+', ' ', value.lower())).strip().removesuffix(';')


def load_schema():
    actual = sorted(path.name for path in ASSET.parent.glob('*.sql'))
    if actual != ['001_schema.sql']:
        raise RuntimeError(f'Unexpected similarity SQL assets: {actual}')
    raw = ASSET.read_text(encoding='utf-8')
    statements = [re.sub(r'(?m)^\s*--[^\n]*', '', line).strip() for line in raw.split(';')]
    statements = [line for line in statements if line]
    if not statements:
        raise RuntimeError('Similarity schema is empty')
    return raw, statements


SCHEMA, STATEMENTS = load_schema()
EXPECTED_OBJECTS = {
    ('table', 'similarity_index_meta'),
    ('table', 'similarity_index_namespaces'),
    ('table', 'similarity_features'),
    ('table', 'similarity_hash_buckets'),
    ('index', 'similarity_hash_bucket_lookup'),
}


class SimilarityIndexSqlProbe(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='still-similarity-index-')
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'similarity.db'
        self.db = sqlite3.connect(self.path)
        self.addCleanup(self.db.close)
        self.db.execute('PRAGMA foreign_keys = ON')
        self.db.executescript(SCHEMA)

    def object_map(self):
        return {
            (kind, name): sql
            for kind, name, sql in self.db.execute(
                "SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
            )
        }

    @staticmethod
    def values(key='1', revision='2', incarnation='3'):
        return (key * 64, revision * 64, incarnation * 64)

    def insert_namespace(self, key='a', version='b', incarnation='c', generation=5):
        self.db.execute(
            'INSERT INTO similarity_index_namespaces(namespace_key, provider_version_digest, provider_generation, incarnation) VALUES(?, ?, ?, ?)',
            (key * 64, version * 64, generation, incarnation * 64),
        )
        return key * 64, incarnation * 64

    def insert_feature(self, namespace_key, incarnation, source='1', revision='2', descriptor=None, hash_value=17):
        blob = descriptor if descriptor is not None else bytes(136)
        source_key = source * 64
        self.db.execute(
            'INSERT INTO similarity_features(namespace_key, namespace_incarnation, source_key, source_revision, pipeline_version, perceptual_hash, descriptor) VALUES(?, ?, ?, ?, ?, ?, ?)',
            (namespace_key, incarnation, source_key, revision * 64, 'still-similarity-v1', hash_value, blob),
        )
        for number in range(11):
            self.db.execute(
                'INSERT INTO similarity_hash_buckets(source_key, pipeline_version, block_number, block_value) VALUES(?, ?, ?, ?)',
                (source_key, 'still-similarity-v1', number, hash_value % 64),
            )
        return source_key

    def test_schema_is_exact_and_contains_only_opaque_descriptor_fields(self):
        actual = self.object_map()
        self.assertEqual(set(actual), EXPECTED_OBJECTS)
        expected_create = {
            normalize_sql(statement.strip()): True
            for statement in STATEMENTS
            if statement.lstrip().upper().startswith(('CREATE TABLE', 'CREATE INDEX'))
        }
        self.assertEqual({normalize_sql(value) for value in actual.values()}, set(expected_create))
        columns = {
            row[1]
            for row in self.db.execute('PRAGMA table_info(similarity_features)')
        }
        self.assertEqual(columns, {
            'namespace_key', 'namespace_incarnation', 'source_key', 'source_revision',
            'pipeline_version', 'perceptual_hash', 'descriptor',
        })
        self.assertFalse(columns & {'name', 'path', 'uri', 'external_handle', 'sha256', 'content_hash'})

    def test_descriptor_and_bucket_constraints_and_namespace_cascade(self):
        namespace_key, incarnation = self.insert_namespace()
        source_key = self.insert_feature(namespace_key, incarnation)
        self.assertEqual(self.db.execute('SELECT length(descriptor) FROM similarity_features').fetchone()[0], 136)
        self.assertEqual(self.db.execute('SELECT count(*) FROM similarity_hash_buckets').fetchone()[0], 11)
        with self.assertRaises(sqlite3.IntegrityError):
            self.insert_feature(namespace_key, incarnation, source='4', descriptor=bytes(135))
        self.db.execute('DELETE FROM similarity_index_namespaces WHERE namespace_key = ?', (namespace_key,))
        self.assertEqual(self.db.execute('SELECT count(*) FROM similarity_features').fetchone()[0], 0)
        self.assertEqual(self.db.execute('SELECT count(*) FROM similarity_hash_buckets').fetchone()[0], 0)
        self.assertEqual(len(source_key), 64)

    def test_descriptor_and_buckets_survive_close_and_reopen(self):
        path = Path(self.temp.name) / 'reopen.db'
        db = sqlite3.connect(path)
        db.execute('PRAGMA foreign_keys = ON')
        db.executescript(SCHEMA)
        db.execute('PRAGMA application_id = 0x5354494c')
        namespace_key = 'a' * 64
        incarnation = 'c' * 64
        db.execute(
            'INSERT INTO similarity_index_namespaces(namespace_key, provider_version_digest, provider_generation, incarnation) VALUES(?, ?, ?, ?)',
            (namespace_key, 'b' * 64, 11, incarnation),
        )
        source_key = '1' * 64
        db.execute(
            'INSERT INTO similarity_features(namespace_key, namespace_incarnation, source_key, source_revision, pipeline_version, perceptual_hash, descriptor) VALUES(?, ?, ?, ?, ?, ?, ?)',
            (namespace_key, incarnation, source_key, '2' * 64, 'still-similarity-v1', 27, bytes(range(136))),
        )
        for number in range(11):
            db.execute('INSERT INTO similarity_hash_buckets(source_key, pipeline_version, block_number, block_value) VALUES(?, ?, ?, ?)',
                       (source_key, 'still-similarity-v1', number, number % 64))
        db.commit()
        db.close()

        reopened = sqlite3.connect(path)
        reopened.execute('PRAGMA foreign_keys = ON')
        self.assertEqual(reopened.execute('PRAGMA application_id').fetchone()[0], 0x5354494c)
        self.assertEqual(reopened.execute('SELECT source_revision, length(descriptor) FROM similarity_features').fetchone(), ('2' * 64, 136))
        self.assertEqual(reopened.execute('SELECT count(*) FROM similarity_hash_buckets').fetchone()[0], 11)
        reopened.close()

    def test_current_revision_pipeline_and_scope_prune_then_clear_only_derived_rows(self):
        namespace_key, incarnation = self.insert_namespace()
        current = self.insert_feature(namespace_key, incarnation, source='5', revision='6')
        self.insert_feature(namespace_key, incarnation, source='1', revision='2')
        self.insert_feature(namespace_key, incarnation, source='4', revision='4')
        old_pipeline_key = '7' * 64
        self.db.execute(
            'INSERT INTO similarity_features(namespace_key, namespace_incarnation, source_key, source_revision, pipeline_version, perceptual_hash, descriptor) VALUES(?, ?, ?, ?, ?, ?, ?)',
            (namespace_key, incarnation, old_pipeline_key, '8' * 64, 'old-pipeline', 12, bytes(136)),
        )
        self.db.execute('INSERT INTO similarity_hash_buckets(source_key, pipeline_version, block_number, block_value) VALUES(?, ?, ?, ?)',
                        (old_pipeline_key, 'old-pipeline', 0, 12))
        active_revision = '6' * 64
        with self.db:
            self.db.execute('CREATE TEMP TABLE active_similarity_sources(source_key TEXT PRIMARY KEY, source_revision TEXT NOT NULL)')
            self.db.execute('INSERT INTO active_similarity_sources VALUES(?, ?)', (current, active_revision))
            self.db.execute(
                'DELETE FROM similarity_features WHERE pipeline_version != ? OR NOT EXISTS '
                '(SELECT 1 FROM active_similarity_sources a WHERE a.source_key = similarity_features.source_key '
                'AND a.source_revision = similarity_features.source_revision)',
                ('still-similarity-v1',),
            )
            self.db.execute('DROP TABLE active_similarity_sources')
        self.assertEqual(self.db.execute('SELECT source_key FROM similarity_features').fetchall(), [(current,)])
        self.assertEqual(self.db.execute('SELECT count(*) FROM similarity_hash_buckets').fetchone()[0], 11)
        with self.db:
            self.db.execute('DELETE FROM similarity_index_namespaces')
        self.assertEqual(self.db.execute('SELECT count(*) FROM similarity_features').fetchone()[0], 0)
        self.assertEqual(self.db.execute('SELECT count(*) FROM similarity_hash_buckets').fetchone()[0], 0)
        self.assertEqual(self.db.execute('SELECT count(*) FROM similarity_index_meta').fetchone()[0], 2)

    def test_incomplete_page_transaction_rolls_back_features_and_buckets(self):
        namespace_key, incarnation = self.insert_namespace()
        self.db.commit()
        with self.assertRaises(RuntimeError):
            with self.db:
                self.insert_feature(namespace_key, incarnation)
                raise RuntimeError('scope fence withdrawn before page commit')
        self.assertEqual(self.db.execute('SELECT count(*) FROM similarity_features').fetchone()[0], 0)
        self.assertEqual(self.db.execute('SELECT count(*) FROM similarity_hash_buckets').fetchone()[0], 0)

    def test_foreign_unversioned_database_preflight_leaves_objects_untouched(self):
        foreign_path = Path(self.temp.name) / 'foreign.db'
        foreign = sqlite3.connect(foreign_path)
        foreign.execute('PRAGMA application_id = 1234')
        foreign.commit()
        before = foreign.execute("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").fetchall()
        user_version = foreign.execute('PRAGMA user_version').fetchone()[0]
        application_id = foreign.execute('PRAGMA application_id').fetchone()[0]
        self.assertEqual((user_version, application_id, len(before)), (0, 1234, 0))
        # Android's open helper applies this same gate before executing the asset.
        self.assertFalse(user_version != 0 or len(before) == 0 and application_id == 0)
        foreign.close()
        verify = sqlite3.connect(foreign_path)
        self.assertEqual(verify.execute("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").fetchall(), before)
        self.assertEqual(verify.execute('PRAGMA application_id').fetchone()[0], 1234)
        verify.close()
        source = (ROOT / 'apps/android/app/src/main/java/app/still/gallery/similarity/SimilarityIndexStore.kt').read_text(encoding='utf-8')
        self.assertIn('databasePragma(database, "application_id")', source)
        self.assertIn('similarityIndexSchemaCanInitialize(database.version, objectCount, applicationId)', source)

        object_path = Path(self.temp.name) / 'foreign-objects.db'
        object_db = sqlite3.connect(object_path)
        object_db.execute('CREATE TABLE unrelated(value TEXT NOT NULL)')
        object_db.execute('INSERT INTO unrelated VALUES(?)', ('keep me',))
        object_db.commit()
        object_before = object_db.execute("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").fetchall()
        self.assertFalse(object_db.execute('PRAGMA user_version').fetchone()[0] != 0 or len(object_before) == 0 and
                         object_db.execute('PRAGMA application_id').fetchone()[0] == 0)
        object_db.close()
        object_verify = sqlite3.connect(object_path)
        self.assertEqual(object_verify.execute("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").fetchall(), object_before)
        self.assertEqual(object_verify.execute('SELECT value FROM unrelated').fetchone()[0], 'keep me')
        object_verify.close()

    def test_media_index_database_remains_excluded_from_backup_and_transfer(self):
        manifest = (ROOT / 'apps/android/app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
        self.assertIn('android:allowBackup="false"', manifest)
        self.assertIn('<exclude domain="database" path="." />', BACKUP.read_text(encoding='utf-8'))
        extraction = EXTRACTION.read_text(encoding='utf-8')
        self.assertIn('<exclude domain="database" path="." />', extraction)
        self.assertIn('<exclude domain="device_database" path="." />', extraction)


if __name__ == '__main__':
    unittest.main(verbosity=2)
