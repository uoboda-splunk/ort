/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 * Copyright (C) 2021 Dr. Ing. h.c. F. Porsche AG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.analyzer.managers

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.ObjectNode

import com.vdurmont.semver4j.Requirement

import java.io.File
import java.net.URI
import java.util.SortedSet

import org.ossreviewtoolkit.analyzer.AbstractPackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.downloader.VcsHost
import org.ossreviewtoolkit.downloader.VersionControlSystem
import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.OrtIssue
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageReference
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.Scope
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.readValue
import org.ossreviewtoolkit.model.yamlMapper
import org.ossreviewtoolkit.utils.CommandLineTool
import org.ossreviewtoolkit.utils.normalizeVcsUrl
import org.ossreviewtoolkit.utils.textValueOrEmpty

/**
 * The [CocoaPods](https://cocoapods.org/) package manager for Objective-C.
 *
 * Steps to parse project with Podfile.lock
 *
 * 1. Parse Podfile.lock to get
 *      - Dependencies – Direct dependencies of this project
 *      - Pods – List with all Pods and their version and their dependencies with max depth of 1
 * 2. Recursively associate all pod-ids with Version and dependencies starting with first level Dependencies on
 * 3. Save everything in Scope and Analyzer Result
 */
class CocoaPods(
    name: String,
    analysisRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, analysisRoot, analyzerConfig, repoConfig), CommandLineTool {
    private var podSpecCache: MutableMap<String, Podspec> = mutableMapOf()
    private var issues: MutableList<OrtIssue> = mutableListOf()

    class Factory : AbstractPackageManagerFactory<CocoaPods>("CocoaPods") {
        override val globsForDefinitionFiles = listOf("Podfile.lock", "Podfile")

        override fun create(
            analysisRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = CocoaPods(managerName, analysisRoot, analyzerConfig, repoConfig)
    }

    override fun command(workingDir: File?) = "pod"

    override fun getVersionRequirement(): Requirement = Requirement.buildIvy("[1.10.1,)")

    override fun getVersionArguments(): String = "--version --allow-root"

    override fun beforeResolution(definitionFiles: List<File>) {
        // We need the version arguments which were included in https://github.com/CocoaPods/CocoaPods/pull/10609
        checkVersion(analyzerConfig.ignoreToolVersions)

        // Update the global specs repo so we can resolve new versions
        run("repo", "update", "--allow-root", workingDir = definitionFiles.first().parentFile)
    }

    override fun resolveDependencies(definitionFile: File): List<ProjectAnalyzerResult> {
        val workingDir = definitionFile.parentFile
        val projectInfo = getProjectInfoFromVcs(workingDir)
        val scope = Scope("dependencies", dependencies = getPackageRefs(definitionFile))
        val packages = scope.collectDependencies().map { getPackage(it, workingDir) }

        return listOf(
            ProjectAnalyzerResult(
                packages = packages.toSortedSet(),
                project = Project(
                    id = Identifier(
                        type = managerName,
                        namespace = "",
                        name = packageName(projectInfo.namespace, projectInfo.projectName),
                        version = projectInfo.revision.orEmpty()
                    ),
                    definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
                    authors = sortedSetOf(),
                    declaredLicenses = sortedSetOf(),
                    vcs = VcsInfo.EMPTY,
                    vcsProcessed = processProjectVcs(workingDir, VcsInfo.EMPTY),
                    scopeDependencies = sortedSetOf(scope),
                    homepageUrl = ""
                ),
                issues = issues
            )
        )
    }

    private fun getPackage(id: Identifier, workingDir: File): Package =
        lookupPodspec(id, workingDir).let { podSpec ->
            val vcs = podSpec.source["git"]?.let { url ->
                VcsInfo(
                    type = VcsType.GIT,
                    url = url,
                    revision = podSpec.source["tag"] ?: ""
                )
            } ?: VcsInfo.EMPTY

            Package(
                id = id,
                authors = sortedSetOf(),
                declaredLicenses = listOf(podSpec.license).toSortedSet(),
                description = podSpec.summary,
                homepageUrl = podSpec.homepage,
                binaryArtifact = RemoteArtifact.EMPTY,
                sourceArtifact = podSpec.source["http"]?.let { RemoteArtifact(it, Hash.NONE) } ?: RemoteArtifact.EMPTY,
                vcs = vcs,
                vcsProcessed = processPackageVcs(vcs, podSpec.homepage)
            )
        }

    private fun getProjectInfoFromVcs(workingDir: File): CocoapodsProjectInfo {
        val workingTree = VersionControlSystem.forDirectory(workingDir)
        val vcsInfo = workingTree?.getInfo() ?: VcsInfo.EMPTY
        val normalizedVcsUrl = normalizeVcsUrl(vcsInfo.url)
        val vcsHost = VcsHost.toVcsHost(URI(normalizedVcsUrl))

        return CocoapodsProjectInfo(
            namespace = vcsHost?.getUserOrOrganization(normalizedVcsUrl),
            projectName = vcsHost?.getProject(normalizedVcsUrl)
                ?: workingDir.relativeTo(analysisRoot).invariantSeparatorsPath,
            revision = vcsInfo.revision
        )
    }

    private fun lookupPodspec(id: Identifier, workingDir: File): Podspec {
        val topLevelSpecName = id.name.substringBefore("/")

        val podspecFile = run("spec", "which", topLevelSpecName, "--version=${id.version}", "--allow-root",
            "--regex", workingDir = workingDir
        ).requireSuccess().stdout.trim().let { File(it) }

        val podspec = podSpecCache.getOrPut(id.name) { podspecFile.readValue() }

        return podspec.withSubspecs().single { it.name == id.name }
    }
}

private data class CocoapodsProjectInfo(val namespace: String?, val projectName: String?, val revision: String?)

data class PodSubSpec(
    val name: String,
    val dependencies: Set<String>?
) {
    companion object Factory {
        fun createFromJson(json: JsonNode): PodSubSpec {
            return PodSubSpec(
                json["name"].textValue(),
                json["dependencies"]?.asIterable()?.map { it.textValue() }?.toSet() ?: setOf()
            )
        }
    }
}

private fun packageName(namespace: String?, name: String?): String =
    if (namespace.isNullOrBlank()) {
        name.orEmpty()
    } else {
        "${namespace.orEmpty()}/${name.orEmpty()}"
    }

private val NAME_AND_VERSION_REGEX = "([\\S]+)\\s+(.*)".toRegex()

private fun getPackageRefs(podfileLock: File): SortedSet<PackageReference> {
    val versionForName = mutableMapOf<String, String>()
    val dependenciesForName = mutableMapOf<String, MutableSet<String>>()
    val root = yamlMapper.readTree(podfileLock)

    root.get("PODS").asIterable().forEach { node ->
        val entry = when {
            node is ObjectNode -> node.fieldNames().asSequence().first()
            else -> node.textValue()
        }

        val (name, version) = NAME_AND_VERSION_REGEX.find(entry)!!.groups.let {
            it[1]!!.value to it[2]!!.value.removeSurrounding("(", ")")
        }
        versionForName[name] = version

        val dependencies = node[entry]?.map { it.textValue().substringBefore(" ") }.orEmpty()
        dependenciesForName.getOrPut(name) { mutableSetOf() } += dependencies
    }

    fun getPackageRef(name: String): PackageReference =
        PackageReference(
            id = Identifier("Pod", "", name, versionForName.getValue(name)),
            dependencies = dependenciesForName.getValue(name).mapTo(sortedSetOf()) { getPackageRef(it) }
        )

    return root.get("DEPENDENCIES").mapTo(sortedSetOf()) { node ->
        val name = node.textValue().substringBefore(" ")
        getPackageRef(name)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Podspec(
    val name: String = "",
    val version: String = "",
    @JsonDeserialize(using = LicenseDeserializer::class)
    val license: String = "",
    val summary: String = "",
    val homepage: String = "",
    val source: Map<String, String> = emptyMap(),
    private val subspecs: List<Podspec> = emptyList()
) {
    fun withSubspecs(): List<Podspec> {
        // TODO: beautify
        val result = mutableListOf<Podspec>()

        fun add(spec: Podspec, namePrefix: String) {
            val name = "$namePrefix${spec.name}"
            result += copy(name = "$namePrefix${spec.name}")
            spec.subspecs.forEach { add(it, "$name/") }
        }

        add(this, "")

        return result
    }
}

/**
 * Handle deserialization of the following two possible representations:
 *
 * 1. https://github.com/CocoaPods/Specs/blob/f75c24e7e9df1dac6ffa410a6fb30f01e026d4d6/Specs/8/5/e/SocketIOKit/2.0.1/SocketIOKit.podspec.json#L6-L9
 * 2. https://github.com/CocoaPods/Specs/blob/f75c24e7e9df1dac6ffa410a6fb30f01e026d4d6/Specs/8/5/e/FirebaseObjects/0.0.1/FirebaseObjects.podspec.json#L6
 */
private class LicenseDeserializer : StdDeserializer<String>(String::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): String {
        val node = parser.codec.readTree<JsonNode>(parser)

        return if (node.isTextual) {
            node.textValue()
        } else {
            node["type"].textValueOrEmpty()
        }
    }
}

fun main() {
    val file = File("/home/frank/.cocoapods/repos/trunk/Specs/0/1/9/MaterialComponents/124.2.0/")
        .resolve("MaterialComponents.podspec.json")

    val podSpec = file.readValue<Podspec>()

    val all = podSpec.withSubspecs().map { it.name }.sorted()

    println(all.joinToString("\n"))
}
