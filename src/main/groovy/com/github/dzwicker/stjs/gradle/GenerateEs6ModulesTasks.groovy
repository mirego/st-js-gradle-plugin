package com.github.dzwicker.stjs.gradle

import com.google.common.io.Files
import groovy.io.FileType
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

class GenerateEs6ModulesTasks extends DefaultTask {
    def File inputDir
    def File customJsDir
    def File generatedJsFolder
    def packageAliases = []

    def allClassesInfo
    def allClassesInfoByName
    def alreadyIncludedClasses
    def namespaces
    ArrayList<String> circularReferenceGuard

    @TaskAction
    void generateEs6Modules() {
        logger.info("inputDir: $inputDir")

        def classesFolder = new File(new File(inputDir, "classes"), "main")
        logger.info("classesFolder: $classesFolder")

        allClassesInfo = findAllClassInfo(classesFolder);
        replacePackageAliases(allClassesInfo);

        allClassesInfoByName = allClassesInfo.collectEntries {
            [it.class, it]
        }

        alreadyIncludedClasses = new HashSet<String>()

        generatedJsFolder.delete();
        generatedJsFolder.mkdirs();

        includeCustomJs()

        def hasCircularError = false;
        allClassesInfo.each { classInfo ->
            circularReferenceGuard = new ArrayList<String>()
            tryIncludeClass(classInfo)
        }
        if (hasCircularError) {
            throw new RuntimeException("Circular references exceptions. Please review the errors.")
        }
    }

    void includeCustomJs() {
        if (customJsDir != null) {
            customJsDir.eachFileRecurse(FileType.FILES) { file ->
                if (file.path.endsWith('.js')) {
                    def destFile = new File(generatedJsFolder, file.name)
                    Files.copy(file, destFile);
                }
            }
        }
    }

    void tryIncludeClass(classInfo) {
        if (!alreadyIncludedClasses.contains(classInfo.class)) {
            if (circularReferenceGuard.contains(classInfo.class)) {
                logger.error("Circular references exceptions: ${classInfo.class} --> $circularReferenceGuard")
                throw new RuntimeException("Circular references exceptions: ${classInfo.class} --> $circularReferenceGuard")
            }

            circularReferenceGuard.add(classInfo.class)
            classInfo.dependencies.each { dependencyClassName ->
                def depencyClassInfo = allClassesInfoByName[dependencyClassName]
                if (depencyClassInfo == null) {
                    logger.info('Skipping class because not found in compiled folder: ' + dependencyClassName)
                } else {
                    tryIncludeClass(depencyClassInfo)
                }
            }

            includeClass(classInfo)
        }
    }

    void includeClass(classInfo) {
        logger.info("Including class: $classInfo")
        alreadyIncludedClasses.add(classInfo.class)
        circularReferenceGuard.remove(classInfo.class)

        def classModuleDetails = findClassModuleDetails(classInfo.class);

        def destFile = new File(generatedJsFolder, classModuleDetails.fileModulePath + '.js');
        destFile.getParentFile().mkdirs();
        destFile.delete();

        def moduleAlias = [:];

        moduleAlias.put(
                classModuleDetails.classNameWithJsNamespace,
                classModuleDetails.simpleClassName);

        classInfo.dependencies.each {
            def dependencyClassModuleDetails = findClassModuleDetails(it);

            if (dependencyClassModuleDetails == null) {
                logger.warn("Not found dependency '$it' for ${classInfo.class}")
                destFile.append("import ${it} from 'NOT_FOUND_${it}';\n")
            } else {
                moduleAlias.put(
                        dependencyClassModuleDetails.classNameWithJsNamespace,
                        dependencyClassModuleDetails.simpleClassName);

                destFile.append("import ${dependencyClassModuleDetails.simpleClassName} from '${dependencyClassModuleDetails.fileModulePath}';\n")
            }
        }
        destFile.append("\n")

        String fileContent = new File(inputDir, "classes/main/" + classInfo.js).text
        moduleAlias.each {
            fileContent = fileContent.replaceAll((CharSequence)it.key, it.value);
        }

        // remove namespace declarations such:
        //    stjs.ns("stjs.Java");
        fileContent = fileContent.replaceAll(/stjs\.ns\(.*\);\n/, '')

        destFile.append(fileContent);

        destFile.append("\n\n")

        def simpleClassName = classInfo.class.tokenize('.').last();
        destFile.append("export default ${simpleClassName};")
    }

    Map findClassModuleDetails(String className) {

        def resolvedNamespace = "";
        def matchingNamespaceKey = "";
        namespaces.each() {
            String namespaceKey = it.key;

            if ((namespaceKey.length() > matchingNamespaceKey.length() && isPackageNamespaceMatches(className, namespaceKey)) || className.equals(namespaceKey)) {
                resolvedNamespace = it.value;
                matchingNamespaceKey = namespaceKey;
            }
        }

        def simpleClassName = className.tokenize('.').last()
        def classNameWithJsNamespace;
        if ("".equals(resolvedNamespace)) {
            classNameWithJsNamespace = simpleClassName;
        } else {
            classNameWithJsNamespace = resolvedNamespace + "." + simpleClassName;
        }
        def fileModulePath = classNameWithJsNamespace.replace('.', '/');

        return [
                simpleClassName: simpleClassName,
                classNameWithJsNamespace: classNameWithJsNamespace,
                fileModulePath: fileModulePath
        ];
    }

    boolean isPackageNamespaceMatches(String className, String namespace) {
        return (namespace.endsWith('.') && className.startsWith(namespace));
    }

    ArrayList findAllClassInfo(classesFolder) {
        def allClassesInfo = new ArrayList();

        classesFolder.eachFileRecurse(FileType.FILES) { file ->
            if (file.path.endsWith('.stjs')) {
                def classProps = new LinkedHashMap();
                file.eachLine { line ->
                    if (!line.startsWith('#')) {
                        def (name, value) = line.tokenize('=')

                        if (name.equals('dependencies')) {
                            // split dependencies
                            value = value.replace('[', '').replace(']', '')
                                    .replace(',', '\t')
                                    .replace('s\\:', '')
                                    .tokenize('\t');

                            // remove OTHER dependencies ("o:\")
                            value.removeAll {
                                it.startsWith('o\\:')
                            }
                        }

                        classProps[name] = value
                    }
                }

                classProps.js = classProps.js.replace('file\\:', '')
                allClassesInfo.add(classProps)
            }
        }

        return allClassesInfo;
    }

    void replacePackageAliases(allClassesInfo) {

        for (def classInfo : allClassesInfo) {
            classInfo.class = resolveClassAlias(classInfo.class)

            classInfo.dependencies = classInfo.dependencies.collect {
                return resolveClassAlias(it)
            }
        }
    }


    String resolveClassAlias(classString) {
        for (def classAliasMapEntry : packageAliases) {
            def initialClass = classString;
            classString = classString.replaceAll((CharSequence)(classAliasMapEntry.key), (CharSequence)(classAliasMapEntry.value))
            if (!initialClass.equals(classString)) {
                logger.info("Changed class for alias: ${initialClass} --> ${classString}")
            }
        }

        return classString;
    }

    List<String> splitOnFirst(String lookup, String str) {
        def idx = str.indexOf(lookup);
        if (idx < 0) {
            return [null, null];
        }

        def result = new ArrayList();
        result.add(str.substring(0, idx))
        result.add(str.substring(idx + lookup.length()))

        return result
    }

    def setNamespaces(Map<String, String> namespaces) {
        this.namespaces = namespaces;
    }
}
