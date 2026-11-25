package com.pnprojects.tarangfx.di

import android.content.Context
import androidx.room.Room
import com.pnprojects.tarangfx.data.db.AppDatabase
import com.pnprojects.tarangfx.data.db.EqPresetDao
import com.pnprojects.tarangfx.data.db.OutputDao
import com.pnprojects.tarangfx.data.db.ProjectDraftDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "tarangfx.db").build()

    @Provides fun outputDao(db: AppDatabase): OutputDao = db.outputDao()
    @Provides fun eqPresetDao(db: AppDatabase): EqPresetDao = db.eqPresetDao()
    @Provides fun projectDraftDao(db: AppDatabase): ProjectDraftDao = db.projectDraftDao()
}
