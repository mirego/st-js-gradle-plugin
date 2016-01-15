package com.github.dzwicker.stjs.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.logging.Logger
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.plugins.WarPlugin
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceSet
import org.stjs.generator.GeneratorConfigurationConfigParser

@SuppressWarnings("UnusedDeclaration")
public class StJsPlugin implements Plugin<Project> {
    private static final String TASK_GROUP = 'ST-JS'

    private GenerateStJsTask generateStJsTask;
    private Task inheritedPackedStjsCompileExtractionTask;
    private Task inheritedPackedStjsCompileCopyTask;
    private PackStjsTask packStjsTask;

    @Override
    public void apply(final Project project) {
        // Add a configuration for inherited packed/transpiled assets
        def inheritedPackedStjsCompileConfiguration = project.configurations.create("inheritedPackedStjsCompile");
        inheritedPackedStjsCompileConfiguration.transitive = false;

        inheritedPackedStjsCompileExtractionTask = project.task('inheritedPackedStjsCompileExtraction', type: Copy,
                group: TASK_GROUP, description: 'Fetch the config.properties to merge with the current specified config as well as copying any packed.js file.') {
            project.afterEvaluate {
                from {
                    inheritedPackedStjsCompileConfiguration.asFileTree.each {
                        from project.zipTree(it)
                    }
                    // Don't include the actual archives themselves
                    null
                }
            }
            into project.file('build/' + project.configurations.inheritedPackedStjsCompile.name)
        }

        boolean isForJavaPlugin = project.getPlugins().hasPlugin(JavaPlugin.class);
        boolean isForWarPlugin = project.getPlugins().hasPlugin(WarPlugin.class);

        Logger logger = project.getLogger();
        if (!isForJavaPlugin && !isForWarPlugin) {
            logger.error("st-js plugin can only be applied if jar or war plugin is applied, too!");
            throw new IllegalStateException("st-js plugin can only be applied if jar or war plugin is applied, too!");
        }

        JavaPluginConvention javaPluginConvention = project.getConvention().getPlugin(JavaPluginConvention.class);
        SourceSet main = javaPluginConvention.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceDirectorySet allJava = main.getAllJava();
        if (allJava.getSrcDirs().size() != 1) {
            throw new IllegalStateException("Only a single source directory is supported!");
        }

        packStjsTask = project.task('packStjs', type: PackStjsTask, group: TASK_GROUP);

        File generatedSourcesDirectory;
        if (isForWarPlugin) {
            generatedSourcesDirectory = new File(project.getBuildDir(), "stjs");
            project.getTasks().getByPath(WarPlugin.WAR_TASK_NAME).dependsOn(packStjsTask);
        } else {
            generatedSourcesDirectory = main.getOutput().getClassesDir();
            project.getTasks().getByPath(JavaPlugin.JAR_TASK_NAME).dependsOn(packStjsTask);
        }

        packStjsTask.setInputDir(project.getBuildDir());
        packStjsTask.setGeneratedJsFolder(generatedSourcesDirectory);

        inheritedPackedStjsCompileCopyTask = project.task('inheritedPackedStjsCompileCopy',
                group: TASK_GROUP, description: 'Copy the packed.js files found in the inherited packed dependency.') {
            inputs.dir inheritedPackedStjsCompileExtractionTask.outputs.files
            outputs.dir generatedSourcesDirectory
            outputs.upToDateWhen { false }
        } << this.&copyIneritedPackedFiles

        generateStJsTask = project.task('stjs', type: GenerateStJsTask, group: TASK_GROUP);
        generateStJsTask.setClasspath(main.getCompileClasspath());
        generateStJsTask.setWar(isForWarPlugin);
        generateStJsTask.setGeneratedSourcesDirectory(generatedSourcesDirectory);
        generateStJsTask.setCompileSourceRoots(allJava);
        generateStJsTask.setOutput(main.getOutput());

        inheritedPackedStjsCompileCopyTask.dependsOn(inheritedPackedStjsCompileExtractionTask);
        generateStJsTask.dependsOn(inheritedPackedStjsCompileCopyTask);
        packStjsTask.dependsOn(generateStJsTask);
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
