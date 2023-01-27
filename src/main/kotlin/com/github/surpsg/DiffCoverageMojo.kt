package com.github.surpsg

import com.form.coverage.config.DiffCoverageConfig
import com.form.coverage.config.DiffSourceConfig
import com.form.coverage.config.ReportConfig
import com.form.coverage.config.ReportsConfig
import com.form.coverage.config.ViolationRuleConfig
import com.form.coverage.report.ReportGenerator
import org.apache.maven.execution.MavenSession
import org.apache.maven.monitor.event.EventMonitor
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.FileUtils
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.relativeTo


@Mojo(name = "diffCoverage", defaultPhase = LifecyclePhase.VERIFY)
class DiffCoverageMojo : AbstractMojo() {

    @Parameter(property = "reactorProjects", required = true, readonly = true)
    private lateinit var reactorProjects: MutableList<MavenProject>

    @Parameter(property = "jacoco.dataFile", defaultValue = "\${project.build.directory}/jacoco.exec")
    private lateinit var dataFile: File

    @Parameter(property = "jacoco.dataFileIncludes", required = false)
    private var dataFileIncludes: String? = null

    @Parameter(property = "jacoco.dataFileExcludes", required = false)
    private var dataFileExcludes: String? = null

    @Parameter(defaultValue = ALL_FILES_PATTERN, required = false)
    private lateinit var includes: List<String>

    @Parameter(required = false)
    private var excludes: List<String> = emptyList()

    @Parameter(name = "diffSource", required = true)
    private lateinit var diffSource: DiffSourceConfiguration

    @Parameter(name = "violations")
    private var violations = ViolationsConfiguration()

    @Parameter(property = "session", required = true, readonly = true)
    private lateinit var session: MavenSession

    @Parameter(property = "project", required = true, readonly = true)
    private lateinit var project: MavenProject

//    private val rootProjectDir: File
//        get() = reactorProjects[0].basedir

    override fun execute() {

        val thisTheLastProject: Boolean = isThisTheLastProject()


//        val size = reactorProjects.size
//        if (reactorProjects[size - 1] !== project) {
//            println("it's not last")
//        } else {
//            println("it's last")
//        }

        if (!thisTheLastProject) {
            return
        }
        val rootProjectDir = session.topLevelProject.basedir
        val outputDirectory = rootProjectDir.resolve("target/diffCoverage")
        outputDirectory.mkdirs()

        val diffCoverageConfig: DiffCoverageConfig = buildDiffCoverageConfig(rootProjectDir, outputDirectory).apply {
            logPluginProperties(this)
        }

        ReportGenerator(outputDirectory, diffCoverageConfig).apply {
            val reportDir = File(diffCoverageConfig.reportsConfig.baseReportDir)
            reportDir.mkdirs()
            log.error("diffSaveTo: $reportDir")
            saveDiffToDir(reportDir).apply {
                log.info("diff content saved to '$absolutePath'")
            }

            create()
        }
    }

    fun isThisTheLastProject(): Boolean {
        return session.getProjectDependencyGraph().getSortedProjects()
            .get(session.getProjectDependencyGraph().getSortedProjects().size - 1).getArtifactId()
            .equals(project.getArtifactId(), true)
    }



    private fun logPluginProperties(diffCoverageConfig: DiffCoverageConfig) {
        log.apply {
//            debug("Root dir: $rootProjectDir")
            debug("Classes dirs: ${diffCoverageConfig.classFiles}")
            debug("Sources: ${diffCoverageConfig.sourceFiles}")
            debug("Exec files: ${diffCoverageConfig.execFiles}")
        }
    }

    private fun buildDiffCoverageConfig(rootProjectDir: File, outputDirectory: File): DiffCoverageConfig {
        val resultPath: String = if (diffSource.file != null) {
            rootProjectDir.resolve("${diffSource.file}").absolutePath
        } else {
            ""
        }
        return DiffCoverageConfig(
            reportName = "aza",
            diffSourceConfig = DiffSourceConfig(
                file = resultPath,
                url = diffSource.url.asStringOrEmpty { toString() },
                diffBase = diffSource.git ?: ""
            ),
            reportsConfig = ReportsConfig(
                baseReportDir = outputDirectory.absolutePath,
                html = ReportConfig(enabled = true, "html"),
                csv = ReportConfig(enabled = true, "diff-coverage.csv"),
                xml = ReportConfig(enabled = true, "diff-coverage.xml")
            ),
            violationRuleConfig = buildViolationRuleConfig(),
            execFiles = collectExecFiles(rootProjectDir),
            classFiles = collectClassesFiles().throwIfEmpty("Classes collection passed to Diff-Coverage"),
            sourceFiles = reactorProjects.map { it.compileSourceRoots }.flatten().map { File(it) }.toSet()
        )
    }

    private fun buildViolationRuleConfig(): ViolationRuleConfig {
        val isMinCoverageSet: Boolean = violations.minCoverage != MIN_COVERAGE_PROPERTY_DEFAULT
        val configuredProperties: Set<Pair<String, Double>> = collectConfiguredCoveragePropertiesNames()

        if (isMinCoverageSet && configuredProperties.isNotEmpty()) {
            val conflictingProperties = configuredProperties.joinToString(separator = "\n") {
                "violations.${it.first} = ${it.second}"
            }
            throw IllegalArgumentException(
                """
                
                Simultaneous configuration of 'minCoverage' and any of [minCoverage, minBranches, minInstructions] is not allowed.
                violations.minCoverage = ${violations.minCoverage}
                $conflictingProperties
            """.trimIndent()
            )
        }

        return if (isMinCoverageSet) {
            ViolationRuleConfig(
                minBranches = violations.minCoverage,
                minInstructions = violations.minCoverage,
                minLines = violations.minCoverage,
                failOnViolation = violations.failOnViolation
            )
        } else {
            ViolationRuleConfig(
                minBranches = violations.minBranches,
                minInstructions = violations.minInstructions,
                minLines = violations.minLines,
                failOnViolation = violations.failOnViolation
            )
        }
    }

    private fun collectConfiguredCoveragePropertiesNames(): Set<Pair<String, Double>> {
        return sequenceOf(
            "minLines" to violations.minLines,
            "minBranches" to violations.minBranches,
            "minInstructions" to violations.minInstructions
        ).filter {
            it.second > 0.0
        }.toSet()
    }

    private fun collectExecFiles(rootProjectDir: File): Set<File> {
        return if (dataFileIncludes == null) {
            setOf(dataFile)
        } else {
            FileUtils.getFiles(rootProjectDir, dataFileIncludes, dataFileExcludes).toSet()
        }
    }

    private fun collectClassesFiles(): Set<File> {
        val includePattern: String = includes.joinToString(",")
        val excludePattern: String = excludes.joinToString(",")
        return if (excludePattern.isEmpty() && includePattern == ALL_FILES_PATTERN) {
            reactorProjects.map { File(it.build.outputDirectory) }.toSet()
        } else {
            collectFilteredFiles(includePattern, excludePattern)
        }
    }

    private fun collectFilteredFiles(includePattern: String, excludePattern: String?): Set<File> {
        return reactorProjects.asSequence()
            .map { project -> File(project.build.outputDirectory) }
            .filter { outputDirectory -> outputDirectory.exists() }
            .flatMap { outputDirectory ->
                FileUtils.getFiles(
                    outputDirectory,
                    includePattern,
                    excludePattern
                )
            }.toSet()
    }

    private fun <T> T?.asStringOrEmpty(toString: T.() -> String): String = if (this != null) {
        toString(this)
    } else {
        ""
    }

    private fun <T> Set<T>.throwIfEmpty(collectionDescription: String): Set<T> {
        if (isEmpty()) {
            throw RuntimeException("$collectionDescription is empty")
        }
        return this
    }

    private companion object {
        const val ALL_FILES_PATTERN = "**"
    }

}
