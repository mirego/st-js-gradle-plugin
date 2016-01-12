package com.github.dzwicker.stjs.gradle

import groovy.io.FileType
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

class PackStjsTask extends DefaultTask {
    def File inputDir
    def File customJsDir
    def File generatedJsFolder
    def String packedFilename
    def String generatedJsListFilename
    def packageAliases = []

    def allClassesInfo
    def allClassesInfoByName
    def alreadyIncludedClasses
    ArrayList<String> circularReferenceGuard
    File generated_js_list
    File packed_file

    @TaskAction
    void pack() {
        logger.info("inputDir: $inputDir")

        def classesFolder = new File(new File(inputDir, "classes"), "main")
        logger.info("classesFolder: $classesFolder")

        allClassesInfo = findAllClassInfo(classesFolder);
        replacePackageAliases(allClassesInfo);

        allClassesInfoByName = allClassesInfo.collectEntries {
            [it.class, it]
        }

        alreadyIncludedClasses = new HashSet<String>()

        packed_file = new File(generatedJsFolder, packedFilename)
        packed_file.delete()

        includeCustomJs()

        if (!generatedJsListFilename) {
            generatedJsListFilename = 'generated-js-list.html';
        }
        generated_js_list = new File(generatedJsFolder, generatedJsListFilename)
        generated_js_list.delete()
        generated_js_list << '<script src="./stjs.js" type="text/javascript"></script>\n'

        def hasCircularError = false;
        allClassesInfo.each { classInfo ->
            circularReferenceGuard = new ArrayList<String>()
            try {
                tryIncludeClass(classInfo)
            } catch (RuntimeException e) {
                alreadyIncludedClasses.add(classInfo.class)

                hasCircularError = true;
            }
        }
        if (hasCircularError) {
            throw new RuntimeException("Circular references exceptions. Please review the errors.")
        }
    }

    void includeCustomJs() {
        if (customJsDir != null) {
            customJsDir.eachFileRecurse(FileType.FILES) { file ->
                if (file.path.endsWith('.js')) {
                    packed_file.append('\n')
                    packed_file.append('\n')
                    packed_file.append('// ------------------------------------------------------------------------\n')
                    packed_file.append("// ${file.name}\n")
                    packed_file.append('// ------------------------------------------------------------------------\n')

                    packed_file.append(new FileInputStream(file))
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
        generated_js_list << "<script src=\".${classInfo.js}\" type=\"text/javascript\"></script>\n"

        packed_file.append('\n')
        packed_file.append('\n')
        packed_file.append('// ------------------------------------------------------------------------\n')
        packed_file.append("// ${classInfo.js}\n")
        packed_file.append('// ------------------------------------------------------------------------\n')

        packed_file.append(new FileInputStream(new File(generatedJsFolder, classInfo.js)))
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
}
