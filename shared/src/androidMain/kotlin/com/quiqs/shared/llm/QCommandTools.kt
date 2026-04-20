package com.quiqs.shared.llm

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import org.json.JSONArray
import org.json.JSONObject

private fun noopTool(descJson: String) = object : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = descJson
    override fun execute(paramsJsonString: String): String = ""
}

private val CREATE_DESC = """
    {
      "name": "create",
      "description": "Create a new Q from the user's request.",
      "parameters": {
        "type": "object",
        "properties": {
          "text": {"type": "string", "description": "Q text."},
          "tags": {"type": "array", "items": {"type": "string"}, "description": "Optional tags."},
          "alarms": {"type": "array", "items": {"type": "object"}, "description": "Optional alarms; each item has a 'type' of oneOffTime, recurringTime, or geofence."}
        },
        "required": ["text"]
      }
    }
""".trimIndent()

private val UPDATE_DESC = """
    {
      "name": "update",
      "description": "Update an existing Q. Only include fields that change.",
      "parameters": {
        "type": "object",
        "properties": {
          "id": {"type": "string", "description": "Existing Q id."},
          "text": {"type": "string"},
          "tags": {"type": "array", "items": {"type": "string"}},
          "alarms": {"type": "array", "items": {"type": "object"}}
        },
        "required": ["id"]
      }
    }
""".trimIndent()

private val DELETE_DESC = """
    {
      "name": "delete",
      "description": "Delete an existing Q.",
      "parameters": {
        "type": "object",
        "properties": {
          "id": {"type": "string", "description": "Existing Q id."}
        },
        "required": ["id"]
      }
    }
""".trimIndent()

private val ANSWER_DESC = """
    {
      "name": "answer",
      "description": "Answer a question about the user's Qs without making changes.",
      "parameters": {
        "type": "object",
        "properties": {
          "text": {"type": "string", "description": "Answer text shown to the user."}
        },
        "required": ["text"]
      }
    }
""".trimIndent()

object QCommandTools {
    val providers: List<ToolProvider> = listOf(
        tool(noopTool(CREATE_DESC)),
        tool(noopTool(UPDATE_DESC)),
        tool(noopTool(DELETE_DESC)),
        tool(noopTool(ANSWER_DESC)),
    )

    fun toCommandJson(call: ToolCall): String {
        val obj = JSONObject()
        obj.put("type", call.name)
        for ((k, v) in call.arguments) obj.put(k, toJsonValue(v))
        return obj.toString()
    }

    private fun toJsonValue(v: Any?): Any {
        if (v == null) return JSONObject.NULL
        return when (v) {
            is Map<*, *> -> {
                val o = JSONObject()
                for ((k, vv) in v) o.put(k.toString(), toJsonValue(vv))
                o
            }
            is Iterable<*> -> {
                val a = JSONArray()
                for (item in v) a.put(toJsonValue(item))
                a
            }
            is Number, is Boolean, is String -> v
            else -> v.toString()
        }
    }
}
