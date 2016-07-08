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

import com.cloudbees.groovy.cps.NonCPS
import org.jenkinsci.plugins.pipeline.utility.steps.conf.mf.SimpleManifest

/** Do not forget to change this */
def version = '1.0'

/**
 * Default Maven version.
 */
String defaultMaven() {
    return "Maven 3.x"
}

/**
 * Default JDK version
 */
String defaultJdk() {
    return "JDK 8u74"
}

/**
 * Add timestamps to all node logs.
 */
def timestampedNode(String label, Closure body) {
    node(label) {
        wrap([$class: 'TimestamperBuildWrapper']) {
            body();
        }
    }
}

/**
 * Unzips the version file in jenkins.war and parses it for the version string.
 * <strong>Side effect:</strong> A file called {@code jenkins-version.txt}
 * will be left in the cwd containing the same data.
 *
 * @param jenkinsWar path to jenkins.war relative to the current working directory.
 *
 * @return the version string
 */
String getJenkinsVersion(String jenkinsWar) {
    Map filesContent = unzip zipFile: jenkinsWar, read: true, glob: 'WEB-INF/classes/jenkins/model/jenkins-version.properties'
    if (!filesContent.isEmpty()) {
        Map props = readProperties text: filesContent.values().iterator().next()
        String version = props['version']
        if (version != null && version.trim() != '') {
            return version.trim()
        }
    }
    //OSS most likely
    SimpleManifest manifest = readManifest file: jenkinsWar
    String s = manifest.main['Jenkins-Version']
    return s != null ? s.trim() : null;

}

/**
 * Downloads and stashes jenkins.war for further use in the flow.
 * The name of the stash will be {@code "jenkins.war"}.
 * @param url the url to download
 *              {@code 'mvn://...'} will be executed via maven (and requires the environment parameter),
 *              Example: {@code 'mvn://org.jenkins-ci.main:jenkins-war:1.609.1:war'}.
 *              {@code 'artifact://' will be downloaded via CopyArtifact build step},
 *              Example: {@code 'artifact://full/path/to/job/buildNr#artifact.ext'}.
 *              Anything else will be downloaded via wget
 * @param label The label to run on. Defaults to 'hi-speed'.
 * @param getVersion if the version should be extracted and added to the build description. default = true
 */
void stashJenkinsWar(String url,
                     String label = 'hi-speed',
                     boolean getVersion = true) { 
    timestampedNode(label) { 
        ws('warDown') {
            sh 'rm -rf *'
            if (url == null) {
                error 'required parameter url is missing'
            } else if (url.startsWith("mvn://")) {
                echo "Fetching ${url} as Maven artifact."
                this.withMaven {
                    String dependency = url.substring(6)
                    if (!dependency.endsWith(":war")) {
                        dependency += ":war"
                    }
                    sh "mvn -B -s settings.xml dependency:copy -Dartifact=${dependency} -DoutputDirectory=./ -Dmdep.stripVersion=true"
                    sh 'mv `ls *.war` jenkins.war'
                }
            } else if (url.startsWith("artifact://")) {
                echo "Fetching ${url} as Jenkins artifact."
                def comp = this.getComponentsFromArtifactUrl(url)
                step([$class: 'CopyArtifact', filter: comp.artifact, projectName: comp.item, selector: [$class: 'SpecificBuildSelector', buildNumber: comp.run]])
                if (comp.artifact != 'jenkins.war') {
                    sh "mv ${comp.artifact} jenkins.war"
                }
            } else if (url.startsWith("stable://")) {
                echo "Fetching ${url} as Jenkins artifact."
                def comp = this.getComponentsFromLatestStableUrl(url)
                step([$class: 'CopyArtifact', filter: comp.artifact, projectName: comp.item, selector: [$class: 'StatusBuildSelector', stable: true]])
                if (comp.artifact != 'jenkins.war') {
                    sh "mv ${comp.artifact} jenkins.war"
                }
            } else {
                echo "Fetching ${url} as URL file."
                sh "wget -q -O jenkins.war ${url}"
            }
            if (!unzip(zipFile: 'jenkins.war', test: true)) {
                error('jenkins.war seems to be corrupted.')
            }
            step([$class: 'Fingerprinter', targets: 'jenkins.war'])
            if (getVersion) {
                String version = this.getJenkinsVersion('jenkins.war')
                if (currentBuild.description == null) {
                    currentBuild.description = version
                } else {
                    currentBuild.description += version
                }
            }
            stash includes: 'jenkins.war', name: 'jenkins.war'
            echo 'war stashed as jenkins.war'
        }
    }
}

/**
 * Extracts the components from an {@code 'artifact://full/path/to/job/buildNr#artifact.ext'} type url.
 * - or {@code 'artifact://full/path/to/job#artifact.ext'}, which switches to latest stable build.
 * @param url the url
 */
@NonCPS
def getComponentsFromArtifactUrl(String url) {
    def pattern = /^artifact:\/\/([\/\w\-_ \.]+)\/(\d+)\/{0,1}#([\/\w\.\-_]+)$/
    def matcher = url =~ pattern
    if (matcher) {
        return [
            item: matcher.group(1),
            run: matcher.group(2),
            artifact: matcher.group(3)
        ]
    } else {
        throw new MalformedURLException("Expected format: 'artifact://full/path/to/job/buildNr#dir/artifact.ext' but got '${url}'")
    }
}

/**
 * Extracts the components from an {@code 'stable://full/path/to/job#artifact.ext'} URL, which switches to latest stable build.
 * @param url the url
 */
@NonCPS
def getComponentsFromLatestStableUrl(String url) {
    def buileNumberPattern = /^stable:\/\/([\/\w\-_ \.]+)#([\/\w\.\-_]+)$/
    def matcher = url =~ pattern
    if (matcher) {
        return [
            item: matcher.group(1),
            artifact: matcher.group(2)
        ]
    } else {
        throw new MalformedURLException("Expected format: 'stable://full/path/to/job#dir/artifact.ext' but got '${url}'")
    }
}

// This method sets up the Maven and JDK tools, puts them in the environment along
// with whatever other arbitrary environment variables we passed in, and runs the
// body we passed in within that environment.
void withMavenEnv(String mavenName = null, String jdkName = null, List envVars = [], def body) {
    if (mavenName == null) {
        mavenName = defaultMaven()
    }

    if (jdkName == null) {
        jdkName = defaultJdk()
    }
    
    // Using the "tool" Pipeline call automatically installs those tools on the
    // node.
    String mvntool = tool name: mavenName, type: 'hudson.tasks.Maven$MavenInstallation'
    String jdktool = tool name: jdkName, type: 'hudson.model.JDK'

    // Set JAVA_HOME, MAVEN_HOME and special PATH variables for the tools we're
    // using.
    List mvnEnv = ["PATH+MVN=${mvntool}/bin", "PATH+JDK=${jdktool}/bin", "JAVA_HOME=${jdktool}", "MAVEN_HOME=${mvntool}"]

    // Add any additional environment variables.
    mvnEnv.addAll(envVars)

    // Invoke the body closure we're passed within the environment we've created.
    withEnv(mvnEnv) {
        body.call()
    }
}

echo "loaded functions.groovy $version"

return this
