package com.pd2launcher.box64.input

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A stored on-screen layout as JSON (the main app's format: `{"version":1,"pieces":[…]}`, a piece
 * with its id, x, y and size, and hidden, opacity, action and label only when they differ from the
 * defaults), read and written with Android's org.json.
 */
object LayoutJson {
    const val VERSION = 1

    fun encode(layout: ControlsLayout): String {
        val pieces = JSONArray()
        for (piece in layout.pieces) {
            val o = JSONObject()
            o.put("id", piece.id)
            o.put("x", piece.x.toDouble())
            o.put("y", piece.y.toDouble())
            o.put("size", piece.size.toDouble())
            if (piece.hidden) o.put("hidden", true)
            if (piece.opacity != 1f) o.put("opacity", piece.opacity.toDouble())
            piece.action?.let { o.put("action", it) }
            piece.label?.let { o.put("label", it) }
            pieces.put(o)
        }
        return JSONObject().put("version", VERSION).put("pieces", pieces).toString()
    }

    /**
     * A stored layout over [default]: nothing stored or unreadable is the default; the
     * controller's pieces it lacks come from the default (in the default's place), pieces it does
     * not know and custom buttons without a binding are dropped.
     */
    fun decode(text: String?, default: ControlsLayout): ControlsLayout {
        if (text.isNullOrBlank()) return default
        val stored = try {
            val array = JSONObject(text).optJSONArray("pieces") ?: return default
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "")
                if (id.isEmpty()) return@mapNotNull null
                Piece(
                    id = id,
                    x = o.optDouble("x", Double.NaN).toFloat(),
                    y = o.optDouble("y", Double.NaN).toFloat(),
                    size = o.optDouble("size", 0.0).toFloat(),
                    hidden = o.optBoolean("hidden", false),
                    opacity = o.optDouble("opacity", 1.0).toFloat(),
                    action = if (o.has("action")) o.optString("action") else null,
                    label = if (o.has("label")) o.optString("label") else null,
                )
            }
        } catch (_: JSONException) {
            return default
        }
        val kept = stored.filter { piece ->
            piece.size > 0f && piece.x.isFinite() && piece.y.isFinite() &&
                if (piece.custom) piece.binding.let { it is PadAction.Key || it is PadAction.Mouse } else piece.kind != null
        }.distinctBy { it.id }.map { it.copy(opacity = it.opacity.coerceIn(Piece.OPACITIES)) }
        if (kept.isEmpty()) return default
        val missing = default.pieces.filter { d -> kept.none { it.id == d.id } }
        return ControlsLayout(missing + kept)
    }
}
