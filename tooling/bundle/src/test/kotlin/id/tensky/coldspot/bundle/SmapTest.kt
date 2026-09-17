package id.tensky.coldspot.bundle

import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** [parseSmap] against kotlinc's own output, and against the corners of JSR-045 that kotlinc uses. */
class SmapTest {

    @Test
    fun `a Compose-compiled lambda's SMAP traces its line numbers back to the call site in Feed_kt`() {
        // given the SourceDebugExtension of the class kotlinc regenerated from LazyDsl.kt's inline `items`
        val smap = parseSmap(smapOf("/compose/classes/id/tensky/coldspotspike/$INLINED_ITEMS_LAMBDA.class"))

        // then its files are LazyDsl.kt, Feed.kt and Composer.kt, each placed by its facade class
        assertEquals(listOf(1 to "LazyDsl.kt", 2 to "Feed.kt", 3 to "Composer.kt"), smap.files.values.map { it.id to it.name })
        assertEquals("id/tensky/coldspotspike", smap.files.getValue(2).packageDir)
        assertEquals("androidx/compose/foundation/lazy", smap.files.getValue(1).packageDir)

        // and the class's line numbers 524 and 531 are Feed.kt's 27 and 28, with Composer.kt's in between
        assertEquals(2 to 27, smap.sourceOf(524)?.pair())
        assertEquals(2 to 28, smap.sourceOf(531)?.pair())
        assertEquals(3 to 1049, smap.sourceOf(527)?.pair())
        assertEquals(1 to 200, smap.sourceOf(200)?.pair())
        assertNull(smap.sourceOf(600))
    }

    @Test
    fun `repeat counts, output increments and a carried-over file id read as JSR-045 says`() {
        // given entries of every shape kotlinc emits, and a KotlinDebug stratum with a file table of its own,
        // as the real ones have, mapping the other way
        val smap = parseSmap(
            """
            SMAP
            X.kt
            Kotlin
            *S Kotlin
            *F
            + 1 X.kt
            com/acme/XKt
            2 Y.kt
            *L
            1#1,10:1
            1761#2,3:502
            5,2:100,3
            *S KotlinDebug
            *F
            + 1 Z.kt
            com/acme/ZKt
            *L
            304#1:900,3
            *E
            """.trimIndent(),
        )

        // then a file without a path has no package
        assertNull(smap.files.getValue(2).path)
        assertEquals("", smap.files.getValue(2).packageDir)
        // `1#1,10:1` covers output lines 1..10
        assertEquals(1 to 10, smap.sourceOf(10)?.pair())
        assertNull(smap.sourceOf(11))
        // `1761#2,3:502` covers 502..504
        assertEquals(2 to 1763, smap.sourceOf(504)?.pair())
        // `5,2:100,3` keeps file 2 and gives each of the two input lines three output lines
        assertEquals(2 to 5, smap.sourceOf(102)?.pair())
        assertEquals(2 to 6, smap.sourceOf(103)?.pair())
        assertNull(smap.sourceOf(106))
        // and the KotlinDebug stratum was not read: file 1 is still X.kt, and its `304#1:900,3` maps nothing
        assertEquals("X.kt", smap.files.getValue(1).name)
        assertNull(smap.sourceOf(900))
    }

    @Test
    fun `anything that does not begin like an SMAP is refused`() {
        assertFailsWith<IllegalArgumentException> { parseSmap("not an SMAP at all") }
    }
}

/** The lambda kotlinc regenerated for `items(visible) { item -> FeedRow(...) }` in the spike's Feed.kt. */
internal const val INLINED_ITEMS_LAMBDA: String = "FeedKt\$Feed\$lambda\$10\$lambda\$9\$lambda\$8\$\$inlined\$items\$default\$4"

private fun SourceLine.pair(): Pair<Int, Int> = fileId to line

/** The SourceDebugExtension of a class file on the test classpath. */
private fun smapOf(resource: String): String {
    val bytes = checkNotNull(SmapTest::class.java.getResourceAsStream(resource)) { "no test resource $resource" }.readBytes()
    var smap: String? = null
    ClassReader(bytes).accept(
        object : ClassVisitor(Opcodes.ASM9) {
            override fun visitSource(source: String?, debug: String?) {
                smap = debug
            }
        },
        ClassReader.SKIP_CODE,
    )
    return checkNotNull(smap) { "$resource has no SourceDebugExtension" }
}
