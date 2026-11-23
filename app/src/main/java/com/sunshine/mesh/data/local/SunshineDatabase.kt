package com.sunshine.mesh.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.sunshine.mesh.data.local.dao.MessageDao
import com.sunshine.mesh.data.local.dao.PeerDao
import com.sunshine.mesh.data.local.dao.PendingPacketDao
import com.sunshine.mesh.data.local.dao.SeenIdDao
import com.sunshine.mesh.data.local.entity.MessageEntity
import com.sunshine.mesh.data.local.entity.PeerEntity
import com.sunshine.mesh.data.local.entity.PendingPacketEntity
import com.sunshine.mesh.data.local.entity.SeenIdEntity

@Database(
    entities = [
        PeerEntity::class,
        MessageEntity::class,
        SeenIdEntity::class,
        PendingPacketEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class SunshineDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao

    abstract fun messageDao(): MessageDao

    abstract fun seenIdDao(): SeenIdDao

    abstract fun pendingPacketDao(): PendingPacketDao

    companion object {
        private const val NAME = "sunshine.db"

        /**
         * Every schema version gets its migration here. A destructive fallback
         * would erase local message history, which this app must never do.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun create(context: Context): SunshineDatabase =
            Room.databaseBuilder(context, SunshineDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
