package com.sunshine.mesh.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.sunshine.mesh.data.local.dao.MessageDao
import com.sunshine.mesh.data.local.dao.PeerDao
import com.sunshine.mesh.data.local.dao.SeenIdDao
import com.sunshine.mesh.data.local.entity.MessageEntity
import com.sunshine.mesh.data.local.entity.PeerEntity
import com.sunshine.mesh.data.local.entity.SeenIdEntity

@Database(
    entities = [
        PeerEntity::class,
        MessageEntity::class,
        SeenIdEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SunshineDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao

    abstract fun messageDao(): MessageDao

    abstract fun seenIdDao(): SeenIdDao

    companion object {
        private const val NAME = "sunshine.db"

        /**
         * Every schema version gets its migration here. A destructive fallback
         * would erase local message history, which this app must never do.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun create(context: Context): SunshineDatabase =
            Room.databaseBuilder(context, SunshineDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
