package com.bugsnag.android.gradle

import com.android.build.api.artifact.ArtifactType
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.AppExtension
import com.android.build.gradle.api.ApkVariant
import com.android.build.gradle.api.ApkVariantOutput
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.tasks.ExternalNativeBuildTask
import com.bugsnag.android.gradle.BugsnagInstallJniLibsTask.Companion.resolveBugsnagArtifacts
import com.bugsnag.android.gradle.internal.BugsnagHttpClientHelper
import com.bugsnag.android.gradle.internal.BuildServiceBugsnagHttpClientHelper
import com.bugsnag.android.gradle.internal.GradleVersions
import com.bugsnag.android.gradle.internal.LegacyBugsnagHttpClientHelper
import com.bugsnag.android.gradle.internal.UploadRequestClient
import com.bugsnag.android.gradle.internal.hasDexguardPlugin
import com.bugsnag.android.gradle.internal.newUploadRequestClientProvider
import com.bugsnag.android.gradle.internal.register
import com.bugsnag.android.gradle.internal.versionNumber
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gradle plugin to automatically upload ProGuard mapping files to Bugsnag.
 *
 * This plugin creates Gradle Tasks, and hooks them into a typical build
 * process. Knowledge of the Android build lifecycle is required to
 * understand how we attach tasks as dependencies.
 *
 * Run `gradle tasks --all` in an Android app project to see all tasks and
 * dependencies.
 *
 * Further reading:
 * https://sites.google.com/a/android.com/tools/tech-docs/new-build-system/user-guide#TOC-Build-Tasks
 * https://docs.gradle.org/current/userguide/custom_tasks.html
 */
class BugsnagPlugin : Plugin<Project> {

    companion object {
        const val GROUP_NAME = "Bugsnag"
        private const val CLEAN_TASK = "Clean"
    }

    @Suppress("LongMethod")
    override fun apply(project: Project) {
        // After Gradle 5.2, this can use service injection for injecting ObjectFactory
        val bugsnag = project.extensions.create(
            "bugsnag",
            BugsnagPluginExtension::class.java,
            project.objects
        )
        project.pluginManager.withPlugin("com.android.application") {
            if (!bugsnag.enabled.get()) {
                return@withPlugin
            }

            val canUseBuildService = project.gradle.versionNumber() >= GradleVersions.VERSION_6_1
            val httpClientHelperProvider = if (canUseBuildService) {
                project.gradle.sharedServices.registerIfAbsent("bugsnagHttpClientHelper",
                    BuildServiceBugsnagHttpClientHelper::class.java
                ) { spec ->
                    // Provide some parameters
                    spec.parameters.timeoutMillis.set(bugsnag.requestTimeoutMs)
                    spec.parameters.retryCount.set(bugsnag.retryCount)
                }
            } else {
                // Reuse instance
                val client = LegacyBugsnagHttpClientHelper(bugsnag.requestTimeoutMs, bugsnag.retryCount)
                project.provider { client }
            }

            val releasesUploadClientProvider = newUploadRequestClientProvider(project, "releases")
            val proguardUploadClientProvider = newUploadRequestClientProvider(project, "proguard")
            val ndkUploadClientProvider = newUploadRequestClientProvider(project, "ndk")

            val android = project.extensions.getByType(AppExtension::class.java)
            if (BugsnagManifestUuidTaskV2.isApplicable()) {
                check(android is CommonExtension<*, *, *, *, *, *, *, *>)
                android.onVariants {
                    if (!bugsnag.enabled.get()) {
                        return@onVariants
                    }
                    val variant = VariantFilterImpl(name)
                    if (!isVariantEnabled(bugsnag, variant)) {
                        return@onVariants
                    }
                    val variantName = name
                    val taskName = computeManifestTaskNameFor(variantName)
                    val manifestInfoOutputFile = project.computeManifestInfoOutputV2(variantName)
                    val buildUuidProvider = project.newUuidProvider()
                    val manifestUpdater = project.tasks.register(taskName,
                        BugsnagManifestUuidTaskV2::class.java) {
                        it.buildUuid.set(buildUuidProvider)
                        it.manifestInfoProvider.set(manifestInfoOutputFile)
                    }
                    onProperties {
                        artifacts
                            .use(manifestUpdater)
                            .wiredWithFiles(
                                taskInput = BugsnagManifestUuidTaskV2::inputManifest,
                                taskOutput = BugsnagManifestUuidTaskV2::outputManifest
                            )
                            .toTransform(ArtifactType.MERGED_MANIFEST)
                    }
                }
            }

            project.afterEvaluate {
                if (!bugsnag.enabled.get()) {
                    return@afterEvaluate
                }
                android.applicationVariants.configureEach { variant ->
                    val filterImpl = VariantFilterImpl(variant.name)
                    if (!isVariantEnabled(bugsnag, filterImpl)) {
                        return@configureEach
                    }
                    registerBugsnagTasksForVariant(
                        project,
                        android,
                        variant,
                        bugsnag,
                        httpClientHelperProvider,
                        releasesUploadClientProvider,
                        proguardUploadClientProvider,
                        ndkUploadClientProvider
                    )
                }
                registerNdkLibInstallTask(project, bugsnag, android)
            }
        }
    }

    private fun isVariantEnabled(bugsnag: BugsnagPluginExtension,
                                 variant: VariantFilterImpl): Boolean {
        bugsnag.filter?.execute(variant)
        return variant.variantEnabled ?: true
    }

    private fun registerNdkLibInstallTask(
        project: Project,
        bugsnag: BugsnagPluginExtension,
        android: AppExtension
    ) {
        val ndkTasks = project.tasks.withType(ExternalNativeBuildTask::class.java)
        val cleanTasks = ndkTasks.filter { it.name.contains(CLEAN_TASK) }.toSet()
        val buildTasks = ndkTasks.filter { !it.name.contains(CLEAN_TASK) }.toSet()

        if (buildTasks.isNotEmpty()) {
            val ndkSetupTask = BugsnagInstallJniLibsTask.register(project,
                "bugsnagInstallJniLibsTask") {
                val files = resolveBugsnagArtifacts(project)
                bugsnagArtifacts.from(files)
            }

            if (isNdkUploadEnabled(bugsnag, android)) {
                ndkSetupTask.configure {
                    it.mustRunAfter(cleanTasks)
                }
                buildTasks.forEach { it.dependsOn(ndkSetupTask) }
            }
        }
    }

    /**
     * Register manifest UUID writing + upload tasks for each Build Variant.
     *
     * See https://sites.google.com/a/android.com/tools/tech-docs/new-build-system/user-guide#TOC-Build-Variants
     */
    @Suppress("LongParameterList", "LongMethod", "ComplexMethod")
    private fun registerBugsnagTasksForVariant(
        project: Project,
        android: AppExtension,
        variant: ApplicationVariant,
        bugsnag: BugsnagPluginExtension,
        httpClientHelperProvider: Provider<out BugsnagHttpClientHelper>,
        releasesUploadClientProvider: Provider<out UploadRequestClient>,
        proguardUploadClientProvider: Provider<out UploadRequestClient>,
        ndkUploadClientProvider: Provider<out UploadRequestClient>
    ) {
        val proguardTaskRegistered = AtomicBoolean()
        lateinit var existingProguardTaskProvider: TaskProvider<out BugsnagUploadProguardTask>
        variant.outputs.configureEach { output ->
            check(output is ApkVariantOutput) {
                "Expected variant output to be ApkVariantOutput but found ${output.javaClass}"
            }
            val jvmMinificationEnabled = variant.buildType.isMinifyEnabled || project.hasDexguardPlugin()
            val ndkEnabled = isNdkUploadEnabled(bugsnag,
                project.extensions.getByType(AppExtension::class.java))

            // skip tasks for variant if JVM/NDK minification not enabled
            if (!jvmMinificationEnabled && !ndkEnabled) {
                return@configureEach
            }

            // register bugsnag tasks
            val manifestInfoFileProvider = registerManifestUuidTask(project, variant, output)
            val mappingFilesProvider = createMappingFileProvider(project, variant, output, android)

            val proguardTaskProvider = when {
                jvmMinificationEnabled -> {
                    if (proguardTaskRegistered.compareAndSet(false, true)) {
                        val registered = registerProguardUploadTask(
                            project,
                            variant,
                            bugsnag,
                            httpClientHelperProvider,
                            manifestInfoFileProvider,
                            proguardUploadClientProvider,
                            mappingFilesProvider
                        )
                        existingProguardTaskProvider = registered
                    }
                    existingProguardTaskProvider
                }
                else -> null
            }
            val symbolFileTaskProvider = when {
                ndkEnabled -> registerSharedObjectUploadTask(
                    project,
                    variant,
                    output,
                    bugsnag,
                    httpClientHelperProvider,
                    manifestInfoFileProvider,
                    ndkUploadClientProvider
                )
                else -> null
            }

            val releaseUploadTask = registerReleasesUploadTask(
                project,
                variant,
                output,
                bugsnag,
                manifestInfoFileProvider,
                releasesUploadClientProvider,
                mappingFilesProvider,
                symbolFileTaskProvider != null
            )

            if (shouldUploadMappings(output, bugsnag)) {
                if (bugsnag.reportBuilds.get()) {
                    variant.register(project, releaseUploadTask)
                }
                if (symbolFileTaskProvider != null && isNdkUploadEnabled(bugsnag, android)) {
                    variant.register(project, symbolFileTaskProvider)
                }
                if (proguardTaskProvider != null && bugsnag.uploadJvmMappings.get()) {
                    variant.register(project, proguardTaskProvider)
                }
            }
        }
    }

    private fun registerManifestUuidTask(
        project: Project,
        variant: ApkVariant,
        output: ApkVariantOutput
    ): Provider<RegularFile> {
        return if (BugsnagManifestUuidTaskV2.isApplicable()) {
            val taskName = computeManifestTaskNameFor(variant.name)
            // This task will have already been created!
            val manifestUpdater = project.tasks
                .withType(BugsnagManifestUuidTaskV2::class.java)
                .named(taskName)
            return manifestUpdater.flatMap(BaseBugsnagManifestUuidTask::manifestInfoProvider)
        } else {
            val taskName = computeManifestTaskNameFor(output.name)
            val manifestInfoOutputFile = project.computeManifestInfoOutputV1(output)
            val buildUuidProvider = project.newUuidProvider()
            project.tasks.register(taskName, BugsnagManifestUuidTask::class.java) {
                it.buildUuid.set(buildUuidProvider)
                it.variantOutput = output
                it.variant = variant
                it.manifestInfoProvider.set(manifestInfoOutputFile)
                val processManifest = output.processManifestProvider.orNull

                if (processManifest != null) {
                    processManifest.finalizedBy(it)
                    it.dependsOn(processManifest)
                }
            }.flatMap(BaseBugsnagManifestUuidTask::manifestInfoProvider)
        }
    }

    /**
     * Creates a bugsnag task to upload proguard mapping file
     */
    @Suppress("LongParameterList")
    private fun registerProguardUploadTask(
        project: Project,
        variant: ApplicationVariant,
        bugsnag: BugsnagPluginExtension,
        httpClientHelperProvider: Provider<out BugsnagHttpClientHelper>,
        manifestInfoFileProvider: Provider<RegularFile>,
        proguardUploadClientProvider: Provider<out UploadRequestClient>,
        mappingFilesProvider: Provider<FileCollection>
    ): TaskProvider<out BugsnagUploadProguardTask> {
        val outputName = taskNameForVariant(variant)
        val taskName = "uploadBugsnag${outputName}Mapping"
        val path = "intermediates/bugsnag/requests/proguardFor${outputName}.json"
        val requestOutputFileProvider = project.layout.buildDirectory.file(path)

        return BugsnagUploadProguardTask.register(project, taskName) {
            requestOutputFile.set(requestOutputFileProvider)
            httpClientHelper.set(httpClientHelperProvider)
            manifestInfoFile.set(manifestInfoFileProvider)
            uploadRequestClient.set(proguardUploadClientProvider)

            mappingFilesProvider.let {
                mappingFileProperty.from(it)
            }
            configureWith(bugsnag)
        }
    }

    @Suppress("LongParameterList")
    private fun registerSharedObjectUploadTask(
        project: Project,
        variant: ApkVariant,
        output: ApkVariantOutput,
        bugsnag: BugsnagPluginExtension,
        httpClientHelperProvider: Provider<out BugsnagHttpClientHelper>,
        manifestInfoFileProvider: Provider<RegularFile>,
        ndkUploadClientProvider: Provider<out UploadRequestClient>
    ): TaskProvider<out BugsnagUploadNdkTask> {
        // Create a Bugsnag task to upload NDK mapping file(s)
        val outputName = taskNameForOutput(output)
        val taskName = "uploadBugsnagNdk${outputName}Mapping"
        val path = "intermediates/bugsnag/requests/ndkFor${outputName}.json"
        val requestOutputFile = project.layout.buildDirectory.file(path)
        return BugsnagUploadNdkTask.register(project, taskName) {
            this.requestOutputFile.set(requestOutputFile)
            projectRoot.set(bugsnag.projectRoot.getOrElse(project.projectDir.toString()))
            variantOutput = output
            objDumpPaths.set(bugsnag.objdumpPaths)
            httpClientHelper.set(httpClientHelperProvider)
            manifestInfoFile.set(manifestInfoFileProvider)
            uploadRequestClient.set(ndkUploadClientProvider)
            configureWith(bugsnag)
            variant.externalNativeBuildProviders.forEach { provider ->
                searchDirectories.from(provider.map(ExternalNativeBuildTask::objFolder))
                searchDirectories.from(provider.map(ExternalNativeBuildTask::soFolder))
            }
        }
    }

    @Suppress("LongParameterList")
    private fun registerReleasesUploadTask(
        project: Project,
        variant: ApkVariant,
        output: ApkVariantOutput,
        bugsnag: BugsnagPluginExtension,
        manifestInfoFileProvider: Provider<RegularFile>,
        releasesUploadClientProvider: Provider<out UploadRequestClient>,
        mappingFilesProvider: Provider<FileCollection>?,
        checkSearchDirectories: Boolean
    ): TaskProvider<out BugsnagReleasesTask> {
        val outputName = taskNameForOutput(output)
        val taskName = "bugsnagRelease${outputName}Task"
        val path = "intermediates/bugsnag/requests/releasesFor${outputName}.json"
        val requestOutputFile = project.layout.buildDirectory.file(path)
        return BugsnagReleasesTask.register(project, taskName) {
            this.requestOutputFile.set(requestOutputFile)
            retryCount.set(bugsnag.retryCount)
            timeoutMillis.set(bugsnag.requestTimeoutMs)
            failOnUploadError.set(bugsnag.failOnUploadError)
            releasesEndpoint.set(bugsnag.releasesEndpoint)
            sourceControlProvider.set(bugsnag.sourceControl.provider)
            sourceControlRepository.set(bugsnag.sourceControl.repository)
            sourceControlRevision.set(bugsnag.sourceControl.revision)
            metadata.set(bugsnag.metadata)
            builderName.set(bugsnag.builderName)
            gradleVersion.set(project.gradle.gradleVersion)
            manifestInfoFile.set(manifestInfoFileProvider)
            uploadRequestClient.set(releasesUploadClientProvider)
            mappingFilesProvider?.let {
                jvmMappingFileProperty.from(it)
            }
            if (checkSearchDirectories) {
                variant.externalNativeBuildProviders.forEach { task ->
                    ndkMappingFileProperty.from(task.map { it.objFolder })
                    ndkMappingFileProperty.from(task.map { it.soFolder })
                }
            }
            configureMetadata()
        }
    }

    private fun shouldUploadMappings(
        output: ApkVariantOutput,
        bugsnag: BugsnagPluginExtension
    ): Boolean {
        return !output.name.toLowerCase().endsWith(
            "debug") || bugsnag.uploadDebugBuildMappings.get()
    }

    private fun isNdkUploadEnabled(bugsnag: BugsnagPluginExtension,
        android: AppExtension): Boolean {
        val usesCmake = android.externalNativeBuild.cmake.path != null
        val usesNdkBuild = android.externalNativeBuild.ndkBuild.path != null
        val default = usesCmake || usesNdkBuild
        return bugsnag.uploadNdkMappings.getOrElse(default)
    }

    fun taskNameForVariant(variant: ApkVariant): String {
        return variant.name.capitalize()
    }

    fun taskNameForOutput(output: ApkVariantOutput): String {
        return output.name.capitalize()
    }

    private fun computeManifestTaskNameFor(variant: String): String {
        return "processBugsnag${variant.capitalize()}Manifest"
    }

    private fun Project.computeManifestInfoOutputV2(variant: String): Provider<RegularFile> {
        val path = "intermediates/bugsnag/manifestInfoFor${variant.capitalize()}.json"
        return layout.buildDirectory.file(path)
    }

    private fun Project.computeManifestInfoOutputV1(
        output: ApkVariantOutput): Provider<RegularFile> {
        val path = "intermediates/bugsnag/manifestInfoFor${taskNameForOutput(output)}.json"
        return layout.buildDirectory.file(path)
    }

    private fun Project.newUuidProvider(): Provider<String> {
        return provider {
            UUID.randomUUID().toString()
        }
    }
}
