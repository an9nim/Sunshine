package com.sunshine.mesh.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Stage 1 -> Stage 2. Existing peers become UNKNOWN on purpose: silently
 * inheriting first-contact trust from the pre-verification build would preserve
 * a possible MITM. The public key itself stays as the historical anchor.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        migratePeers(db)
        migrateSeenIds(db)
    }

    private fun migratePeers(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `peers_new` (
                `peer_id` TEXT NOT NULL,
                `public_key` BLOB NOT NULL,
                `last_seen_public_key` BLOB NOT NULL,
                `nickname` TEXT NOT NULL,
                `first_seen_at` INTEGER NOT NULL,
                `last_seen_at` INTEGER NOT NULL,
                `trust_state` TEXT NOT NULL,
                `verified_at` INTEGER,
                PRIMARY KEY(`peer_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `peers_new` (
                `peer_id`, `public_key`, `last_seen_public_key`, `nickname`,
                `first_seen_at`, `last_seen_at`, `trust_state`, `verified_at`
            )
            SELECT
                `peer_id`, `public_key`, `public_key`, `nickname`,
                `first_seen_at`, `last_seen_at`, 'UNKNOWN', NULL
            FROM `peers`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `peers`")
        db.execSQL("ALTER TABLE `peers_new` RENAME TO `peers`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_peers_public_key` " +
                "ON `peers` (`public_key`)",
        )
    }

    private fun migrateSeenIds(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `seen_ids_new` (
                `msg_id` TEXT NOT NULL,
                `part_index` INTEGER NOT NULL,
                `seen_at` INTEGER NOT NULL,
                PRIMARY KEY(`msg_id`, `part_index`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `seen_ids_new` (`msg_id`, `part_index`, `seen_at`)
            SELECT `msg_id`, 0, `seen_at` FROM `seen_ids`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `seen_ids`")
        db.execSQL("ALTER TABLE `seen_ids_new` RENAME TO `seen_ids`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_seen_ids_seen_at` " +
                "ON `seen_ids` (`seen_at`)",
        )
    }
}

/**
 * Stage 3 -> Stage 4. Adds the store-and-forward table for packets addressed
 * to us that we cannot open yet. Existing tables are untouched.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pending_packets` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sender_id` TEXT NOT NULL,
                `payload` BLOB NOT NULL,
                `created_at` INTEGER NOT NULL,
                `expires_at` INTEGER NOT NULL,
                `next_retry_at` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_packets_expires_at` " +
                "ON `pending_packets` (`expires_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_packets_next_retry_at` " +
                "ON `pending_packets` (`next_retry_at`)",
        )
    }
}
