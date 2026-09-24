package com.example.myapplication.stage8

import android.content.Context
import com.example.myapplication.ShapeType
import com.example.myapplication.ToolMode
import com.google.gson.Gson

data class DrawingToolStyle(
    val colorArgb: Int = 0xffff0000.toInt(),
    val width: Float = .003f,
    val fontSize: Float = .02f,
    val bold: Boolean = false,
    val shape: ShapeType = ShapeType.RECTANGLE,
    val filled: Boolean = false
) {
    fun isValid() = width.isFinite() && width in .001f.. .04f &&
        fontSize.isFinite() && fontSize in .01f.. .08f
}

data class DrawingToolSettings(val styles: Map<String, DrawingToolStyle> = emptyMap()) {
    fun style(mode: ToolMode): DrawingToolStyle = styles[mode.name] ?: DrawingToolStyle(
        colorArgb = when (mode) {
            ToolMode.HIGHLIGHTER -> 0xffffff00.toInt()
            ToolMode.NOTE -> 0xff000000.toInt()
            ToolMode.MEASURE, ToolMode.POLYLINE -> 0xffe91e63.toInt()
            else -> 0xffff0000.toInt()
        },
        width = if (mode == ToolMode.HIGHLIGHTER) .01f else .003f
    )
    fun withStyle(mode: ToolMode, style: DrawingToolStyle): DrawingToolSettings {
        require(style.isValid())
        return copy(styles = styles + (mode.name to style))
    }
}

/** Project identity, never display name, owns durable tool defaults. Call on IO. */
class DrawingToolSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("project-tool-settings-v1", Context.MODE_PRIVATE)
    private val gson = Gson()
    fun scopeForDocument(documentId: String): String = preferences.getString("document-project:$documentId", null)
        ?.let { "project:$it" } ?: "document:$documentId"
    fun bindDocument(documentId: String, projectId: String) {
        require(documentId.isNotBlank() && projectId.isNotBlank())
        check(preferences.edit().putString("document-project:$documentId", projectId).commit())
    }
    fun read(scope: String): DrawingToolSettings {
        val encoded = preferences.getString(scope, null) ?: return DrawingToolSettings()
        require(encoded.length <= 16_384)
        val value = requireNotNull(gson.fromJson(encoded, DrawingToolSettings::class.java))
        require(value.styles.size <= ToolMode.entries.size && value.styles.all { (name, style) ->
            ToolMode.entries.any { it.name == name } && style.isValid()
        })
        return value
    }
    fun write(scope: String, settings: DrawingToolSettings) {
        require(scope.isNotBlank() && scope.length <= 8192)
        require(settings.styles.size <= ToolMode.entries.size && settings.styles.values.all { it.isValid() })
        check(preferences.edit().putString(scope, gson.toJson(settings)).commit()) { "Tool settings could not be saved" }
    }
}
