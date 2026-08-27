package dev.mahlernim.timelinevisualizer.journal

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        JournalEntity::class,
        ImportBatchEntity::class,
        DetailedObservationEntity::class,
        ObservationImportEntity::class,
        SemanticSnapshotEntity::class,
        SemanticSegmentEntity::class,
        RouteProjectionStateEntity::class,
        RouteProjectionSpanEntity::class,
        RouteProjectionChunkEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun journalDao(): JournalDao

    companion object {
        fun open(context: Context): JournalDatabase = Room.databaseBuilder(
            context.applicationContext,
            JournalDatabase::class.java,
            "travel-journal.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `route_projection_states` (`journalId` TEXT NOT NULL, `sourceRevision` INTEGER NOT NULL, `builtRevision` INTEGER NOT NULL, `algorithmVersion` INTEGER NOT NULL, `buildStatus` TEXT NOT NULL, `dirtyStartEpochMillis` INTEGER, `dirtyEndEpochMillis` INTEGER, `updatedAtEpochMillis` INTEGER, `spanCount` INTEGER NOT NULL, `pointCount` INTEGER NOT NULL, `detailedInputCount` INTEGER NOT NULL, `detailedUsableCount` INTEGER NOT NULL, `semanticUsableCount` INTEGER NOT NULL, PRIMARY KEY(`journalId`), FOREIGN KEY(`journalId`) REFERENCES `journals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `route_projection_spans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `journalId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `startEpochMillis` INTEGER NOT NULL, `endEpochMillis` INTEGER NOT NULL, `source` TEXT NOT NULL, `transitionReason` TEXT, `pointCount` INTEGER NOT NULL, FOREIGN KEY(`journalId`) REFERENCES `journals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_route_projection_spans_journalId_ordinal` ON `route_projection_spans` (`journalId`, `ordinal`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_route_projection_spans_journalId_startEpochMillis_endEpochMillis` ON `route_projection_spans` (`journalId`, `startEpochMillis`, `endEpochMillis`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `route_projection_chunks` (`spanId` INTEGER NOT NULL, `chunkOrdinal` INTEGER NOT NULL, `formatVersion` INTEGER NOT NULL, `pointCount` INTEGER NOT NULL, `pointData` BLOB NOT NULL, PRIMARY KEY(`spanId`, `chunkOrdinal`), FOREIGN KEY(`spanId`) REFERENCES `route_projection_spans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_route_projection_chunks_spanId` ON `route_projection_chunks` (`spanId`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `route_projection_states` ADD COLUMN `projectionStartEpochMillis` INTEGER")
                db.execSQL("ALTER TABLE `route_projection_states` ADD COLUMN `projectionEndExclusiveEpochMillis` INTEGER")
                // Every v2 projection was produced only for the lifetime sentinel range.
                db.execSQL(
                    """
                    UPDATE `route_projection_states`
                    SET `projectionStartEpochMillis` = ${Long.MIN_VALUE},
                        `projectionEndExclusiveEpochMillis` = ${Long.MAX_VALUE}
                    WHERE `builtRevision` > 0 AND `spanCount` > 0
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing chunks remain readable. Their null bounds make the range reader use
                // the conservative fallback until that derived projection is rebuilt.
                db.execSQL("ALTER TABLE `route_projection_chunks` ADD COLUMN `startEpochMillis` INTEGER")
                db.execSQL("ALTER TABLE `route_projection_chunks` ADD COLUMN `endExclusiveEpochMillis` INTEGER")
            }
        }
    }
}
