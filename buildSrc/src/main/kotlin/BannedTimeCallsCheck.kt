import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when main source reads system time directly (CLAUDE.md rule 4, FR.STR.03).
 * Time must come from an injected Clock; only the files in [allowedFileNames] may read it.
 *
 * A plain text scan, not a bytecode analysis: simple to explain, and good enough because the
 * banned calls are short, distinctive expressions. Javadoc links like System#nanoTime don't match.
 */
@CacheableTask
abstract class BannedTimeCallsCheck : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val allowedFileNames: SetProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun check() {
        val allowed = allowedFileNames.get()
        val violations = mutableListOf<String>()
        sources.asFileTree.matching { include("**/*.java") }.forEach { file ->
            if (file.name in allowed) return@forEach
            file.readLines().forEachIndexed { index, line ->
                if (BANNED.any { it.containsMatchIn(line) }) {
                    violations += "${file.path}:${index + 1}: ${line.trim()}"
                }
            }
        }

        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(violations.joinToString("\n"))

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Direct system time reads found. Inject a Clock instead (CLAUDE.md rule 4):\n" +
                    violations.joinToString("\n")
            )
        }
    }

    private companion object {
        val BANNED = listOf(
            Regex("""\bSystem\s*\.\s*(currentTimeMillis|nanoTime)\s*\("""),
            Regex("""\b(Instant|LocalDate|LocalTime|LocalDateTime|ZonedDateTime|OffsetDateTime)\s*\.\s*now\s*\("""),
            Regex("""\bClock\s*\.\s*(systemUTC|systemDefaultZone)\s*\("""),
            Regex("""import\s+static\s+java\.lang\.System\.(currentTimeMillis|nanoTime|\*)"""),
        )
    }
}
