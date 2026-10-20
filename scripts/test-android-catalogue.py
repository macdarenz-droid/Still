#!/usr/bin/env python3
"""Probe the shipped catalogue SQL with real temporary-file host SQLite.

Only schema and DML come from the Android assets. Fixture decisions below model
the SQL caller contract; Kotlin policy, Android transactions/lifecycle and API 26
SQLite compatibility still require their own tests and device verification.
"""

from pathlib import Path
import re
import sqlite3
import tempfile
import unittest
import uuid


ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'apps/android/app/src/main/assets/catalogue'
EXPECTED_ASSETS = {
    '001_schema', 'insert_incarnation', 'insert_namespace', 'insert_source',
    'read_namespace', 'read_source', 'update_namespace', 'update_source',
}


def sql_code(statement):
    return re.sub(r'/\*.*?\*/|--[^\n]*', '', statement, flags=re.S)


def load_sql(asset_directory=ASSETS):
    actual = {path.stem for path in asset_directory.glob('*.sql')}
    if actual != EXPECTED_ASSETS:
        raise RuntimeError(f'Catalogue SQL assets differ: expected {sorted(EXPECTED_ASSETS)}, got {sorted(actual)}')
    statements = {}
    for name in sorted(EXPECTED_ASSETS):
        statement = (asset_directory / f'{name}.sql').read_text(encoding='utf-8')
        if not sql_code(statement).strip():
            raise RuntimeError(f'Required catalogue SQL asset is empty: {name}.sql')
        statements[name] = statement
    return statements


SQL = load_sql()
SOURCE_COLUMNS = (
    'source_id', 'incarnation_id', 'is_video', 'external_handle', 'birth_marker',
    'observation_id', 'metadata_revision', 'metadata_fingerprint', 'source_uri',
    'display_name', 'mime_type', 'captured_at', 'width', 'height', 'byte_length',
    'duration_ms', 'album_id', 'album_name', 'orientation_degrees', 'modified_at',
    'date_added_seconds', 'generation_added', 'generation_modified',
)
UPDATE_COLUMNS = (
    'observation_id', 'metadata_revision', 'metadata_fingerprint', 'source_uri',
    'display_name', 'mime_type', 'captured_at', 'width', 'height', 'byte_length',
    'duration_ms', 'album_id', 'album_name', 'orientation_degrees', 'modified_at',
    'date_added_seconds', 'generation_added', 'generation_modified',
)


def opaque_id():
    return str(uuid.uuid4())


class CatalogueSqlProbe(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='still-catalogue-')
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'catalogue.db'
        self.db = self.connect()
        self.addCleanup(lambda: self.db.close())
        self.db.executescript(SQL['001_schema'])

    def connect(self):
        connection = sqlite3.connect(self.path)
        connection.row_factory = sqlite3.Row
        connection.execute('PRAGMA foreign_keys = ON')
        self.assertEqual(connection.execute('PRAGMA foreign_keys').fetchone()[0], 1)
        return connection

    def reopen(self):
        self.db.commit()
        self.db.close()
        self.db = self.connect()

    def incarnation(self, volume='external_primary', version='A', authority='media'):
        identity = opaque_id()
        self.db.execute(SQL['insert_incarnation'], (identity, authority, volume, version))
        return identity

    def namespace(self, incarnation, version='A', generation=10,
                  volume='external_primary', authority='media'):
        self.db.execute(SQL['insert_namespace'],
                        (authority, volume, incarnation, version, generation))

    def read_namespace(self, volume='external_primary', authority='media'):
        return self.db.execute(SQL['read_namespace'], (authority, volume)).fetchone()

    def point_namespace(self, incarnation, version, generation,
                        volume='external_primary', authority='media'):
        return self.db.execute(SQL['update_namespace'],
                               (incarnation, version, generation, authority, volume)).rowcount

    def source(self, incarnation, **overrides):
        row = dict(zip(SOURCE_COLUMNS, (
            opaque_id(), incarnation, 0, '42', 'generation:3', opaque_id(), 1,
            'fixture-metadata-v1', 'content://media/external_primary/images/media/42',
            'Photo.jpg', 'image/jpeg', 123_000, 1920, 1080, 2048, 0,
            'camera', 'Camera', 0, 125_000, 100, 3, 9,
        )))
        row.update(overrides)
        return row

    def insert_source(self, row):
        return self.db.execute(SQL['insert_source'],
                               tuple(row[column] for column in SOURCE_COLUMNS)).rowcount

    def read_source(self, row):
        return self.db.execute(SQL['read_source'], (
            row['incarnation_id'], row['is_video'], row['external_handle'], row['birth_marker'],
        )).fetchone()

    def update_source(self, row, expected_revision):
        return self.db.execute(SQL['update_source'],
                               tuple(row[column] for column in UPDATE_COLUMNS) +
                               (row['source_id'], expected_revision)).rowcount

    def all_sources(self):
        return [dict(row) for row in self.db.execute(
            'SELECT * FROM catalogue_sources ORDER BY source_id')]

    def test_sql_assets_use_supported_non_destructive_statements(self):
        self.assertEqual(set(SQL), EXPECTED_ASSETS)
        for name, statement in SQL.items():
            with self.subTest(asset=name):
                # Remove comments so explanatory prose cannot trigger this guard.
                code = sql_code(statement)
                self.assertNotRegex(code, r'(?i)\b(?:UPSERT|RETURNING|STRICT|REPLACE|DROP|DELETE)\b')
                self.assertNotRegex(code, r'(?i)\bON\s+CONFLICT\b')

    def test_required_asset_loader_rejects_every_missing_asset(self):
        with tempfile.TemporaryDirectory(prefix='still-missing-sql-') as directory:
            assets = Path(directory)
            for name, statement in SQL.items():
                (assets / f'{name}.sql').write_text(statement, encoding='utf-8')
            for name, statement in SQL.items():
                with self.subTest(asset=name):
                    path = assets / f'{name}.sql'
                    path.unlink()
                    with self.assertRaisesRegex(RuntimeError, 'SQL assets differ'):
                        load_sql(assets)
                    path.write_text(statement, encoding='utf-8')

    def test_required_asset_loader_rejects_every_empty_asset(self):
        with tempfile.TemporaryDirectory(prefix='still-empty-sql-') as directory:
            assets = Path(directory)
            for name, statement in SQL.items():
                (assets / f'{name}.sql').write_text(statement, encoding='utf-8')
            for name, statement in SQL.items():
                with self.subTest(asset=name):
                    path = assets / f'{name}.sql'
                    path.write_text('-- comment is not executable SQL\n', encoding='utf-8')
                    with self.assertRaisesRegex(RuntimeError, 'SQL asset is empty'):
                        load_sql(assets)
                    path.write_text(statement, encoding='utf-8')

    def test_opaque_ids_and_namespace_survive_close_and_reopen(self):
        incarnation = self.incarnation()
        self.namespace(incarnation)
        source = self.source(incarnation)
        self.insert_source(source)
        self.reopen()
        self.assertEqual(tuple(self.read_namespace()), (incarnation, 'A', 10))
        self.assertEqual(tuple(self.read_source(source)), (
            source['source_id'], source['observation_id'], 1, 'fixture-metadata-v1',
        ))
        self.assertEqual(self.all_sources(), [source])
        for identity in (incarnation, source['source_id'], source['observation_id']):
            self.assertEqual(str(uuid.UUID(identity)), identity)

    def test_unchanged_fingerprint_needs_no_update_or_revision_churn(self):
        source = self.source(self.incarnation())
        self.insert_source(source)
        self.db.commit()
        changes = self.db.total_changes
        # This is the caller's unchanged-row branch: read, compare, skip DML.
        for _ in range(3):
            prior = self.read_source(source)
            self.assertEqual(prior['metadata_fingerprint'], source['metadata_fingerprint'])
            self.assertEqual(prior['metadata_revision'], 1)
            self.assertEqual(prior['observation_id'], source['observation_id'])
        self.assertEqual(self.db.total_changes, changes)
        self.reopen()
        self.assertEqual(self.all_sources(), [source])

    def test_changed_metadata_compare_and_update_keeps_source_and_changes_observation(self):
        source = self.source(self.incarnation())
        self.insert_source(source)
        prior = self.read_source(source)
        updated = dict(source, display_name='Renamed.jpg', metadata_fingerprint='fixture-metadata-v2',
                       metadata_revision=2, observation_id=opaque_id(), width=2048)
        self.assertEqual(self.update_source(updated, prior['metadata_revision']), 1)
        self.reopen()
        self.assertEqual(self.all_sources(), [updated])
        current = self.read_source(updated)
        self.assertEqual(current['source_id'], source['source_id'])
        self.assertNotEqual(current['observation_id'], source['observation_id'])
        self.assertEqual(current['metadata_revision'], 2)

    def test_stale_expected_revision_cannot_overwrite_newer_metadata(self):
        source = self.source(self.incarnation())
        self.insert_source(source)
        current = dict(source, metadata_revision=2, observation_id=opaque_id(),
                       metadata_fingerprint='newer', display_name='Newer.jpg')
        self.assertEqual(self.update_source(current, 1), 1)
        stale = dict(source, metadata_revision=2, observation_id=opaque_id(),
                     metadata_fingerprint='stale', display_name='Stale.jpg')
        self.assertEqual(self.update_source(stale, 1), 0)
        self.assertEqual(self.all_sources(), [current])

    def test_source_identity_partitions_volume_provider_kind_and_birth(self):
        primary = self.incarnation()
        removable = self.incarnation(volume='0123-4567')
        other_provider = self.incarnation(authority='other.media')
        rows = [
            self.source(primary),
            self.source(removable),
            self.source(other_provider),
            self.source(primary, is_video=1, mime_type='video/mp4'),
            self.source(primary, birth_marker='generation:100'),
            self.source(primary, external_handle='43'),
            self.source(primary, birth_marker='date-added:101', generation_added=None),
        ]
        for row in rows:
            self.insert_source(row)
        self.reopen()
        self.assertEqual(len(self.all_sources()), len(rows))
        for row in rows:
            self.assertEqual(self.read_source(row)['source_id'], row['source_id'])
        self.assertEqual(len({row['source_id'] for row in rows}), len(rows))
        self.assertEqual(len({row['observation_id'] for row in rows}), len(rows))

    def test_namespace_a_b_a_uses_new_incarnation_and_retains_all_history(self):
        first = self.incarnation(version='A')
        self.namespace(first)
        first_source = self.source(first)
        self.insert_source(first_source)
        second = self.incarnation(version='B')
        self.assertEqual(self.point_namespace(second, 'B', 1), 1)
        second_source = self.source(second)
        self.insert_source(second_source)
        third = self.incarnation(version='A')
        self.assertEqual(self.point_namespace(third, 'A', 1), 1)
        third_source = self.source(third)
        self.insert_source(third_source)
        self.reopen()
        self.assertEqual(tuple(self.read_namespace()), (third, 'A', 1))
        self.assertEqual(len({first, second, third}), 3)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM catalogue_incarnations').fetchone()[0], 3)
        self.assertEqual(len(self.all_sources()), 3)
        for row in (first_source, second_source, third_source):
            self.assertEqual(self.read_source(row)['source_id'], row['source_id'])

    def test_duplicate_source_identity_observation_and_namespace_are_rejected(self):
        incarnation = self.incarnation()
        self.namespace(incarnation)
        source = self.source(incarnation)
        self.insert_source(source)
        attempts = {
            'source_id': dict(source, external_handle='other', observation_id=opaque_id()),
            'identity_tuple': dict(source, source_id=opaque_id(), observation_id=opaque_id()),
            'observation_id': dict(source, source_id=opaque_id(), external_handle='other'),
        }
        for kind, duplicate in attempts.items():
            with self.subTest(constraint=kind), self.assertRaises(sqlite3.IntegrityError):
                self.insert_source(duplicate)
        with self.assertRaises(sqlite3.IntegrityError):
            self.namespace(incarnation)
        self.assertEqual(self.all_sources(), [source])

    def test_foreign_keys_reject_unknown_incarnations_on_insert_and_pointer_update(self):
        incarnation = self.incarnation()
        self.namespace(incarnation)
        missing = opaque_id()
        with self.assertRaises(sqlite3.IntegrityError):
            self.insert_source(self.source(missing))
        with self.assertRaises(sqlite3.IntegrityError):
            self.namespace(missing, volume='missing-volume')
        with self.assertRaises(sqlite3.IntegrityError):
            self.point_namespace(missing, 'B', 1)
        self.assertEqual(tuple(self.read_namespace()), (incarnation, 'A', 10))
        self.assertEqual(self.all_sources(), [])

    def test_nonpositive_revision_and_invalid_kind_or_orientation_are_rejected(self):
        incarnation = self.incarnation()
        for field, value in (
            ('metadata_revision', 0), ('metadata_revision', -1),
            ('metadata_revision', None), ('is_video', -1), ('is_video', 2),
            ('orientation_degrees', -90), ('orientation_degrees', 45),
        ):
            with self.subTest(field=field, value=value), self.assertRaises(sqlite3.IntegrityError):
                self.insert_source(self.source(incarnation, **{field: value}))
        source = self.source(incarnation)
        self.insert_source(source)
        invalid_update = dict(source, metadata_revision=0, observation_id=opaque_id())
        with self.assertRaises(sqlite3.IntegrityError):
            self.update_source(invalid_update, 1)
        self.assertEqual(self.all_sources(), [source])

    def test_negative_metadata_is_rejected_on_insert_and_update(self):
        incarnation = self.incarnation()
        source = self.source(incarnation)
        self.insert_source(source)
        for field in (
            'captured_at', 'width', 'height', 'byte_length', 'duration_ms', 'modified_at',
            'date_added_seconds', 'generation_added', 'generation_modified',
        ):
            with self.subTest(field=field):
                with self.assertRaises(sqlite3.IntegrityError):
                    self.insert_source(self.source(incarnation, external_handle=field, **{field: -1}))
                invalid_update = dict(source, metadata_revision=2, observation_id=opaque_id(), **{field: -1})
                with self.assertRaises(sqlite3.IntegrityError):
                    self.update_source(invalid_update, 1)
        self.namespace(incarnation)
        with self.assertRaises(sqlite3.IntegrityError):
            self.point_namespace(incarnation, 'A', -1)
        self.assertEqual(self.all_sources(), [source])
        self.assertEqual(tuple(self.read_namespace()), (incarnation, 'A', 10))

    def test_nullable_generations_allow_older_provider_observations(self):
        incarnation = self.incarnation(version=None)
        self.namespace(incarnation, version=None, generation=None)
        source = self.source(incarnation, birth_marker='date-added:100',
                             generation_added=None, generation_modified=None)
        self.insert_source(source)
        self.reopen()
        self.assertEqual(tuple(self.read_namespace()), (incarnation, None, None))
        self.assertEqual(self.all_sources(), [source])

    def test_transaction_fault_rolls_back_pointer_incarnation_and_source_writes(self):
        incarnation = self.incarnation()
        self.namespace(incarnation)
        source = self.source(incarnation)
        self.insert_source(source)
        self.db.commit()
        with self.assertRaisesRegex(RuntimeError, 'injected failure'):
            with self.db:
                replacement = self.incarnation(version='B')
                self.point_namespace(replacement, 'B', 1)
                self.insert_source(self.source(replacement))
                updated = dict(source, metadata_revision=2, observation_id=opaque_id(),
                               display_name='Uncommitted.jpg', metadata_fingerprint='uncommitted')
                self.update_source(updated, 1)
                raise RuntimeError('injected failure after multiple writes')
        self.reopen()
        self.assertEqual(tuple(self.read_namespace()), (incarnation, 'A', 10))
        self.assertEqual(self.all_sources(), [source])
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM catalogue_incarnations').fetchone()[0], 1)

    def test_constraint_fault_rolls_back_entire_snapshot_transaction(self):
        incarnation = self.incarnation()
        self.namespace(incarnation)
        source = self.source(incarnation)
        self.insert_source(source)
        self.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.point_namespace(incarnation, 'A', 20)
                self.insert_source(self.source(incarnation, external_handle='43'))
                self.insert_source(self.source(incarnation, external_handle='44', byte_length=-1))
        self.reopen()
        self.assertEqual(tuple(self.read_namespace()), (incarnation, 'A', 10))
        self.assertEqual(self.all_sources(), [source])

    def test_bound_names_are_literal_on_insert_and_update(self):
        incarnation = self.incarnation()
        malicious = "'); DROP TABLE catalogue_sources; -- 雪\n?"
        source = self.source(incarnation, display_name=malicious, album_name=malicious)
        self.insert_source(source)
        self.assertEqual(self.all_sources(), [source])
        updated = dict(source, display_name='"; UPDATE catalogue_sources SET metadata_revision=99; --',
                       observation_id=opaque_id(), metadata_revision=2, metadata_fingerprint='changed-name')
        self.assertEqual(self.update_source(updated, 1), 1)
        self.reopen()
        self.assertEqual(self.all_sources(), [updated])

    def test_partial_and_empty_snapshots_retain_missing_rows(self):
        incarnation = self.incarnation()
        self.namespace(incarnation)
        first = self.source(incarnation)
        missing = self.source(incarnation, external_handle='43')
        self.insert_source(first)
        self.insert_source(missing)
        self.db.commit()
        # A partial snapshot writes only the row it observed; absence is no DML.
        changed = dict(first, metadata_revision=2, observation_id=opaque_id(),
                       display_name='Observed.jpg', metadata_fingerprint='partial-snapshot')
        with self.db:
            self.point_namespace(incarnation, 'A', 11)
            self.assertEqual(self.update_source(changed, 1), 1)
        self.assertEqual(self.read_source(missing)['source_id'], missing['source_id'])
        # An authorized empty snapshot may advance its cursor, never infer deletion.
        with self.db:
            self.point_namespace(incarnation, 'A', 12)
        self.reopen()
        self.assertEqual(self.all_sources(), sorted([changed, missing], key=lambda row: row['source_id']))


if __name__ == '__main__':
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(CatalogueSqlProbe)
    if suite.countTestCases() == 0:
        raise SystemExit('Required catalogue SQL test suite is empty')
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() and not result.skipped else 1)
