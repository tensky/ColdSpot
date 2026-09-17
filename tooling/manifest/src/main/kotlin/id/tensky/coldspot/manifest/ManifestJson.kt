package id.tensky.coldspot.manifest

/**
 * The manifests as JSON, exactly schema [Manifest.SCHEMA_VERSION]: written as the build writes them and read as
 * the runtime reads them, with nothing but the standard library. Any other `schemaVersion` is refused with a
 * message that says so, because the build and the runtime in one APK must be of one mind; fields this schema
 * does not know are ignored, so that a newer build's extra field never breaks an older reader on its own.
 * Malformed text, or text of the wrong shape, is a [ManifestFormatException] with the line and column.
 */
public object ManifestJson {
    public fun write(manifest: Manifest): String = manifest.toJson().render()

    public fun write(manifest: ModuleManifest): String = manifest.toJson().render()

    /** @throws ManifestFormatException when [text] is not JSON, not this schema, or not a shared manifest */
    public fun read(text: String): Manifest {
        val root = Fields(parse(text), "manifest")
        root.schemaVersion()
        return Manifest(
            schemaVersion = root.int("schemaVersion"),
            coldspotVersion = root.string("coldspotVersion"),
            base = root.obj("base").let { Manifest.Base(it.string("ref"), it.string("sha"), it.enum("source")) },
            head = root.obj("head").let { Manifest.Head(it.nullableString("sha"), it.nullableString("branch"), it.bool("dirty")) },
            commits = root.obj("commits").let { c ->
                Manifest.Commits(c.objects("listed").map { Manifest.Commit(it.string("shortSha"), it.string("sha"), it.string("summary")) }, c.int("total"))
            },
            jacoco = root.obj("jacoco").let { Manifest.Jacoco(it.string("version"), it.string("build")) },
            resetToken = root.nullableString("resetToken"),
            files = root.objects("files").map { Manifest.ChangedFile(it.string("path"), it.ints("changedLines"), it.string("text")) },
            excluded = root.objects("excluded").map { Manifest.ExcludedFile(it.string("path"), it.int("changedLines"), it.string("rule")) },
        )
    }

    /** @throws ManifestFormatException when [text] is not JSON, not this schema, or not a module manifest */
    public fun readModule(text: String): ModuleManifest {
        val root = Fields(parse(text), "manifest")
        root.schemaVersion()
        return ModuleManifest(
            schemaVersion = root.int("schemaVersion"),
            module = root.string("module"),
            files = root.objects("files").map { file ->
                ModuleManifest.ModuleFile(
                    path = file.string("path"),
                    classes = file.strings("classes"),
                    blindLines = file.ints("blindLines"),
                    previews = file.objects("previews").map { ModuleManifest.PreviewLines(it.ints("lines"), it.string("reason")) },
                )
            },
        )
    }

    private fun parse(text: String): JsonValue.Obj {
        val value = JsonParser(text).parse()
        return value as? JsonValue.Obj ?: throw ManifestFormatException("a manifest is a JSON object, found ${value.kind}", text, value.at)
    }

    private fun Manifest.toJson(): JsonValue = obj(
        "schemaVersion" to num(schemaVersion),
        "coldspotVersion" to str(coldspotVersion),
        "base" to obj("ref" to str(base.ref), "sha" to str(base.sha), "source" to str(base.source.name)),
        "head" to obj("sha" to head.sha?.let(::str).orNull(), "branch" to head.branch?.let(::str).orNull(), "dirty" to JsonValue.Bool(head.dirty)),
        "commits" to obj(
            "listed" to arr(commits.listed.map { obj("shortSha" to str(it.shortSha), "sha" to str(it.sha), "summary" to str(it.summary)) }),
            "total" to num(commits.total),
        ),
        "jacoco" to obj("version" to str(jacoco.version), "build" to str(jacoco.build)),
        "resetToken" to resetToken?.let(::str).orNull(),
        "files" to arr(files.map { obj("path" to str(it.path), "changedLines" to ints(it.changedLines), "text" to str(it.text)) }),
        "excluded" to arr(excluded.map { obj("path" to str(it.path), "changedLines" to num(it.changedLines), "rule" to str(it.rule)) }),
    )

    private fun ModuleManifest.toJson(): JsonValue = obj(
        "schemaVersion" to num(schemaVersion),
        "module" to str(module),
        "files" to arr(
            files.map { file ->
                obj(
                    "path" to str(file.path),
                    "classes" to arr(file.classes.map(::str)),
                    "blindLines" to ints(file.blindLines),
                    "previews" to arr(file.previews.map { obj("lines" to ints(it.lines), "reason" to str(it.reason)) }),
                )
            },
        ),
    )

    private fun obj(vararg fields: Pair<String, JsonValue>): JsonValue = JsonValue.Obj(linkedMapOf(*fields))
    private fun arr(items: List<JsonValue>): JsonValue = JsonValue.Arr(items)
    private fun str(s: String): JsonValue = JsonValue.Str(s)
    private fun num(n: Int): JsonValue = JsonValue.Num(n.toString())
    private fun ints(ns: List<Int>): JsonValue = JsonValue.Arr(ns.map(::num))
    private fun JsonValue?.orNull(): JsonValue = this ?: JsonValue.Null()

    /** The fields of one object, read by name and type, with [where] (`manifest.files[3]`) for the messages. */
    private class Fields(private val obj: JsonValue.Obj, private val where: String) {
        fun schemaVersion() {
            val version = int("schemaVersion")
            if (version != Manifest.SCHEMA_VERSION) {
                throw ManifestFormatException(
                    "this manifest is schemaVersion $version, and this ColdSpot reads schemaVersion ${Manifest.SCHEMA_VERSION}: the build that " +
                        "made the APK and the runtime inside it are different ColdSpot versions. Rebuild with one version of both.",
                )
            }
        }

        fun string(name: String): String = (get(name) as? JsonValue.Str)?.value ?: wrong(name, "a string")

        fun nullableString(name: String): String? = when (val value = get(name)) {
            is JsonValue.Null -> null
            is JsonValue.Str -> value.value
            else -> wrong(name, "a string or null")
        }

        fun int(name: String): Int = (get(name) as? JsonValue.Num)?.text?.toIntOrNull() ?: wrong(name, "an integer")

        fun bool(name: String): Boolean = (get(name) as? JsonValue.Bool)?.value ?: wrong(name, "true or false")

        inline fun <reified E : Enum<E>> enum(name: String): E {
            val value = string(name)
            return enumValues<E>().firstOrNull { it.name == value }
                ?: throw ManifestFormatException("$where.$name: \"$value\" is not one of ${enumValues<E>().joinToString { it.name }}")
        }

        fun obj(name: String): Fields = Fields((get(name) as? JsonValue.Obj) ?: wrong(name, "an object"), "$where.$name")

        fun objects(name: String): List<Fields> = array(name).mapIndexed { i, item ->
            Fields((item as? JsonValue.Obj) ?: throw ManifestFormatException("$where.$name[$i]: expected an object, found ${item.kind}"), "$where.$name[$i]")
        }

        fun strings(name: String): List<String> = array(name).mapIndexed { i, item ->
            (item as? JsonValue.Str)?.value ?: throw ManifestFormatException("$where.$name[$i]: expected a string, found ${item.kind}")
        }

        fun ints(name: String): List<Int> = array(name).mapIndexed { i, item ->
            (item as? JsonValue.Num)?.text?.toIntOrNull() ?: throw ManifestFormatException("$where.$name[$i]: expected an integer, found ${item.kind}")
        }

        private fun array(name: String): List<JsonValue> = (get(name) as? JsonValue.Arr)?.items ?: wrong(name, "an array")

        private fun get(name: String): JsonValue = obj.fields[name] ?: throw ManifestFormatException("$where: missing \"$name\"")

        private fun wrong(name: String, expected: String): Nothing =
            throw ManifestFormatException("$where.$name: expected $expected, found ${get(name).kind}")
    }
}
