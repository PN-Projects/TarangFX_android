package com.pnprojects.tarangfx.ui.navigation

/** Every tool on the Home grid and how it selects input. */
enum class Tool(
    val title: String,
    val editorRoute: String,
    val multiSelect: Boolean = false,
    val video: Boolean = false,
    val needsPicker: Boolean = true,
) {
    TRIM("Trim audio", "trim"),
    RINGTONE("Ringtone", "ringtone"),
    MERGE("Merge", "merge", multiSelect = true),
    MIX("Mix", "mix", multiSelect = true),
    SPLIT("Split", "split"),
    VIDEO("Video to audio", "video", multiSelect = true, video = true),
    CONVERT("Convert format", "convert", multiSelect = true),
    RECORD("Record", "record", needsPicker = false),
    DENOISE("AI Denoise", "denoise"),
    FX("FX & EQ", "fx"),
    TAGS("Tag Editor", "tags"),
}

object Routes {
    const val EDIT = "edit"
    const val OUTPUTS = "outputs"
    const val SETTINGS = "settings"
    const val PICKER = "picker/{tool}"
    fun picker(tool: Tool) = "picker/${tool.name}"
    const val APPEND_PICKER = "append/{tool}"
    fun appendPicker(tool: Tool) = "append/${tool.name}"
}
