package com.example.open_script.data

import org.json.JSONArray
import org.json.JSONObject

internal object JsonCodec {
    fun settings(value: PromptSettings): JSONObject = JSONObject()
        .put("language", value.language).put("speed", value.speed.toDouble()).put("font", value.font)
        .put("size", value.size.toDouble()).put("auto_size", value.autoSize)
        .put("opacity", value.opacity.toDouble()).put("color", value.color)
        .put("show_prompt", value.showPrompt).put("mirror", value.mirror)

    fun settings(value: JSONObject): PromptSettings {
        val defaults = PromptSettings()
        return PromptSettings(
            language = value.optString("language", defaults.language).takeIf { it in setOf("pt-BR", "en", "es") } ?: defaults.language,
            speed = number(value, "speed", defaults.speed, 10f..100f),
            font = value.optString("font", defaults.font).takeIf { it in setOf("sans", "serif", "mono") } ?: defaults.font,
            size = number(value, "size", defaults.size, 20f..120f),
            autoSize = value.optBoolean("auto_size", defaults.autoSize),
            opacity = number(value, "opacity", defaults.opacity, 0f..70f),
            color = value.optString("color", defaults.color).takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: defaults.color,
            showPrompt = value.optBoolean("show_prompt", defaults.showPrompt),
            mirror = value.optBoolean("mirror", defaults.mirror),
        )
    }

    private fun number(value: JSONObject, key: String, fallback: Float, range: ClosedFloatingPointRange<Float>): Float {
        val result = value.optDouble(key, fallback.toDouble()).toFloat()
        return result.takeIf { it.isFinite() && it in range } ?: fallback
    }

    fun localScript(value: ScriptDocument): JSONObject = JSONObject()
        .put("id", value.id).put("title", value.title).put("content", value.content)
        .put("updated_at", value.updatedAt).put("remote_id", value.remoteId ?: JSONObject.NULL)
        .put("revision", value.revision ?: JSONObject.NULL)

    fun localScript(value: JSONObject): ScriptDocument = ScriptDocument(
        id = value.getString("id"), title = value.getString("title"), content = value.getString("content"),
        updatedAt = value.getLong("updated_at"),
        remoteId = if (value.isNull("remote_id")) null else value.getString("remote_id"),
        revision = if (value.isNull("revision")) null else value.getInt("revision"),
    )

    fun remoteScript(value: JSONObject, localId: String? = null): ScriptDocument {
        val remoteId = value.getString("id")
        val revision = value.getInt("revision")
        if (!ApiContract.isUuid(remoteId) || revision < 1) throw ApiException("O servidor retornou um roteiro inválido.", code = "invalid_response")
        return ScriptDocument(
            id = localId ?: remoteId, title = value.getString("title"), content = value.getString("content"),
            updatedAt = ApiContract.timestamp(value.getString("updated_at")), remoteId = remoteId, revision = revision,
        )
    }

    fun snapshot(value: LibrarySnapshot): JSONObject = JSONObject()
        .put("version", 1).put("draft", localScript(value.draft))
        .put("scripts", JSONArray().apply { value.scripts.forEach { put(localScript(it)) } })
        .put("settings", settings(value.settings))

    fun snapshot(value: JSONObject): LibrarySnapshot {
        require(value.getInt("version") == 1) { "Versão de biblioteca desconhecida." }
        val scripts = value.getJSONArray("scripts")
        return LibrarySnapshot(
            draft = localScript(value.getJSONObject("draft")),
            scripts = (0 until scripts.length()).map { localScript(scripts.getJSONObject(it)) },
            settings = settings(value.getJSONObject("settings")),
        )
    }

    fun take(value: RecordedTake): JSONObject = JSONObject()
        .put("id", value.id).put("title", value.title).put("path", value.path).put("duration_ms", value.durationMs)
        .put("created_at", value.createdAt).put("width", value.width).put("height", value.height)

    fun take(value: JSONObject): RecordedTake = RecordedTake(
        id = value.getString("id"), title = value.getString("title"), path = value.getString("path"),
        durationMs = value.getLong("duration_ms"), createdAt = value.getLong("created_at"),
        width = value.optInt("width", 1080), height = value.optInt("height", 1920),
    )

    fun user(value: JSONObject): UserProfile = UserProfile(
        value.getString("id"), value.getString("display_name"), value.getString("email"),
    )

    fun session(value: JSONObject): AuthSession = AuthSession(
        value.getString("access_token"), value.getString("expires_at"), user(value.getJSONObject("user")),
    )

    fun session(value: AuthSession): JSONObject = JSONObject()
        .put("access_token", value.accessToken).put("expires_at", value.expiresAt)
        .put("user", JSONObject().put("id", value.user.id).put("display_name", value.user.displayName).put("email", value.user.email))
}
