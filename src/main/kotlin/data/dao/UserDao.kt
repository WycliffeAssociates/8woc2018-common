package data.dao

import data.model.Language
import data.model.User
import io.reactivex.Completable

interface UserDao : Dao<User> {
    fun addLanguage(user: User, language: Language, isSource: Boolean): Completable
    fun removeLanguage(user: User, language: Language, isSource: Boolean): Completable
    fun setLanguagePreference(user: User, language: Language, isSource: Boolean): Completable
}