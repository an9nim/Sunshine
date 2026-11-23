package com.sunshine.mesh.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real schema migration on a device. The exported 1.json/2.json files
 * are packaged as androidTest assets, so runMigrationsAndValidate() also checks
 * that MIGRATION_1_2 produces exactly the schema Room expects for version 2.
 */
@RunWith(AndroidJUnit4::class)
class SunshineDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SunshineDatabase::class.java,
    )

    @Test
    fun migration1To2KeepsDataAndResetsTrust() {
        createVersion1Database()

        val database = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            MIGRATION_1_2,
        )
        try {
            assertPeerMigrated(database)
            assertSeenIdsMigrated(database)
            assertMessagesUntouched(database)
            assertCompositeSeenIdKey(database)
        } finally {
            database.close()
        }
    }

    @Test
    fun migration2To3AddsPendingPacketsAndKeepsData() {
        val version2 = helper.createDatabase(DATABASE_NAME, 2)
        try {
            version2.execSQL(
                "INSERT INTO seen_ids (msg_id, part_index, seen_at) VALUES (?, ?, ?)",
                arrayOf<Any>(FIRST_MSG_ID, 0, FIRST_SEEN_AT),
            )
        } finally {
            version2.close()
        }

        val database = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            3,
            true,
            MIGRATION_2_3,
        )
        try {
            database.query(
                "SELECT COUNT(*) FROM seen_ids WHERE msg_id = ?",
                arrayOf(FIRST_MSG_ID),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }

            database.execSQL(
                "INSERT INTO pending_packets " +
                    "(sender_id, payload, created_at, expires_at, next_retry_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(PEER_ID, byteArrayOf(1, 2, 3), 1_000L, 5_000L, 2_000L),
            )
            database.query(
                "SELECT sender_id, payload, expires_at FROM pending_packets",
                emptyArray(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(PEER_ID, cursor.getString(cursor.getColumnIndexOrThrow("sender_id")))
                assertArrayEquals(
                    byteArrayOf(1, 2, 3),
                    cursor.getBlob(cursor.getColumnIndexOrThrow("payload")),
                )
                assertEquals(5_000L, cursor.getLong(cursor.getColumnIndexOrThrow("expires_at")))
            }

            database.execSQL(
                "DELETE FROM pending_packets WHERE expires_at <= ?",
                arrayOf<Any>(6_000L),
            )
            database.query("SELECT COUNT(*) FROM pending_packets", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            database.close()
        }
    }

    private fun createVersion1Database() {
        val database = helper.createDatabase(DATABASE_NAME, 1)
        try {
            database.execSQL(
                "INSERT INTO peers " +
                    "(peer_id, public_key, nickname, first_seen_at, last_seen_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(PEER_ID, PUBLIC_KEY, NICKNAME, FIRST_SEEN_AT, LAST_SEEN_AT),
            )
            database.execSQL(
                "INSERT INTO seen_ids (msg_id, seen_at) VALUES (?, ?)",
                arrayOf<Any>(FIRST_MSG_ID, FIRST_SEEN_AT),
            )
            database.execSQL(
                "INSERT INTO seen_ids (msg_id, seen_at) VALUES (?, ?)",
                arrayOf<Any>(SECOND_MSG_ID, LAST_SEEN_AT),
            )
            database.execSQL(
                "INSERT INTO messages " +
                    "(message_id, msg_id, peer_id, direction, body, created_at, status, packet) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
                arrayOf<Any>(
                    "local-1",
                    FIRST_MSG_ID,
                    PEER_ID,
                    "OUTGOING",
                    byteArrayOf(1, 2, 3),
                    FIRST_SEEN_AT,
                    "SENT",
                ),
            )
        } finally {
            database.close()
        }
    }

    private fun assertPeerMigrated(database: SupportSQLiteDatabase) {
        database.query(
            "SELECT public_key, last_seen_public_key, nickname, first_seen_at, " +
                "last_seen_at, trust_state, verified_at FROM peers WHERE peer_id = ?",
            arrayOf(PEER_ID),
        ).use { cursor ->
            assertTrue("migrated peer is missing", cursor.moveToFirst())
            assertArrayEquals(PUBLIC_KEY, cursor.getBlob(cursor.getColumnIndexOrThrow("public_key")))
            assertArrayEquals(
                PUBLIC_KEY,
                cursor.getBlob(cursor.getColumnIndexOrThrow("last_seen_public_key")),
            )
            assertEquals(NICKNAME, cursor.getString(cursor.getColumnIndexOrThrow("nickname")))
            assertEquals(
                FIRST_SEEN_AT,
                cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_at")),
            )
            assertEquals(
                LAST_SEEN_AT,
                cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at")),
            )
            assertEquals(
                "UNKNOWN",
                cursor.getString(cursor.getColumnIndexOrThrow("trust_state")),
            )
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("verified_at")))
        }
    }

    private fun assertSeenIdsMigrated(database: SupportSQLiteDatabase) {
        database.query(
            "SELECT msg_id, part_index, seen_at FROM seen_ids ORDER BY msg_id",
            emptyArray(),
        ).use { cursor ->
            assertEquals(2, cursor.count)

            assertTrue(cursor.moveToFirst())
            assertEquals(FIRST_MSG_ID, cursor.getString(cursor.getColumnIndexOrThrow("msg_id")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("part_index")))
            assertEquals(
                FIRST_SEEN_AT,
                cursor.getLong(cursor.getColumnIndexOrThrow("seen_at")),
            )

            assertTrue(cursor.moveToNext())
            assertEquals(SECOND_MSG_ID, cursor.getString(cursor.getColumnIndexOrThrow("msg_id")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("part_index")))
            assertEquals(
                LAST_SEEN_AT,
                cursor.getLong(cursor.getColumnIndexOrThrow("seen_at")),
            )
        }
    }

    private fun assertMessagesUntouched(database: SupportSQLiteDatabase) {
        database.query(
            "SELECT msg_id, status FROM messages WHERE message_id = ?",
            arrayOf("local-1"),
        ).use { cursor ->
            assertTrue("migrated message is missing", cursor.moveToFirst())
            assertEquals(FIRST_MSG_ID, cursor.getString(cursor.getColumnIndexOrThrow("msg_id")))
            assertEquals("SENT", cursor.getString(cursor.getColumnIndexOrThrow("status")))
        }
    }

    /**
     * A second part of an already known message must be insertable. This is the
     * behaviour the fragment deduplication relies on after the migration.
     */
    private fun assertCompositeSeenIdKey(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO seen_ids (msg_id, part_index, seen_at) VALUES (?, ?, ?)",
            arrayOf<Any>(FIRST_MSG_ID, 1, LAST_SEEN_AT),
        )
        database.query(
            "SELECT COUNT(*) FROM seen_ids WHERE msg_id = ?",
            arrayOf(FIRST_MSG_ID),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
    }

    private companion object {
        const val DATABASE_NAME = "sunshine-migration-test.db"
        const val PEER_ID = "0123456789abcdef"
        const val NICKNAME = "Алиса"
        const val FIRST_MSG_ID = "msg-1"
        const val SECOND_MSG_ID = "msg-2"
        const val FIRST_SEEN_AT = 1_000L
        const val LAST_SEEN_AT = 2_000L
        val PUBLIC_KEY = ByteArray(32) { (it + 1).toByte() }
    }
}
