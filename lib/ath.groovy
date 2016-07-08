/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

/** Do not forget to change this */
def version = '1.0'

/**
 * Provides split branches for the Acceptance test harness.
 * Split size should be 7 + cucumber unless there are no test cases from previous runs to base the split on (then 1 + cucumber).
 * Assumes that {@link lib.functions#stashJenkinsWar} and {@link #stashAth()} has been run before this.
 *
 * @param functions lib/functions.groovy
 * @param nodeLabel the label to run the tests on, default = hi-speed
 * @param archiveJUnitReports if all the junit xml reports should be archived in a zip file for later, default = false
 * @param rerunFailingTestsCount number that enables rerun of failing tests. tests will be run until they pass or the number of reruns has been exhausted, default = 0 (rerun disabled)
 * @param parallelCount number of parallel branches to create. Default = 7
 * @return the "steps" to put inside a parallel step.
 */
Map getAthBranches(def functions, String nodeLabel = null, boolean archiveJUnitReports = false, int rerunFailingTestsCount = 0,
                   int parallelCount = 7) {
    if (nodeLabel == null) {
        nodeLabel = athLabel()
    }

    def splits = splitTests([$class: 'CountDrivenParallelism', size: parallelCount])

    String mvnFlags = "-Dsurefire.rerunFailingTestsCount=${rerunFailingTestsCount}"

    def branches = [:]

    for (int i = 0; i < splits.size(); i++) {
        def exclusions = new ArrayList(splits.get(i))

        final String splitId = 'split-' + i
        branches["ath-split-${i}"] = {
            this.singleAthBranch(functions, exclusions, mvnFlags + " -DskipCucumberTests=true", splitId, nodeLabel, archiveJUnitReports)
        }
    }

    branches['ath-split-cucumber'] = {
        this.singleAthBranch(functions, null, mvnFlags + " -Dcucumber.test=features", 'split-cucumber', nodeLabel, archiveJUnitReports)
    }
    return branches;
}

void singleAthBranch(def functions,
                     def exclusions,
                     String mvnProps,
                     String splitId,
                     String nodeLabel,
                     boolean archiveJUnitReports = false) {
    if (nodeLabel == null) {
        nodeLabel = athLabel()
    }
    
    node(nodeLabel) {
        unstash 'ath-stash'
        unstash 'jenkins.war'
        if (exclusions != null) {
            def excludeStr = exclusions.join("\n")
            writeFile file: 'excludes.txt', text: excludeStr
            echo excludeStr
        }
        timeout(time: 6, unit: TimeUnit.HOURS) { //TODO might need trimming
            wrap([$class: 'Xvnc', takeScreenshot: false, useXauthority: true]) {
                functions.withMaven {
                    sh 'mvn clean test -B -Dmaven.test.failure.ignore=true -DforkCount=1 ' + mvnProps
                }
            }
            try {
                step([$class: 'JUnitResultArchiver', testResults: 'target/surefire-reports/*.xml',
                     testDataPublishers: [[$class: 'AttachmentPublisher']]])

                if (archiveJUnitReports) {
                    //todo use zip step
                    sh "find . -path \"*/target/surefire-reports/*.xml\" | zip -@ junitReports-${splitId}.zip"
                    archive "junitReports-${splitId}.zip"
                }
            } catch (Exception ignored) {
                echo "No test reports found."
            }
            try {
                archive "**/target/diagnostics/**"
            } catch (Exception e) {
                echo "No diagnostics found."
            }
        }
    }
}

def athLabel() {
    return ATH_LABEL ?: "hi-speed"
}

def stashAth(def functions) {
    node(athLabel()) {
        checkout scm
        stash excludes: '.git/**', name: 'ath-stash'
    }
}

echo "loaded lib/ath.groovy $version"

return this
