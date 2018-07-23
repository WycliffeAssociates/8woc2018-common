package data

data class Chunk(
        var id: Int,
        val start: Int,
        val end: Int,
        val sort: Int,
        val srcFile: String,
        val content: Content
)