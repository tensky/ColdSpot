package id.tensky.coldspot.bundle

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The glob dialect of the exclude rules, and which rule a path is charged to. */
class ExcludeRulesTest {

    @Test
    fun `a leading double star matches at the root and at any depth, and only whole segments`() {
        val rules = ExcludeRules(listOf("**/src/test/**"))

        assertEquals("**/src/test/**", rules.matching("src/test/java/demo/FeatureTest.java"))
        assertEquals("**/src/test/**", rules.matching("feature/foryou/impl/src/test/kotlin/X.kt"))
        assertNull(rules.matching("app/src/testFixtures/java/X.java"), "src/test must not match src/testFixtures")
        assertNull(rules.matching("app/src/main/test/X.java"), "the segments must be adjacent")
        assertNull(rules.matching("app/xsrc/test/X.java"), "a segment must start at a slash")
    }

    @Test
    fun `a single star and a question mark stay within one segment`() {
        val rules = ExcludeRules(listOf("**/Generated*.java", "core/?/X.kt"))

        assertEquals("**/Generated*.java", rules.matching("a/b/GeneratedThing.java"))
        assertNull(rules.matching("a/Generated/Thing.java"), "* crossed a directory")
        assertEquals("core/?/X.kt", rules.matching("core/a/X.kt"))
        assertNull(rules.matching("core/ab/X.kt"))
        assertNull(rules.matching("core/a/b/X.kt"))
    }

    @Test
    fun `the first matching rule is the one reported, in the order given`() {
        val rules = ExcludeRules(listOf("**/src/test/**", "**/*Test.java"))

        assertEquals("**/src/test/**", rules.matching("app/src/test/java/FeatureTest.java"))
        assertEquals("**/*Test.java", rules.matching("app/src/main/java/FeatureTest.java"))
    }

    @Test
    fun `the default rules cover test source sets, test fixtures, buildSrc and build-logic at any depth`() {
        val rules = ExcludeRules.DEFAULT

        assertEquals("**/src/test/**", rules.matching("app/src/test/java/demo/FeatureTest.java"))
        assertEquals("**/src/androidTest/**", rules.matching("app/src/androidTest/java/demo/UiTest.java"))
        assertEquals("**/src/testFixtures/**", rules.matching("tooling/diff/src/testFixtures/kotlin/FixtureRepo.kt"))
        assertEquals("**/buildSrc/**", rules.matching("buildSrc/src/main/kotlin/Conventions.kt"))
        assertEquals("**/build-logic/**", rules.matching("build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt"))
        assertNull(rules.matching("feature/foryou/impl/src/main/kotlin/ForYouScreen.kt"))
        assertNull(rules.matching("app/src/demo/kotlin/Fake.kt"), "a flavor source set is not a test source set")
    }

    @Test
    fun `dots and other regex characters in a pattern are literal`() {
        val rules = ExcludeRules(listOf("**/*.java"))

        assertEquals("**/*.java", rules.matching("a/B.java"))
        assertNull(rules.matching("a/Bxjava"), "the dot matched any character")
    }
}
