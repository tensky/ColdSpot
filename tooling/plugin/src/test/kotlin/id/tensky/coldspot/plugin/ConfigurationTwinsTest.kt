package id.tensky.coldspot.plugin

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How a coverage configuration's debug twin is named, and what is not a twin at all. */
class ConfigurationTwinsTest {

    @Test
    fun `the build type leads, or follows a flavor or a prefix, as one camel-case word`() {
        assertEquals("debugImplementation", twinConfigurationName("coverageImplementation", "coverage", "debug"))
        assertEquals("debugRuntimeOnly", twinConfigurationName("coverageRuntimeOnly", "coverage", "debug"))
        assertEquals("demoDebugImplementation", twinConfigurationName("demoCoverageImplementation", "coverage", "debug"))
        assertEquals("kspDebug", twinConfigurationName("kspCoverage", "coverage", "debug"))
        assertEquals("kspDemoDebug", twinConfigurationName("kspDemoCoverage", "coverage", "debug"))
        assertEquals("kaptDebug", twinConfigurationName("kaptCoverage", "coverage", "debug"))
        assertEquals("debugAndroidTestImplementation", twinConfigurationName("coverageAndroidTestImplementation", "coverage", "debug"))
    }

    @Test
    fun `the other way round names the coverage twin of a debug configuration`() {
        assertEquals("coverageImplementation", twinConfigurationName("debugImplementation", "debug", "coverage"))
        assertEquals("kspDemoCoverage", twinConfigurationName("kspDemoDebug", "debug", "coverage"))
    }

    @Test
    fun `a build type of several words is one word in a name`() {
        assertEquals("debugImplementation", twinConfigurationName("qaCoverageImplementation", "qaCoverage", "debug"))
        assertEquals("demoDebugApi", twinConfigurationName("demoQaCoverageApi", "qaCoverage", "debug"))
        assertNull(twinConfigurationName("qaImplementation", "qaCoverage", "debug"))
    }

    @Test
    fun `a name that merely contains the letters is not a twin`() {
        assertNull(twinConfigurationName("implementation", "coverage", "debug"))
        assertNull(twinConfigurationName("coverage", "coverage", "debug"), "the bare build type is not a configuration of it")
        assertNull(twinConfigurationName("discoverageImplementation", "coverage", "debug"), "no word boundary before")
        assertNull(twinConfigurationName("demoCoveragedImplementation", "coverage", "debug"), "no word boundary after")
        assertNull(twinConfigurationName("debugImplementation", "coverage", "debug"))
    }
}
