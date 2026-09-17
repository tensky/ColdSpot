import org.gradle.api.internal.tasks.testing.TestDescriptorInternal
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestOutputEvent
import org.gradle.api.tasks.testing.TestOutputListener
import org.gradle.api.tasks.testing.TestResult
import java.util.Base64

// ---- Test events for Android Studio ------------------------------------------------------------------------
// Android Studio builds its test tree from `<ijLog>` lines that an init script of its own prints from a
// TestListener; that script attaches the listener from `gradle.taskGraph.whenReady`, which Gradle 9 fires with an
// empty graph for a task delegated to an included build. So a tooling test started from the IDE runs, passes,
// and ends in "Test events not received". This prints the same lines, in the same shape, for the Test tasks of
// the module that applies it, and only then: when the IDE started the build (its `android.injected.invoked.from.ide`
// property) and tooling is included in another build (the IDE's own listener works when tooling is opened on its own).
// Every tooling module applies it to itself, from its own build file: a build that includes tooling may run with
// isolated projects, and there no project may configure another's tasks (an `allprojects` block in tooling's root
// build file failed Android Studio's sync of such a build).
// Retire it once the IDE attaches through `tasks.withType(Test).configureEach`, or the tree shows every test twice.
val invokedFromIde = providers.gradleProperty("android.injected.invoked.from.ide").isPresent || System.getProperty("idea.active") == "true"
if (invokedFromIde && gradle.parent != null) {
    tasks.withType<Test>().configureEach {
        // Everything read off the task itself at execution time: the action captures nothing the
        // configuration cache would have to serialise.
        doFirst {
            val test = this as Test
            val report = test.reports.html.outputLocation.get().asFile.resolve("index.html").absolutePath
            IdeTestEvents.log("<event type='reportLocation' testReport='${IdeTestEvents.attr(report)}' />")
            test.addTestListener(IdeTestEvents.Listener)
            test.addTestOutputListener(IdeTestEvents.OutputListener)
        }
    }
}

/** The IDE's `ijTestLogger` protocol: one `<ijLog>` line per event, line breaks inside written as `<ijLogEol/>`. */
object IdeTestEvents {
    fun log(xml: String) = println("<ijLog>${xml.replace("\n", "<ijLogEol/>")}</ijLog>")

    fun attr(value: String?): String =
        (value ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;").replace("\"", "&quot;")

    /** Text the IDE reads back from base64, so that nothing in it can break the XML. */
    private fun cdata(value: String?): String = "<![CDATA[${Base64.getEncoder().encodeToString((value ?: "").toByteArray())}]]>"

    private fun testEvent(type: String, descriptor: TestDescriptor, body: StringBuilder.() -> Unit = {}): String {
        val internal = descriptor as TestDescriptorInternal
        val displayName = try { descriptor.displayName } catch (e: Throwable) { descriptor.name }
        return buildString {
            append("<event type='$type'>\n")
            append("  <test id='${attr(internal.id.toString())}' parentId='${attr(internal.parent?.id?.toString())}'>\n")
            append("    <descriptor name='${attr(descriptor.name)}' displayName='${attr(displayName)}' className='${attr(descriptor.className)}' />\n")
            body()
            append("  </test>\n</event>")
        }
    }

    private fun StringBuilder.result(result: TestResult) {
        append("    <result resultType='${result.resultType}' startTime='${result.startTime}' endTime='${result.endTime}'>\n")
        val exception = result.exception
        if (exception != null) {
            append("<errorMsg>${cdata(exception.message)}</errorMsg>")
            append("<exceptionName>${cdata(exception.javaClass.name)}</exceptionName>")
            append("<stackTrace>${cdata(java.io.StringWriter().also { exception.printStackTrace(java.io.PrintWriter(it)) }.toString())}</stackTrace>")
        }
        val comparison = exception?.let { e -> generateSequence(e.javaClass as Class<*>?) { it.superclass }.firstOrNull { it.name == "org.junit.ComparisonFailure" || it.name == "junit.framework.ComparisonFailure" } }
        when {
            comparison != null -> {
                val expected = comparison.getMethod("getExpected").invoke(exception) as String?
                val actual = comparison.getMethod("getActual").invoke(exception) as String?
                append("      <failureType>comparison</failureType>\n")
                append("<expected>${cdata(expected)}</expected><actual>${cdata(actual)}</actual>")
            }
            exception is AssertionError -> append("      <failureType>assertionFailed</failureType>\n")
            else -> append("      <failureType>error</failureType>\n")
        }
        append("    </result>\n")
    }

    object Listener : TestListener {
        override fun beforeSuite(suite: TestDescriptor) = log(testEvent("beforeSuite", suite))
        override fun afterSuite(suite: TestDescriptor, result: TestResult) = log(testEvent("afterSuite", suite) { result(result) })
        override fun beforeTest(testDescriptor: TestDescriptor) = log(testEvent("beforeTest", testDescriptor))
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) = log(testEvent("afterTest", testDescriptor) { result(result) })
    }

    object OutputListener : TestOutputListener {
        override fun onOutput(testDescriptor: TestDescriptor, outputEvent: TestOutputEvent) =
            log(testEvent("onOutput", testDescriptor) { append("    <event destination='${outputEvent.destination}'>${cdata(outputEvent.message)}</event>\n") })
    }
}
