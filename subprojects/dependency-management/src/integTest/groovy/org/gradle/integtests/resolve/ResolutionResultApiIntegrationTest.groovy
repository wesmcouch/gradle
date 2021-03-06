/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */



package org.gradle.integtests.resolve

import org.gradle.integtests.fixtures.AbstractDependencyResolutionTest
import org.gradle.integtests.fixtures.FeaturePreviewsFixture
import org.gradle.integtests.fixtures.FluidDependenciesResolveRunner
import org.gradle.integtests.fixtures.resolve.ResolveTestFixture
import org.junit.runner.RunWith

@RunWith(FluidDependenciesResolveRunner)
class ResolutionResultApiIntegrationTest extends AbstractDependencyResolutionTest {
    ResolveTestFixture resolve = new ResolveTestFixture(buildFile, 'conf')

    /*
    The ResolutionResult API is also covered by the dependency report integration tests.
     */

    def "selection reasons are described"() {
        given:
        mavenRepo.module("org", "leaf", "1.0").publish()
        mavenRepo.module("org", "leaf", "2.0").publish()
        mavenRepo.module("org", "foo", "0.5").publish()

        mavenRepo.module("org", "foo", "1.0").dependsOn('org', 'leaf', '1.0').publish()
        mavenRepo.module("org", "bar", "1.0").dependsOn('org', 'leaf', '2.0').publish()
        mavenRepo.module("org", "baz", "1.0").dependsOn('org', 'foo',  '1.0').publish()

        file("settings.gradle") << "rootProject.name = 'cool-project'"

        file("build.gradle") << """
            version = '5.0'
            repositories {
                maven { url "${mavenRepo.uri}" }
            }
            configurations {
                conf
            }
            configurations.conf.resolutionStrategy.force 'org:leaf:2.0'
            dependencies {
                conf 'org:foo:0.5', 'org:bar:1.0', 'org:baz:1.0'
            }
            task resolutionResult {
                doLast {
                    def result = configurations.conf.incoming.resolutionResult
                    result.allComponents {
                        if(it.id instanceof ModuleComponentIdentifier) {
                            println it.id.module + ":" + it.id.version + " " + it.selectionReason.description
                        }
                        else if(it.id instanceof ProjectComponentIdentifier) {
                            println it.moduleVersion.name + ":" + it.moduleVersion.version + " " + it.selectionReason.description
                        }
                    }
                }
            }
        """

        when:
        run "resolutionResult"

        then:
        output.contains """
cool-project:5.0 root
foo:1.0 conflict resolution
leaf:2.0 forced
bar:1.0 requested
baz:1.0 requested
"""
    }

    def "resolution result API gives access to dependency reasons in case of conflict"() {
        given:
        mavenRepo.with {
            def leaf1 = module('org.test', 'leaf', '1.0').publish()
            def leaf2 = module('org.test', 'leaf', '1.1').publish()
            module('org.test', 'a', '1.0')
                .dependsOn(leaf1, reason: 'first reason')
                .withModuleMetadata()
                .publish()
            module('org.test', 'b', '1.0')
                .dependsOn(leaf2, reason: 'second reason')
                .withModuleMetadata()
                .publish()

        }
        FeaturePreviewsFixture.enableGradleMetadata(file("gradle.properties"))

        when:
        file("build.gradle") << """
            configurations {
                conf
            }
            
            repositories {
               maven { url "${mavenRepo.uri}" }
            }
            
            dependencies {
                conf 'org.test:a:1.0'
                conf 'org.test:b:1.0'
            }
            
            task checkDeps {
                doLast {
                    def result = configurations.conf.incoming.resolutionResult
                    result.allComponents {
                        if (it.id instanceof ModuleComponentIdentifier && it.id.module == 'leaf') {
                            def selectionReason = it.selectionReason
                            assert selectionReason.conflictResolution
                            def descriptions = selectionReason.descriptions.reverse()
                            assert descriptions.size() > 1
                            descriptions.each {
                                println "\$it.cause : \$it.description"
                            }
                            def descriptor = descriptions.find { it.cause == ComponentSelectionCause.REQUESTED }
                            assert descriptor?.description == 'second reason'
                        }
                    }
                }
            }

        """

        then:
        run "checkDeps"
    }

    // TODO CC: Ideally, we should also keep the "rule applied" reason, but the infrastructure doesn't let us do this yet
    def "resolution result API gives access to dependency reasons in case of conflict and selection by rule"() {
        given:
        mavenRepo.with {
            def leaf1 = module('org.test', 'leaf', '1.0').publish()
            def leaf2 = module('org.test', 'leaf', '1.1').publish()
            module('org.test', 'a', '1.0')
                .dependsOn('org.test', 'leaf', '0.9')
                .withModuleMetadata()
                .publish()
            module('org.test', 'b', '1.0')
                .dependsOn(leaf2, reason: 'second reason')
                .withModuleMetadata()
                .publish()

        }
        FeaturePreviewsFixture.enableGradleMetadata(file("gradle.properties"))
        file('settings.gradle') << """rootProject.name='test'"""
        file("build.gradle") << """
            configurations {
                conf {
                    resolutionStrategy {
                        dependencySubstitution {
                            all {
                                if (it.requested instanceof ModuleComponentSelector) {
                                    if (it.requested.module == 'leaf' && it.requested.version == '0.9') {
                                        it.useTarget group: 'org.test', name: it.requested.module, version: '1.0'
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            repositories {
               maven { url "${mavenRepo.uri}" }
            }
            
            dependencies {
                conf 'org.test:a:1.0'
                conf 'org.test:b:1.0'
                
            }
        """
        resolve.prepare()
        buildFile << """           
            checkDeps {
                doLast {
                    def result = configurations.conf.incoming.resolutionResult
                    result.allComponents {
                        if (it.id instanceof ModuleComponentIdentifier && it.id.module == 'leaf') {
                            def selectionReason = it.selectionReason
                            assert selectionReason.conflictResolution
                            def descriptions = selectionReason.descriptions.reverse()
                            assert descriptions.size() > 1
                            descriptions.each {
                                println "\$it.cause : \$it.description"
                            }
                            def descriptor = descriptions.find { it.cause == ComponentSelectionCause.REQUESTED }
                            assert descriptor?.description == 'second reason'
                        }
                    }
                }
            }
        """

        when:

        run "checkDeps"

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module('org.test:a:1.0:runtime') {
                    edge('org.test:leaf:0.9', 'org.test:leaf:1.1')
                        .byConflictResolution() // conflict with the version requested by 'b'
                        .byReason('second reason') // this comes from 'b'
                        // ideally, should also have a reason for the dependency upgrade from 0.9 to 1.0
                }
                module('org.test:b:1.0:runtime') {
                    module('org.test:leaf:1.1').byConflictResolution().byReason('second reason')
                }
            }
        }
    }
}
