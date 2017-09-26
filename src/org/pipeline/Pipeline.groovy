#!/usr/bin/groovy
package org.pipeline;

// Check KubeCtl Connectivity
def kubectlTest() {
    println "Checking kubectl Connnectivity To The API"
    sh "kubectl get nodes"
}

// Perform Checks Helm Chart Code
def helmLint(String chart_dir) {
    // Lint Helm Code
    println "Running Helm Lint Check On ${chart_dir}"
    sh "helm lint ${chart_dir}"
}

// Setup Helm Client And Check Version
def helmConfig() {
    println "Initiliazing Helm Client"
    sh "helm init"
    println "Checking Client/Server Version(s)"
    sh "helm version"
}

// Deploy Helm Chart To The Environment
def helmDeploy(Map args) {
    // Configure Helm Client And Confirm Tiller Process Is Installed
    helmConfig()

    def String namespace

    // If No Namespace Passed, Just Use The Name
    if (args.namespace == null) {
        namespace = args.name
    } else {
        namespace = args.namespace
    }

    // Dry Run?
    if (args.dry_run) {
        println "Running Dry-Run Test Deployment"
        sh "helm upgrade --dry-run --install ${args.name} ${args.chart_dir} --set imageTag=${args.version_tag},replicas=${args.replicas},cpu=${args.cpu},memory=${args.memory},ingress.hostname=${args.hostname} --namespace=${namespace}"
    } else {
        println "Running Deployment"
        // reimplement --wait once it works reliable
        sh "helm upgrade --install ${args.name} ${args.chart_dir} --set imageTag=${args.version_tag},replicas=${args.replicas},cpu=${args.cpu},memory=${args.memory},ingress.hostname=${args.hostname} --namespace=${namespace}"

        // sleeping until --wait works reliably
        sleep(20)

        // Message For The User About A Status Check
        echo "Application ${args.name} Successfully Deployed. Use helm status ${args.name} To Check."
    }
}

// Delete A Helm Application From K8s
def helmDelete(Map args) {
        println "Running Helm Delete On ${args.name}"
        sh "helm delete --purge ${args.name}"
}

// Run Helm Test On Application
def helmTest(Map args) {
    println "Running Helm test"
    sh "helm test ${args.name} --cleanup"
}

// Set Git Environment Variables
def gitEnvVars() {
    println "Setting Git Environment Variables"

    env.GIT_COMMIT_ID = sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%H'").trim()
    env.GIT_REMOTE_URL = sh(returnStdout: true, script: "git config --get remote.origin.url").trim()
    env.GIT_SHA = env.GIT_COMMIT_ID.substring(0, 7)

    println "env.GIT_COMMIT_ID => ${env.GIT_COMMIT_ID}"
    println "env.GIT_SHA => ${env.GIT_SHA}"
    println "env.GIT_REMOTE_URL => ${env.GIT_REMOTE_URL}"
}

// Build And Publish Docker Container
def containerBuildPub(Map args) {
    println "Running Docker Build/Publish: ${args.host}/${args.acct}/${args.repo}:${args.tags}"

    docker.withRegistry("https://${args.host}", "${args.auth_id}") {
        sh "docker build --build-arg VCS_REF=${env.GIT_SHA} --build-arg VCS_URL=${env.GIT_REMOTE_URL} --build-arg BUILD_DATE=`date -u +'%Y-%m-%dT%H:%M:%SZ'` -t ${args.acct}/${args.repo} ${args.dockerfile}"
        def img = docker.image("${args.acct}/${args.repo}")
        for (int i = 0; i < args.tags.size(); i++) {
            // img.push(args.tags.get(i))
            sh "docker tag ${args.acct}/${args.repo} ${args.host}/${args.acct}/${args.repo}:${args.tags.get(i)}"
            sh "docker push ${args.host}/${args.acct}/${args.repo}:${args.tags.get(i)}"
        }
        return img.id
    }
}

// Get List Of Tags For A Docker Container
def getContainerTags(config, Map tags = [:]) {
    println "Getting List Of Tags For Container"
    def String commit_tag
    def String version_tag

    try {
        // If PR Branch Tag Only Has A Branch Name
        if (env.BRANCH_NAME.contains('PR')) {
            commit_tag = env.BRANCH_NAME
            tags << ['commit': commit_tag]
            return tags
        }
    } catch (Exception e) {
        println "WARNING: Commit Unavailable From Environment. ${e}"
    }

    // Set Commit Tag
    try {
        // If Branch Available, Use As Prefix, Otherwise Only Commit Hash
        if (env.BRANCH_NAME) {
            commit_tag = env.BRANCH_NAME + '-' + env.GIT_COMMIT_ID.substring(0, 7)
        } else {
            commit_tag = env.GIT_COMMIT_ID.substring(0, 7)
        }
        tags << ['commit': commit_tag]
    } catch (Exception e) {
        println "WARNING: Commit Unavailable From Environment. ${e}"
    }

    // Set Master Tag
    try {
        if (env.BRANCH_NAME == 'master') {
            tags << ['master': 'latest']
        }
    } catch (Exception e) {
        println "WARNING: Branch Unavailable From Environment. ${e}"
    }

    // Set Develop Tag
    try {
        if (env.BRANCH_NAME == 'develop') {
            tags << ['develop': 'develop']
        }
    } catch (Exception e) {
        println "WARNING: Branch Unavailable From Environment. ${e}"
    }

    // Only Set Build Tag If None Of The Above Are Available
    if (!tags) {
        try {
            tags << ['build': env.BUILD_TAG]
        } catch (Exception e) {
            println "WARNING: Build Tag Unavailable From config.project. ${e}"
        }
    }

    return tags
}

// Set Container Registry Account To Use
def getContainerRepoAcct(config) {
    println "Setting Container Registry Credentials According To Branch"
    def String acct

    if (env.BRANCH_NAME == 'master') {
        acct = config.container_repo.master_acct
    } else {
        acct = config.container_repo.alt_acct
    }

    return acct
}

@NonCPS
// Jenkins And Workflow Restriction Force This Function Instead Of map.values()
// see: https://issues.jenkins-ci.org/browse/JENKINS-27421
def getMapValues(Map map=[:]) {
    def entries = []
    def map_values = []

    entries.addAll(map.entrySet())

    for (int i=0; i < entries.size(); i++){
        String value =  entries.get(i).value
        map_values.add(value)
    }

    return map_values
}
