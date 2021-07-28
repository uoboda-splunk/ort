/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
package org.ossreviewtoolkit.helper.commands

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file

import java.io.File
import java.io.FileOutputStream
import java.lang.RuntimeException
import java.math.BigInteger
import java.net.URL
import java.security.MessageDigest
import java.util.*

import org.ossreviewtoolkit.helper.commands.Result.Entry
import org.ossreviewtoolkit.helper.common.readOrtResult
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.OrtResult
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageLinkage
import org.ossreviewtoolkit.model.PackageReference
import org.ossreviewtoolkit.model.yamlMapper
import org.ossreviewtoolkit.utils.expandTilde

/*
#!/bin/bash

ort_home=/home/frank/devel/ort/ort

cwd=$(pwd) ; cd $ort_home && ./gradlew installDist ; cd $cwd

orth="$ort_home/helper-cli/build/install/orth/bin/orth"

$orth package-stats \
  --distributed-projects-ort-file-urls distributed-project-urls.txt \
  --non-distributed-projects-ort-file-urls non-distributed-project-urls.txt \
  --target-dir output

 */
class PackageStatsCommand : CliktCommand() {
    private val distributedProjectsOrtFileUrls by option(
        "--distributed-projects-ort-file-urls"
    ).convert { it.expandTilde() }
        .file(mustExist = true, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = false)
        .convert { it.absoluteFile.normalize() }
        .required()

    private val nonDistributedProjectsOrtFileUrls by option(
        "--non-distributed-projects-ort-file-urls",
    ).convert { it.expandTilde() }
        .file(mustExist = true, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = false)
        .convert { it.absoluteFile.normalize() }
        .required()

    private val targetDir by option(
        "--target-dir"
    ).convert { it.expandTilde() }
        .file(mustExist = false, canBeFile = false, canBeDir = true, mustBeWritable = false, mustBeReadable = false)
        .convert { it.absoluteFile.normalize() }
        .required()

    private val failedUrls = mutableSetOf<String>()

    private val skippedPackages = mutableSetOf<Identifier>()

    override fun run() {
        if (!targetDir.exists()) targetDir.mkdirs()

        val cacheDir = targetDir.resolve("cache").apply { mkdirs() }

        val pkgsForDistributedProjects = getPackageEntries(distributedProjectsOrtFileUrls, cacheDir)
        val pkgsForNonDistributedProjects = getPackageEntries(nonDistributedProjectsOrtFileUrls, cacheDir)

        val distributedPackages = pkgsForDistributedProjects.values.filter {
            !it.isExcluded
        }.mapTo(mutableSetOf()) { it.id }

        val nonDistributedPackages = pkgsForDistributedProjects.values.filter {
            it.isExcluded
        }.mapTo(mutableSetOf()) { it.id } + pkgsForNonDistributedProjects.values.filter {
            it.id !in distributedPackages
        }.mapTo(mutableSetOf()) { it.id }

        val allPackages = (distributedPackages + nonDistributedPackages).associateWith {
            val a = pkgsForDistributedProjects[it]
            val b = pkgsForNonDistributedProjects[it]

            when {
                a != null && b != null -> a.merge(b)
                a != null -> a
                b != null -> b
                else -> throw RuntimeException()
            }
        }

        val result = Result(
            distributedPackages = distributedPackages.map { allPackages.getValue(it) }.sort(),
            nonDistributedPackages = nonDistributedPackages.map { allPackages.getValue(it) }.sort(),
            failedUrls = failedUrls.toList().sorted(),
            skippedPackages = skippedPackages.toSortedSet()
        )

        if (failedUrls.isNotEmpty()) {
            println("The following ORT files could not be de-serialized:")
            println()
            println(failedUrls.sorted().joinToString(separator = "\n  ", prefix = "  "))
            println()
        }

        val resultFile = targetDir.resolve("result.yml")
        resultFile.writeText(result.toYaml())
        println("Found ${result.distributedPackages.size} distributed and " +
                "${result.nonDistributedPackages.size} non-distributed packages.")
        println("Result written to '${resultFile.absolutePath}'.")
    }

    private fun getPackageEntries(urlsFile: File, cacheDir: File): Map<Identifier, Entry> {
        val result = mutableMapOf<Identifier, Entry>()
        val urls = urlsFile.readText().lines().filter { it.isNotBlank() }

        urls.forEachIndexed { i, url ->
            println("[$i / ${urls.size}] GET $url")
            val ortResult = getOrtFile(url, cacheDir) ?: run {
                failedUrls += url
                return@forEachIndexed
            }

            val linkages = ortResult.getPackageLinkages()

            ortResult.getPackages(omitExcluded = false).forEach { curatedPackage ->
                if (Here.isProprietaryHerePackage(curatedPackage.pkg)) {
                    skippedPackages += curatedPackage.pkg.id
                    return@forEach
                }

                val entry = Entry(
                    id = curatedPackage.pkg.id,
                    isExcluded = ortResult.isExcluded(curatedPackage.pkg.id),
                    isModified = false,
                    declaredLicense = curatedPackage.pkg.declaredLicensesProcessed.spdxExpression?.toString() ?: "NONE",
                    linkage = linkages[curatedPackage.pkg.id].orEmpty().toSortedSet(),
                    downloadLink = curatedPackage.pkg.vcsProcessed.url.takeIf { it.isNotBlank() }
                        ?: curatedPackage.pkg.sourceArtifact.url
                )

                result[entry.id] = result[entry.id]?.merge(entry) ?: entry
            }
        }

        return result
    }

    @Suppress("SwallowedException")
    private fun getOrtFile(url: String, cacheDir: File): OrtResult? {
        val cacheFile = cacheDir.resolve("${md5sum(url)}/${url.substringAfterLast("/")}")
        println("  -> ${cacheFile.absolutePath}, inCache=${cacheFile.isFile}")

        if (!cacheFile.isFile) download(url, cacheFile)

        return try {
            readOrtResult(cacheFile)
        } catch (e: UnrecognizedPropertyException) {
            println("  De-serialization failed!")
            null
        }
    }
}

private data class Result(
    val distributedPackages: List<Entry>,
    val nonDistributedPackages: List<Entry>,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val failedUrls: List<String>,
    val skippedPackages: SortedSet<Identifier>,
) {
    val summary: Summary = Summary(
        excludedPackages = distributedPackages.count { it.isExcluded } + nonDistributedPackages.count { it.isExcluded },
        nonDistributedPackages = nonDistributedPackages.size,
        totalPackages = distributedPackages.size + nonDistributedPackages.size,
        failedUrls = failedUrls.size
    )

    fun toYaml(): String = yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this)

    data class Entry(
        val id: Identifier,
        @JsonIgnore
        val isExcluded: Boolean,
        val isModified: Boolean,
        val declaredLicense: String,
        val linkage: SortedSet<PackageLinkage>,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        val downloadLink: String,
    ) {
        fun merge(other: Entry): Entry {
            require(id == other.id)

            return copy(
                isExcluded = isExcluded && other.isExcluded,
                isModified = isModified || other.isModified,
                declaredLicense = declaredLicense.takeIf { it != "NONE"} ?: other.declaredLicense,
                linkage = (linkage + other.linkage).toSortedSet(),
                downloadLink = downloadLink.takeIf { it.isNotBlank() } ?: other.downloadLink
            )
        }
    }

    data class Summary (
        val excludedPackages: Int,
        val nonDistributedPackages: Int,
        val totalPackages: Int,
        val failedUrls: Int
    )
}

private fun Collection<Entry>.sort(): List<Entry> = sortedBy { it.id }

private fun download(url: String, targetFile: File) {
    targetFile.parentFile.mkdirs()

    URL(url).openStream().use { input ->
        FileOutputStream(File(targetFile.absolutePath)).use { output ->
            input.copyTo(output)
        }
    }

    targetFile.parentFile.resolve("url.txt").writeText(url)
}

private fun md5sum(text: String): String {
    val md = MessageDigest.getInstance("MD5")
    return BigInteger(1, md.digest(text.toByteArray())).toString(16).padStart(32, '0')
}

private fun OrtResult.getPackageLinkages(): Map<Identifier, MutableSet<PackageLinkage>> {
    val result = mutableMapOf<Identifier, MutableSet<PackageLinkage>>()

    fun visit(ref: PackageReference) {
        result.getOrPut(ref.id) { mutableSetOf() } += ref.linkage
    }

    getProjects(omitExcluded = false).forEach { project ->
        project.scopes.forEach { scopes ->
            scopes.dependencies.forEach { pkgRef ->
                visit(pkgRef)
            }
        }
    }

    return result
}

private object Here {
    val hereOrgs = arrayOf(
        "advancedtelematic",
        "here",
        "navigation",
        "navteq",
        "traffic"
    )

    fun isProprietaryHerePackage(pkg: Package): Boolean {

        if (pkg.id.type in setOf("NPM", "Yarn")
            && pkg.sourceArtifact.url.contains("artifactory.in.here.com")
            && pkg.vcsProcessed.url.isEmpty()
        ) {
            return true
        }

        val vcsUrl = pkg.vcsProcessed.url

        return when {
            vcsUrl.contains("gerrit.it.here.com") && !vcsUrl.contains("external") -> true
            vcsUrl.contains("gitlab-ee-poc.jenkins.release.in.here.com") -> true
            vcsUrl.contains("hth.in.here.com") -> true
            vcsUrl.contains("hth-ext.it.here.com") -> true
            vcsUrl.contains("main.gitlab.in.here.com") && !vcsUrl.endsWith("/hnod/nodejs.git") -> true
            vcsUrl.contains("olp-gitlab.in.here.com") -> true
            vcsUrl.contains("rep-gerrit-ams.it.here.com") && !vcsUrl.contains("external") -> true
            vcsUrl.isBlank() && pkg.id.isFromOrg(*hereOrgs) -> true
            else -> false
        }
    }
}
