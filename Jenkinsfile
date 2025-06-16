pipeline {

    environment {
        DEV_CLUSTER = "${env.DEV_K8S_CLUSTER}"
        DEV_CLUSTER_CRED_ID = "${env.DEV_K8S_CLUSTER_CRED_ID}"
        JAVA_21_HOME = '/usr/local/java21'
    }

    agent any
    stages {
        stage('Initialize') {
            steps {
                script {
                    env.JAVA_HOME = env.JAVA_21_HOME
                    echo "Using Java version: 21"
                }
            }
        }

        stage('Add Global Env Variables') {
            steps {
                script {
                        readYaml(file: 'jenkins_values.yml').each { key, value ->
                        env[key] = value
                    }
                }
            }
        }

        stage('Pipeline Parameters') {
            steps {
                script {
                    timeout(time: 60, unit: 'SECONDS') {
                        INPUT_PARAMS = input (message: 'Please select the build parameters', parameters: [choice(choices: env.job_namespace.replaceAll("\\s","").split(',') as List, name: 'NAMESPACE'), choice(choices: ['Yes', 'No'], name: 'Deploy_Image'), choice(choices: ['Yes', 'No'], name: 'Skip_Sonar_Ananlysis'), choice(choices: ['Yes', 'No'], name: 'Skip_Quality_Gate'), choice(choices: ['Yes', 'No', 'DEFINITELY'], name: 'TRIGGER_INTEGRATION_TESTS')])
                        echo "The Selected Parameters are: ${INPUT_PARAMS}"
                        INPUT_PARAMS.each { item ->
                            env[item.key] = item.value
                        }
                        if (env.NAMESPACE.endsWith('-prod') && !env.TAG_NAME) {
                            error "Only release tags can be deployed in the prod environment. Selected namespace: ${env.NAMESPACE}, but no release tag found."
                        }
                    }
                }
            }
        }

        stage('Build') {
            steps {
                withCredentials([file(credentialsId: 'setting_xml', variable: 'SECRET_FILE')]) {
                    sh "cp $SECRET_FILE settings.xml"

                    // Set Gradle Wrapper to 6.4.1 and build
                    sh './gradlew wrapper --gradle-version 8.13 --distribution-type all -Pprofile=dev'
                    sh "./gradlew -Dorg.gradle.jvmargs=\"-Xms1g -Xmx3g -XX:MaxMetaspaceSize=1g\" -Dkotlin.daemon.jvmargs=\"-Xms512m -Xmx2g -XX:MaxMetaspaceSize=1g\" clean build -x test -x asciidoctor -x integrationTest -Pprofile=dev --no-daemon --max-workers 1"

                    // Stash artifacts
                    stash includes: '**/build/libs/*.jar', name: 'app'
                }
            }
            // post {
                // always {
                    // script {
                        // def xmlFilesExist = sh(
                        // script: 'find build/test-results/test -type f -name "*.xml" | grep -q .',
                        // returnStatus: true
                        // ) == 0

                        // if (xmlFilesExist) {
                            // junit 'build/test-results/test/**/*.xml'
                        // } else {
                           // echo "No test reports found. Skipping JUnit step."
                        // }
                    // }
                // }
            // }
        }

        stage('Sonar Analysis'){
            when {
                expression {
                    return env.Skip_Sonar_Ananlysis != "Yes";
                }
            }
            steps{
                withSonarQubeEnv('sonarqube-9.1') {
                   sh "mvn sonar:sonar"
                }
            }
        }

        stage('Quality Gate Analysis') {
            when {
                expression {
                    return env.Skip_Quality_Gate != "Yes";
                }
            }
            steps {
                sleep(60)
                script {
                    def sonarAnalysis = waitForQualityGate()
                    if(sonarAnalysis.status != 'OK') {
                       slackSend (message: " '${env.JOB_NAME} [${env.BUILD_NUMBER}]' pipeline aborted due to quality gate failure: ${sonarAnalysis.status}")
                       waitForQualityGate abortPipeline: true
                    }
                }
            }
        }

        stage('Build with Kaniko') {
            agent {
                kubernetes {
                    yaml """
                    kind: Pod
                    spec:
                      containers:
                      - name: kaniko
                        image: gcr.io/kaniko-project/executor:debug
                        imagePullPolicy: Always
                        command:
                        - sleep
                        args:
                        - 9999999
                        tty: true
                        volumeMounts:
                          - name: jenkins-docker-cfg
                            mountPath: /kaniko/.docker
                      volumes:
                      - name: jenkins-docker-cfg
                        projected:
                          sources:
                          - secret:
                              name: default-secret
                              items:
                                - key: .dockerconfigjson
                                  path: config.json
                    """
                }
            }

            steps {
                script {
                    env.org_name = "${helm_release_name}".replace('-', '_')
                    env.docker_tag = env.TAG_NAME ? "swr.eu-de.otc.t-systems.com/${org_name}/${env.projectName}:${env.TAG_NAME}" : "swr.eu-de.otc.t-systems.com/${org_name}/${env.projectName}:${env.BRANCH_NAME.replaceAll("/","-")}-${env.BUILD_NUMBER}"
                }
                container(name: 'kaniko', shell: '/busybox/sh') {
                    unstash 'app'
                    sh '/kaniko/executor --context `pwd` --dockerfile ./docker/Dockerfile --cache=true --destination "${docker_tag}"'
                }

                script {
                    withCredentials([usernamePassword(credentialsId: 'oli-system-umair', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                        script {
                            env.encodedPass=URLEncoder.encode(GIT_PASSWORD, "UTF-8")
                            env.updateVersion=env.docker_tag.substring(env.docker_tag.lastIndexOf(':') + 1, env.docker_tag.length())
                        }
                        dir("${helm_chart_dir}"){
                            git branch: 'main', credentialsId: 'oli-system-umair', url: "https://${helm_chart_repo}"
                            if (env.TAG_NAME) {
                                sh "sed -i 's/${env.projectName}:.*/${env.projectName}:${env.updateVersion}/g' envirnoments/*/values.yaml"
                            }
                            else {
                                sh "sed -i 's/${env.projectName}:.*/${env.projectName}:${env.updateVersion}/g' envirnoments/${env.NAMESPACE}/values.yaml"
                            }
                            sh '''
                                if [ -n "$(git status --porcelain)" ]; then
                                    git add .
                                    git config user.email muhammad.umair@my-oli.com
                                    git config user.name muhammadumairsabir
                                    git commit -m "Triggered By Build: ${JOB_NAME} - ${BUILD_NUMBER}"
                                    git push https://${GIT_USERNAME}:${encodedPass}@${helm_chart_repo}.git
                                else
                                  echo "no changes";
                                fi
                            '''
                        }
                    }
                }
            }
        }

        stage('Deploy'){
            when {
                expression {
                    return env.Deploy_Image == 'Yes' && (
                        (!env.NAMESPACE.endsWith('-prod') && env.BRANCH_NAME) ||
                        (env.NAMESPACE.endsWith('-prod') && env.TAG_NAME)
                    )
                }
            }
            steps{
                script{
                    script {
                        dir("${helm_chart_dir}") {
                            git branch: 'main', credentialsId: 'oli-system-umair', url: "https://${helm_chart_repo}"
                        }
                    }
                    withKubeConfig([credentialsId: "${DEV_CLUSTER_CRED_ID}", serverUrl: "${DEV_CLUSTER}"]){
                        script{
                            try {
                                sh """
                                    helm upgrade ${helm_release_name} ./${helm_chart_dir}/charts/${env.chartName} \
                                        --install \
                                        --namespace ${env.NAMESPACE} \
                                        --create-namespace \
                                        --values ./${helm_chart_dir}/envirnoments/${env.NAMESPACE}/values.yaml \
                                        --set ${deploy_image_key}.image=${env.docker_tag.replace('swr.eu-de.otc.t-systems.com', '100.125.7.25:20202')}
                                    """
                            } catch (Exception e) {
                                error "Could not deploy branch artifact for ${env.projectName}"
                            }
                        }
                    }
                }
            }
        }

        stage('Build Artifacts') {
            steps {
                script {
                    archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true
                }
            }
        }


        stage('Trigger integration tests'){
            when {
                expression {
                    ( (env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master' || env.BRANCH_NAME == 'main' || env.BRANCH_NAME.toLowerCase().contains('release') || env.BRANCH_NAME.toLowerCase().contains('hotfix')) && env.NAMESPACE.contains('-int') && env.TRIGGER_INTEGRATION_TESTS=='Yes'  || env.TRIGGER_INTEGRATION_TESTS=='DEFINITELY');
                }
            }
            steps {
                script{

                       try{
                            if (env.it_test_type == 'cypress'){
                            withCredentials([file(credentialsId: env.cypress_config_file, variable: 'cypressconfig')]) {
                                dir('IntegrationTest') {
                                    sh 'sleep 60'
                                    sh 'npm install'
                                    sh "cp \$cypressconfig ./cypress.env.json"

                                    sh 'npm test'
                                }
                            }
                        }
                        else {
                            if(CheckBranch(jenkins.model.Jenkins.instance.getItem("integration-tests"), env.BRANCH_NAME ))
                            {
                                def branchName = "${env.BRANCH_NAME}".replace('/','%2F')
                                build job: "integration-tests/${branchName}", propagate: true, wait: true, parameters: [
                                    string(name: 'NAMESPACE', value: "${env.NAMESPACE}")
                                ]
                            }
                            else{
                                build job: 'integration-tests/master', propagate: true, wait: true, parameters: [
                                    string(name: 'NAMESPACE', value: "${env.NAMESPACE}")
                                ]
                            }
                        }
                        }
                        catch(Exception e){
                            error "Integration tests failed"
                        }
                }
            }
            post {
                always {
                    script {
                        def xmlFilesExist = sh(
                        script: 'find IntegrationTest/results -type f -name "*.xml" | grep -q .',
                        returnStatus: true
                        ) == 0

                        if (xmlFilesExist) {
                            junit 'IntegrationTest/results/*.xml'
                        } else {
                           echo "No test reports found. Skipping JUnit step."
                        }
                    }
                }
            }
        }
    }
    options {
        ansiColor('xterm')
        office365ConnectorWebhooks([
            [name: "Jenkins-Alerts", url: "https://myoli.webhook.office.com/webhookb2/7a91dc14-98b4-4ba8-8d48-a18df5bfa022@b0960f1d-8a33-4f4a-82f0-78fafb621ca4/IncomingWebhook/e52bd29364ef4ab79b817a516f016e21/5c074767-d147-4309-bc53-22f4c76f6145", notifyBackToNormal: true, notifyFailure: true, notifyRepeatedFailure: true, notifySuccess: true, notifyAborted: true]
        ])
        buildDiscarder(logRotator(numToKeepStr: "10"))
    }
    post {
        always {
            cleanWs()
        }
    }
}

def CheckBranch(project, branchname){
    def check = false
    project.getItems().each { job ->
        if (branchname == job.getProperty(org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty.class).getBranch().getName().toString()){
            check = true
            return
        }
    }
    return check
}

def determineJavaVersion() {
    def gradleFileContent = sh(script: 'cat build.gradle.kts', returnStdout: true).trim()

    def javaVersionMatcher = gradleFileContent =~ /java\.sourceCompatibility\s*=\s*JavaVersion\.VERSION_(\d+_\d+)/
    return javaVersionMatcher ? javaVersionMatcher[0][1].replace('_', '.').toDouble() : 11
}
