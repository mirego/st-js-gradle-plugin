package com.github.dzwicker.stjs.gradle
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.logging.Logger
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.plugins.WarPlugin
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSet
import org.stjs.generator.GeneratorConfigurationConfigParser

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

@SuppressWarnings("UnusedDeclaration")
public class StJsPlugin implements Plugin<Project> {
    private static final String TASK_GROUP = 'ST-JS'

    private GenerateStJsTask generateStJsTask;
    private Task inheritedPackedStjsCompileExtractionTask;
    private Task babelRuntimeTask;
    private Task npmBuildTask;
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

            generateStJsTask.setInheritedPackedConfigFilePath(getIneritedPackedConfigFile())
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

        packStjsTask.setInputDir(project.getBuildDir());
        packStjsTask.setGeneratedJsFolder(generatedSourcesDirectory);

        def es6TempFolder = new File(project.getBuildDir(), 'tmp/stjs-es6modules');
        generateEs6ModulesTask.setInputDir(project.getBuildDir());
        generateEs6ModulesTask.setTempFolder(es6TempFolder);
        generateEs6ModulesTask.setDestFolder(generatedResourcesDirectory);

        generateStJsTask = project.task('stjs', type: GenerateStJsTask, group: TASK_GROUP) << {
            generateEs6ModulesTask.setNamespaces(generateStJsTask.configuration.getNamespaces());
        }

        generateStJsTask.setClasspath(main.getCompileClasspath());
        generateStJsTask.setWar(isForWarPlugin);
        generateStJsTask.setGeneratedSourcesDirectory(generatedSourcesDirectory);
        generateStJsTask.setCompileSourceRoots(allJava);
        generateStJsTask.setOutput(main.getOutput());

        generateStJsTask.dependsOn(inheritedPackedStjsCompileExtractionTask);
        packStjsTask.dependsOn(generateStJsTask);
        generateEs6ModulesTask.dependsOn(generateStJsTask);

        babelRuntimeTask = project.task('babelRuntime', type: Exec, group: TASK_GROUP,
                description: 'Prepare babel runtime for transpilation to ES6 modules.') {
            workingDir es6TempFolder
            commandLine 'npm'
            args = ['run', 'generate-runtime']
        }
        babelRuntimeTask.dependsOn(generateEs6ModulesTask);

        npmBuildTask = project.task('npmBuild', type: Exec, group: TASK_GROUP,
                description: 'Run npm build that will create a single file to the resources/main folder. This is an ES6 module enabled file that contains all the classes from your transpilation process.') {
            workingDir es6TempFolder
            commandLine 'npm'
            args = ['run', 'build']
        }
        npmBuildTask.dependsOn(babelRuntimeTask);

        project.getTasks().getByPath(JavaPlugin.JAR_TASK_NAME).dependsOn(npmBuildTask);
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
