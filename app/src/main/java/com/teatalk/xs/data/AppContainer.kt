package com.teatalk.xs.data

import android.content.Context
import androidx.room.Room
import com.teatalk.xs.data.db.TeaTalkDatabase

/** 应用级依赖容器（M0 手工 DI）。 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database: TeaTalkDatabase by lazy {
        Room.databaseBuilder(appContext, TeaTalkDatabase::class.java, "teatalk.db")
            .addMigrations(TeaTalkDatabase.MIGRATION_1_2)
            .build()
    }

    val characterRepository: CharacterRepository by lazy {
        CharacterRepository(appContext, database.characterDao())
    }

    val worldBookRepository: WorldBookRepository by lazy {
        WorldBookRepository(appContext, database.worldBookDao())
    }
}
