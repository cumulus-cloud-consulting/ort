/*
 * Copyright (C) 2017 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
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

package org.ossreviewtoolkit.analyzer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.WordSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.beEmpty
import io.kotest.matchers.collections.containExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.containExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beTheSameInstanceAs

import java.time.Instant

import org.ossreviewtoolkit.model.AnalyzerResult
import org.ossreviewtoolkit.model.DependencyGraph
import org.ossreviewtoolkit.model.DependencyReference
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageLinkage
import org.ossreviewtoolkit.model.PackageReference
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.RootDependencyIndex
import org.ossreviewtoolkit.model.Scope
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.ScopeExclude
import org.ossreviewtoolkit.model.config.ScopeExcludeReason
import org.ossreviewtoolkit.model.fromYaml
import org.ossreviewtoolkit.model.toYaml
import org.ossreviewtoolkit.model.yamlMapper

class AnalyzerResultBuilderTest : WordSpec() {
    private val issue1 = Issue(timestamp = Instant.EPOCH, source = "source-1", message = "message-1")
    private val issue2 = Issue(timestamp = Instant.EPOCH, source = "source-2", message = "message-2")
    private val issue3 = Issue(timestamp = Instant.EPOCH, source = "source-3", message = "message-3")
    private val issue4 = Issue(timestamp = Instant.EPOCH, source = "source-4", message = "message-4")
    private val issue5 = Issue(timestamp = Instant.EPOCH, source = "source-5", message = "message-5")

    private val package1 = Package.EMPTY.copy(id = Identifier("type-1", "namespace-1", "package-1", "version-1"))
    private val package2 = Package.EMPTY.copy(id = Identifier("type-2", "namespace-2", "package-2", "version-2"))
    private val package3 = Package.EMPTY.copy(id = Identifier("type-3", "namespace-3", "package-3", "version-3"))

    private val pkgRef1 = package1.toReference(issues = listOf(issue1))
    private val pkgRef2 = package2.toReference(
        dependencies = setOf(package3.toReference(issues = listOf(issue2)))
    )

    private val scope1 = Scope("scope-1", setOf(pkgRef1))
    private val scope2 = Scope("scope-2", setOf(pkgRef2))

    private val project1 = Project.EMPTY.copy(
        id = Identifier("type-1", "namespace-1", "project-1", "version-1"),
        scopeDependencies = setOf(scope1),
        definitionFilePath = "project1"
    )
    private val project2 = Project.EMPTY.copy(
        id = Identifier("type-2", "namespace-2", "project-2", "version-2"),
        scopeDependencies = setOf(scope1, scope2)
    )
    private val project3 = Project.EMPTY.copy(
        id = Identifier("type-1", "namespace-3", "project-1.2", "version-1"),
        scopeNames = setOf("scope-2"),
        scopeDependencies = null
    )

    private val dependencies1 = listOf(package1.id, package2.id)
    private val dependencies2 = listOf(package3.id)

    private val depRef1 = DependencyReference(0)
    private val depRef2 = DependencyReference(1)
    private val depRef3 = DependencyReference(0, issues = listOf(issue5))

    private val scopeMapping1 = mapOf(
        DependencyGraph.qualifyScope(project1.id, "scope-1") to listOf(RootDependencyIndex(0)),
        DependencyGraph.qualifyScope(project3.id, "scope-2") to listOf(RootDependencyIndex(1))
    )
    private val scopeMapping2 = mapOf(
        DependencyGraph.qualifyScope(project2.id, "scope-3") to listOf(RootDependencyIndex(0))
    )

    private val graph1 =
        DependencyGraph(
            dependencies1,
            sortedSetOf(DependencyGraph.DEPENDENCY_REFERENCE_COMPARATOR, depRef1, depRef2),
            scopeMapping1
        )
    private val graph2 =
        DependencyGraph(
            dependencies2,
            sortedSetOf(DependencyGraph.DEPENDENCY_REFERENCE_COMPARATOR, depRef3),
            scopeMapping2
        )

    private val analyzerResult1 = ProjectAnalyzerResult(
        project1, setOf(package1), listOf(issue3, issue4)
    )
    private val analyzerResult2 = ProjectAnalyzerResult(
        project2, setOf(package1, package2, package3), listOf(issue4)
    )

    init {
        "AnalyzerResult" should {
            "be serialized and deserialized correctly" {
                val mergedResults = AnalyzerResultBuilder()
                    .addResult(analyzerResult1)
                    .addResult(analyzerResult2)
                    .build()

                val serializedMergedResults = mergedResults.toYaml()
                val deserializedMergedResults = serializedMergedResults.fromYaml<AnalyzerResult>()

                deserializedMergedResults shouldBe mergedResults
            }

            "be serialized and deserialized correctly with a dependency graph" {
                val p1 = project1.copy(scopeDependencies = null, scopeNames = setOf("scope1"))
                val p2 = project2.copy(scopeDependencies = null, scopeNames = setOf("scope3"))
                val result = AnalyzerResult(
                    projects = setOf(p1, p2, project3),
                    packages = emptySet(),
                    dependencyGraphs = mapOf(
                        project1.id.type to graph1,
                        project2.id.type to graph2
                    )
                )

                val deserializedResult = result.toYaml().fromYaml<AnalyzerResult>()

                deserializedResult shouldBe result
            }

            "not change its representation when serialized again" {
                val p1 = project1.copy(scopeDependencies = null, scopeNames = setOf("scope1"))
                val p2 = project2.copy(scopeDependencies = null, scopeNames = setOf("scope3"))
                val serializedResult = AnalyzerResult(
                    projects = setOf(p1, p2, project3),
                    packages = emptySet(),
                    dependencyGraphs = mapOf(
                        project1.id.type to graph1,
                        project2.id.type to graph2
                    )
                ).toYaml()

                serializedResult.fromYaml<AnalyzerResult>().toYaml() shouldBe serializedResult
            }

            "use the dependency graph representation on serialization" {
                val mergedResults = AnalyzerResultBuilder()
                    .addResult(analyzerResult1)
                    .addResult(analyzerResult2)
                    .build()

                val serializedMergedResults = mergedResults.toYaml()
                val resultTree = yamlMapper.readTree(serializedMergedResults)

                resultTree["dependency_graphs"] shouldNotBeNull {
                    count() shouldBe 2
                }
            }

            "be serialized and deserialized correctly with an empty dependency graph" {
                val emptyGraph = DependencyGraph(packages = emptyList(), scopes = emptyMap())
                val p1 = project1.copy(scopeDependencies = null, scopeNames = setOf("scope1"))
                val p2 = project2.copy(scopeDependencies = null, scopeNames = setOf("scope3"))
                val result = AnalyzerResult(
                    projects = setOf(p1, p2, project3),
                    packages = emptySet(),
                    dependencyGraphs = mapOf(
                        project1.id.type to graph1,
                        project2.id.type to emptyGraph
                    )
                )

                val deserializedResult = result.toYaml().fromYaml<AnalyzerResult>()

                deserializedResult.withResolvedScopes() shouldBe result.withResolvedScopes()
            }
        }

        "collectIssues" should {
            "find all issues" {
                val analyzerResult = AnalyzerResultBuilder()
                    .addResult(analyzerResult1)
                    .addResult(analyzerResult2)
                    .addDependencyGraph("foo", graph2)
                    .build()

                analyzerResult.getAllIssues() should containExactly(
                    package1.id to setOf(issue1),
                    package3.id to setOf(issue2, issue5),
                    project1.id to setOf(issue3, issue4),
                    project2.id to setOf(issue4)
                )
            }
        }

        "withResolvedScopes" should {
            "return the same instance if no shared dependency graphs are present" {
                val analyzerResult = AnalyzerResultBuilder()
                    .addResult(analyzerResult1)
                    .addResult(analyzerResult2)
                    .build()
                    .withResolvedScopes()

                analyzerResult.withResolvedScopes() should beTheSameInstanceAs(analyzerResult)
            }

            "resolve the dependency information in affected projects" {
                val p1 = project1.copy(scopeDependencies = null, scopeNames = setOf("scope-1"))
                val p2 = project2.copy(scopeDependencies = null, scopeNames = setOf("scope-3"))
                val analyzerResult = AnalyzerResultBuilder()
                    .addResult(ProjectAnalyzerResult(p1, emptySet()))
                    .addDependencyGraph(p1.id.type, graph1)
                    .addResult(ProjectAnalyzerResult(project3, emptySet()))
                    .addResult(ProjectAnalyzerResult(p2, emptySet()))
                    .addDependencyGraph(p2.id.type, graph2)
                    .build()

                val resolvedResult = analyzerResult.withResolvedScopes()

                resolvedResult.dependencyGraphs.isEmpty() shouldBe true

                resolvedResult.projects.find { it.id == p1.id } shouldNotBeNull {
                    scopes shouldHaveSize 1
                    scopes.first().name shouldBe "scope-1"
                    scopes.first().dependencies shouldHaveSize 1
                    scopes.first().dependencies.first().id shouldBe package1.id
                }

                resolvedResult.projects.find { it.id == project3.id } shouldNotBeNull {
                    scopes shouldHaveSize 1
                    scopes.first().name shouldBe "scope-2"
                    scopes.first().dependencies shouldHaveSize 1
                    scopes.first().dependencies.first().id shouldBe package2.id
                }

                resolvedResult.projects.find { it.id == p2.id } shouldNotBeNull {
                    scopes shouldHaveSize 1
                    scopes.first().name shouldBe "scope-3"
                    scopes.first().dependencies shouldHaveSize 1
                    scopes.first().dependencies.first().id shouldBe package3.id
                }
            }
        }

        "AnalyzerResultBuilder" should {
            "merge results from all files" {
                val mergedResults = AnalyzerResultBuilder()
                    .addResult(analyzerResult1)
                    .addResult(analyzerResult2)
                    .build()

                mergedResults.withResolvedScopes().projects shouldBe setOf(
                    project1,
                    project2
                )
                mergedResults.packages shouldBe setOf(
                    package1,
                    package2,
                    package3
                )
                mergedResults.issues shouldBe mapOf(
                    project1.id to analyzerResult1.issues,
                    project2.id to analyzerResult2.issues
                )
            }

            "convert to the dependency graph representation when building" {
                val mergedResults = AnalyzerResultBuilder()
                    .addResult(analyzerResult1)
                    .addResult(analyzerResult2)
                    .build()

                mergedResults.dependencyGraphs.keys should containExactlyInAnyOrder("type-1", "type-2")
            }

            "apply scope excludes" {
                val scopeExclude = ScopeExclude("scope-2", ScopeExcludeReason.BUILD_DEPENDENCY_OF)
                val excludes = Excludes(scopes = listOf(scopeExclude))

                val mergedResults = AnalyzerResultBuilder()
                    .addResult(analyzerResult1)
                    .addResult(analyzerResult2)
                    .build(excludes)

                mergedResults.projects.forAll { project ->
                    project.scopeNames.orEmpty() shouldNotContain "scope-2"
                }
            }

            "throw if a result contains a project and a package with the same ID" {
                val packageWithProjectId = package1.copy(id = project1.id)

                shouldThrow<IllegalArgumentException> {
                    AnalyzerResultBuilder()
                        .addResult(analyzerResult1)
                        .addPackages(setOf(packageWithProjectId))
                        .build()
                }
            }

            "resolve package manager dependencies" {
                val packageManagerDependency = PackageManagerDependency(
                    packageManager = project1.id.type,
                    definitionFile = project1.definitionFilePath,
                    scope = scope1.name,
                    linkage = PackageLinkage.PROJECT_DYNAMIC
                ).toPackageReference()

                val scope = Scope(
                    name = "scope",
                    dependencies = setOf(
                        packageManagerDependency,
                        PackageReference(
                            id = package1.id,
                            dependencies = setOf(packageManagerDependency)
                        )
                    )
                )

                val project = Project.EMPTY.copy(
                    id = project2.id,
                    scopeDependencies = setOf(scope),
                    definitionFilePath = project2.definitionFilePath
                )

                val projectAnalyzerResult = ProjectAnalyzerResult(
                    project = project,
                    packages = setOf(package1)
                )

                val analyzerResult = AnalyzerResultBuilder().run {
                    addResult(projectAnalyzerResult)
                    addResult(analyzerResult1)
                    build()
                }

                analyzerResult.withResolvedScopes().apply {
                    projects.find { it.id == project.id } shouldNotBeNull {
                        scopes should containExactlyInAnyOrder(
                            Scope(
                                name = "scope",
                                dependencies = setOf(
                                    PackageReference(
                                        id = project1.id,
                                        linkage = PackageLinkage.PROJECT_DYNAMIC,
                                        dependencies = setOf(pkgRef1)
                                    ),
                                    PackageReference(
                                        id = package1.id,
                                        dependencies = setOf(
                                            PackageReference(
                                                id = project1.id,
                                                linkage = PackageLinkage.PROJECT_DYNAMIC,
                                                dependencies = setOf(pkgRef1)
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    }
                }
            }

            "handle a result without dependencies" {
                val emptyResult = AnalyzerResultBuilder().build()

                emptyResult.projects should beEmpty()
                emptyResult.packages should beEmpty()
            }
        }
    }
}
