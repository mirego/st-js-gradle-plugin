package com.github.dzwicker.stjs.gradle
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.logging.Logger
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.plugins.WarPlugin
import org.gradle.api.tasks.SourceSet
import org.stjs.generator.GeneratorConfigurationConfigParser

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

@SuppressWarnings("UnusedDeclaration")
public class StJsPlugin implements Plugin<Project> {
    private static final String TASK_GROUP = 'ST-JS'

    private GenerateStJsTask generateStJsTask;
    private Task inheritedPackedStjsCompileExtractionTask;
    private Task inheritedPackedStjsCompileCopyTask;
    private PackStjsTask packStjsTask;
    private GenerateEs6ModulesTasks generateEs6ModulesTask;

    @Override
    public void apply(final Project project) {
        // Add a configuration for inherited packed/transpiled assets
        def inheritedPackedStjsCompileConfiguration = project.configurations.create("inheritedPackedStjsCompile");
        inheritedPackedStjsCompileConfiguration.transitive = false;

        inheritedPackedStjsCompileExtractionTask = project.task('inheritedPackedStjsCompileExtraction',
                group: TASK_GROUP, description: 'Fetch the config.properties to merge with the current specified config as well as copying any packed.js file.') {
            outputs.dir project.file('build/' + project.configurations.inheritedPackedStjsCompile.name)
            outputs.upToDateWhen { false }
        } << {
            def destDir = project.file('build/' + project.configurations.inheritedPackedStjsCompile.name)
            project.configurations.inheritedPackedStjsCompile.asFileTree.each { depFile ->
                def zipFile = new ZipFile(depFile)

                Enumeration<? extends ZipEntry> entries = zipFile.entries();

                while (entries.hasMoreElements()) {
                    ZipEntry zipEntry = entries.nextElement();

                    def filePath = "${destDir}${File.separator}${zipEntry.name}"
                    if (zipEntry.isDirectory()) {
                        logger.debug("  creating: $filePath")
                        def dir = new File(filePath)
                        if (!dir.exists()) {
                            new File(filePath).mkdirs()
                        }
                    } else {
                        logger.debug(" inflating: $filePath")
                        new File(filePath).withOutputStream {
                            it << zipFile.getInputStream(zipEntry)
                        }
                    }
                }
                zipFile.close()
            }
        }

        boolean isForJavaPlugin = project.getPlugins().hasPlugin(JavaPlugin.class);
        boolean isForWarPlugin = project.getPlugins().hasPlugin(WarPlugin.class);

        if (isForWarPlugin) {
            throw new IllegalStateException("st-js plugin cannot be used by war plugin.");
        }

        Logger logger = project.getLogger();
        if (!isForJavaPlugin) {
            logger.error("st-js plugin can only be applied if jar plugin is applied!");
            throw new IllegalStateException("st-js plugin can only be applied if jar plugin is applied!");
        }

        JavaPluginConvention javaPluginConvention = project.getConvention().getPlugin(JavaPluginConvention.class);
        SourceSet main = javaPluginConvention.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceDirectorySet allJava = main.getAllJava();

        packStjsTask = project.task('packStjs', type: PackStjsTask, group: TASK_GROUP);
        generateEs6ModulesTask = project.task('generateEs6Modules', type: GenerateEs6ModulesTasks, group: TASK_GROUP)

        File generatedSourcesDirectory = main.getOutput().getClassesDir();
        File generatedResourcesDirectory = main.getOutput().resourcesDir;
        project.getTasks().getByPath(JavaPlugin.JAR_TASK_NAME).dependsOn(packStjsTask);

        packStjsTask.setInputDir(project.getBuildDir());
        packStjsTask.setGeneratedJsFolder(generatedSourcesDirectory);

        generateEs6ModulesTask.setInputDir(project.getBuildDir());
        generateEs6ModulesTask.setTempFolder(new File(project.rootDir, 'build/tmp/stjs-es6modules'));
        generateEs6ModulesTask.setDestFolder(generatedResourcesDirectory);

        inheritedPackedStjsCompileCopyTask = project.task('inheritedPackedStjsCompileCopy',
                group: TASK_GROUP, description: 'Copy the packed.js files found in the inherited packed dependency.') {
            inputs.dir inheritedPackedStjsCompileExtractionTask.outputs.files
            outputs.dir generatedSourcesDirectory
            outputs.upToDateWhen { false }
        } << this.&copyIneritedPackedFiles

        generateStJsTask = project.task('stjs', type: GenerateStJsTask, group: TASK_GROUP) << {
            generateEs6ModulesTask.setNamespaces(generateStJsTask.configuration.getNamespaces());
        }

        generateStJsTask.setClasspath(main.getCompileClasspath());
        generateStJsTask.setWar(isForWarPlugin);
        generateStJsTask.setGeneratedSourcesDirectory(generatedSourcesDirectory);
        generateStJsTask.setCompileSourceRoots(allJava);
        generateStJsTask.setOutput(main.getOutput());

        inheritedPackedStjsCompileCopyTask.dependsOn(inheritedPackedStjsCompileExtractionTask);
        generateStJsTask.dependsOn(inheritedPackedStjsCompileCopyTask);
        packStjsTask.dependsOn(generateStJsTask);
        generateEs6ModulesTask.dependsOn(generateStJsTask);
    }

    void copyIneritedPackedFiles(Task task) {
        def dest = task.outputs.files.iterator().next()
        dest.mkdir();
        task.logger.info("Copying inherited packed files to: " + dest.absolutePath)

        if (!task.inputs.files.empty) {
            def filteredFiles = task.inputs.files.asFileTree.filter { it.name.endsWith('-packed.js') }

            filteredFiles.iterator().each { from ->
                task.logger.info("Copying inherited packed files from: " + from.absolutePath)

                if (!from.isFile()) {
                    throw new IllegalStateException("Expected packed file is not a valid file.")
                }

                new File(dest, from.name).bytes = from.bytes
            }
        }

        // Set the inherited config only when we are in execution mode, not apply mode.
        generateStJsTask.setInheritedPackedConfigFilePath(getIneritedPackedConfigFile())
    }

    String getIneritedPackedConfigFile() {
        if (!inheritedPackedStjsCompileExtractionTask.outputs.files.empty) {
            def filteredFiles = inheritedPackedStjsCompileExtractionTask.outputs.files.asFileTree.filter {
                it.name.matches(GeneratorConfigurationConfigParser.CONFIG_PROPERTIES_RESOURCES_FILENAME)
            }

            return filteredFiles.iterator().hasNext() ? filteredFiles.first().absolutePath : null;
        }

        return null;
    }
}
