package example.setup

import labs.cerberus.ts.configuration.YamlConfiguration

data class ChannelSpec(val key: String, val name: String, val parent: String?,
    val properties: Map<String, String>, val permissions: Map<String, Int>)
data class GroupSpec(val key: String, val name: String, val template: String?, val permissions: Map<String, Int>)
data class SetupConfig(val applyOnStart: Boolean, val prune: Boolean, val server: Map<String, String>,
    val channels: List<ChannelSpec>, val serverGroups: List<GroupSpec>, val channelGroups: List<GroupSpec>) {
    companion object {
        val serverFields = setOf("virtualserver_name", "virtualserver_welcomemessage")
        private val channelFields = setOf("channel_topic", "channel_description", "channel_codec", "channel_codec_quality",
            "channel_maxclients", "channel_flag_maxclients_unlimited", "channel_needed_talk_power")
        fun read(config: YamlConfiguration): SetupConfig {
            fun mapping(value: Any?, label: String): Map<String, Any?> {
                require(value is Map<*, *> && value.keys.all { it is String }) { "$label muss eine YAML-Map sein." }
                return value.entries.associate { it.key as String to it.value }
            }
            fun entries(key: String) = mapping(config.get(key) ?: emptyMap<String, Any?>(), key).also {
                require(it.size <= 100) { "Maximal 100 Einträge in $key." }
                require(it.keys.all { k -> k.matches(Regex("[a-z][a-z0-9-]{0,47}")) }) { "Ungültiger Schlüssel in $key." }
            }
            fun text(value: Any?, label: String, limit: Int): String {
                require(value is String && value.isNotBlank() && value.length <= limit && '\u0000' !in value) { "Ungültiger Text: $label." }
                return value
            }
            fun properties(value: Any?, allowed: Set<String>, label: String) = mapping(value ?: emptyMap<String, Any?>(), label).mapValues { (key, v) ->
                require(key in allowed) { "Nicht unterstützte Eigenschaft: $key." }
                require(v is String || v is Number) { "$label.$key muss Text oder eine Zahl sein." }
                v.toString().also { require(it.length <= 8000 && '\u0000' !in it) { "$label.$key ist zu lang oder enthält NUL." } }
            }
            fun permissions(value: Any?, label: String) = mapping(value ?: emptyMap<String, Any?>(), label).mapValues { (key, v) ->
                require(key.matches(Regex("[ib]_[a-z0-9_]+"))) { "Ungültige Permission: $key." }
                require(v is Number && v.toString().toIntOrNull() != null) { "$label.$key muss eine Ganzzahl sein." }
                v.toInt()
            }
            val channels = entries("channels").map { (key, value) ->
                val fields = mapping(value, "channels.$key")
                require(fields.keys.all { it in setOf("name", "parent", "properties", "permissions") }) { "Unbekanntes Channel-Feld: $key." }
                val props = properties(fields["properties"], channelFields, key)
                props.filterKeys { it !in setOf("channel_topic", "channel_description") }.forEach { (field, value) ->
                    val n = value.toIntOrNull() ?: error("$key.$field muss eine Ganzzahl sein.")
                    require(when (field) {
                        "channel_codec" -> n in 4..5
                        "channel_codec_quality" -> n in 0..10
                        "channel_flag_maxclients_unlimited" -> n in 0..1
                        else -> n >= 0
                    }) { "Ungültiger Wert für $key.$field." }
                }
                ChannelSpec(key, text(fields["name"], "$key.name", 40), fields["parent"]?.let { text(it, "$key.parent", 48) },
                    props, permissions(fields["permissions"], key))
            }
            require(channels.map { it.parent to it.name }.distinct().size == channels.size) { "Doppelte Channel-Namen unter demselben Parent." }
            val byKey = channels.associateBy { it.key }
            val ordered = mutableListOf<ChannelSpec>(); val visiting = mutableSetOf<String>(); val done = mutableSetOf<String>()
            fun visit(key: String) {
                if (key in done) return
                require(visiting.add(key)) { "Zyklische Channel-Hierarchie: $key." }
                val entry = byKey[key] ?: error("Unbekannter Parent: $key.")
                entry.parent?.let(::visit)
                visiting.remove(key); done += key; ordered += entry
            }
            channels.forEach { visit(it.key) }
            fun groups(path: String) = entries(path).map { (key, value) ->
                val fields = mapping(value, "$path.$key")
                require(fields.keys.all { it in setOf("name", "template", "permissions") }) { "Unbekanntes Gruppen-Feld: $key." }
                GroupSpec(key, text(fields["name"], "$path.$key.name", 30), fields["template"]?.let { text(it, "$key.template", 30) },
                    permissions(fields["permissions"], key))
            }.also { require(it.map(GroupSpec::name).distinct().size == it.size) { "Doppelte Gruppennamen in $path." } }
            fun boolean(path: String): Boolean {
                val value = config.get(path)
                require(value is Boolean) { "$path muss true oder false sein." }
                return value
            }
            return SetupConfig(boolean("apply-on-start"), boolean("prune"),
                properties(config.get("server"), serverFields, "server"), ordered, groups("server-groups"), groups("channel-groups"))
        }
    }
}
