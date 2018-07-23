package data.persistence

import data.dao.Dao
import data.model.*

interface AppDatabase {
    fun getUserDao(): Dao<User>
    fun getLanguageDao(): Dao<Language>
    fun getProjectDao(): Dao<Project>
    fun getAnthologyDao(): Dao<Anthology>
    fun getBookDao(): Dao<Book>
    fun getChapterDao(): Dao<Chapter>
    fun getChunkDao(): Dao<Chunk>
    fun getTakesDao(): Dao<Take>
}