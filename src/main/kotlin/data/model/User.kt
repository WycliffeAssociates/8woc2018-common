package data.model

data class User(
        var id: Int = 0,
        val audioHash: String,
        val audioPath: String,
        val sourceLanguages: MutableList<Language>,
        val targetLanguages: MutableList<Language>,
        val userPreferences: UserPreferences
)



