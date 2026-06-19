package eu.neilalexander.yggdrasil

import android.content.Context
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ConfigurationProxy {
    private lateinit var json: JSONObject
    private lateinit var file: File

    operator fun invoke(applicationContext: Context): ConfigurationProxy {
        file = File(applicationContext.filesDir, "yggdrasil.conf")
        if (!file.exists()) {
            val conf = Mobile.generateConfigJSON()
            if (file.createNewFile()) {
                file.writeBytes(conf)
            }
        }
        fix()
        return this
    }

    fun resetJSON() {
        val conf = Mobile.generateConfigJSON()
        file.writeBytes(conf)
        fix()
    }

    fun resetKeys() {
        val newJson = JSONObject(String(Mobile.generateConfigJSON()))
        updateJSON { json ->
            json.put("PrivateKey", newJson.getString("PrivateKey"))
        }
    }

    fun setKeys(privateKey: String) {
        updateJSON { json ->
            json.put("PrivateKey", privateKey)
        }
    }

    fun updateJSON(fn: (JSONObject) -> Unit) {
        json = JSONObject(file.readText(Charsets.UTF_8))
        fn(json)
        val str = json.toString()
        file.writeText(str, Charsets.UTF_8)
    }

    private fun fix() {
        updateJSON { json ->
            json.put("AdminListen", "none")
            json.put("IfName", "none")
            json.put("IfMTU", 65535)

            if (json.getJSONArray("MulticastInterfaces").get(0) is String) {
                val ar = JSONArray()
                ar.put(0, JSONObject("""
                    {
                        "Regex": ".*",
                        "Beacon": true,
                        "Listen": true,
                        "Password": ""
                    }
                """.trimIndent()))
                json.put("MulticastInterfaces", ar)
            }
        }
    }

    fun getJSON(): JSONObject {
        fix()
        return json
    }

    fun getJSONByteArray(): ByteArray {
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    var multicastListen: Boolean
        get() = (json.getJSONArray("MulticastInterfaces").get(0) as JSONObject).getBoolean("Listen")
        set(value) {
            updateJSON { json ->
                (json.getJSONArray("MulticastInterfaces").get(0) as JSONObject).put("Listen", value)
            }
        }

    var multicastBeacon: Boolean
        get() = (json.getJSONArray("MulticastInterfaces").get(0) as JSONObject).getBoolean("Beacon")
        set(value) {
            updateJSON { json ->
                (json.getJSONArray("MulticastInterfaces").get(0) as JSONObject).put("Beacon", value)
            }
        }

    var multicastPassword: String
        get() = (json.getJSONArray("MulticastInterfaces").get(0) as JSONObject).optString("Password")
        set(value) {
            updateJSON { json ->
                (json.getJSONArray("MulticastInterfaces").get(0) as JSONObject).put("Password", value)
            }
        }

    var exitNodeEnabled: Boolean
        get() = json.optBoolean("ExitNodeEnabled", false)
        set(value) {
            updateJSON { json ->
                json.put("ExitNodeEnabled", value)
            }
        }

    var gatewayAddress: String
        get() = json.optString("GatewayAddress", "")
        set(value) {
            updateJSON { json ->
                json.put("GatewayAddress", value)
            }
        }

    var gatewayPort: Int
        get() = json.optInt("GatewayPort", 1080)
        set(value) {
            updateJSON { json ->
                json.put("GatewayPort", value)
            }
        }

    var gatewayUsername: String
        get() = json.optString("GatewayUsername", "")
        set(value) {
            updateJSON { json ->
                json.put("GatewayUsername", value)
            }
        }

    var gatewayPassword: String
        get() = json.optString("GatewayPassword", "")
        set(value) {
            updateJSON { json ->
                json.put("GatewayPassword", value)
            }
        }
}