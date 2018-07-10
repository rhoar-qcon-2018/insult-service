def ciProject = 'labs-ci-cd'
def testProject = 'labs-test'
def devProject = 'labs-dev'
openshift.withCluster() {
  openshift.withProject() {
    ciProject = openshift.project()
    testProject = ciProject.replaceFirst(/^labs-ci-cd/, 'labs-test')
    devProject = ciProject.replaceFirst(/^labs-ci-cd/, 'labs-dev')
  }
}

def buildConfig = { APP_NAME, PIPELINE_NAMESPACE, IMAGE_STREAM ->
  if (!IMAGE_STREAM) {
    IMAGE_STREAM = 'redhat-openjdk18-openshift:1.1'
  }
  openshift.withCluster() {
    openshift.apply(openshift.process(
      readFile(file: './.openshift/templates/vertx-build.yaml'), 
      '-p', "APP_NAME=${APP_NAME}", 
      '-p', "PIPELINE_NAMESPACE=${PIPELINE_NAMESPACE}", 
      '-p', "IMAGE_STREAM=${IMAGE_STREAM}"), 
      "--namespace=${PIPELINE_NAMESPACE}")
  }
}

def deploymentConfig = {APP_NAME, PIPELINE_NAMESPACE, NAMESPACE ->
  openshift.withCluster() {
    openshift.apply(openshift.process(
    readFile(file: './.openshift/templates/vertx-deploy.yaml'), 
    '-p', "APP_NAME=${APP_NAME}", 
    '-p', "PIPELINE_NAMESPACE=${PIPELINE_NAMESPACE}", 
    '-p', "NAMESPACE=${NAMESPACE}"), 
    "--namespace=${NAMESPACE}")
  }
}

pipeline {
  agent {
    label 'jenkins-slave-mvn'
  }
  environment {
    PROJECT_NAME = 'insult-service'
    KUBERNETES_NAMESPACE = "${ciProject}"
  }
  stages {
    stage('Quality And Security') {
      parallel {
        stage('OWASP Dependency Check') {
          steps {
            sh 'mvn -T 2 dependency-check:check'
          }
        }
        stage('Compile & Test') {
          steps {
            sh 'mvn -T 2 package'
          }
        }
        stage('Ensure SonarQube Webhook is configured') {
          when {
            expression {
              withSonarQubeEnv('sonar') {
                def retVal = sh(returnStatus: true, script: "curl -u \"${SONAR_AUTH_TOKEN}:\" http://sonarqube:9000/api/webhooks/list | grep Jenkins")
                echo "CURL COMMAND: ${retVal}"
                return (retVal > 0)
              }
            }
          }
          steps {
            withSonarQubeEnv('sonar') {
              sh "curl -X POST -u \"${SONAR_AUTH_TOKEN}:\" -F \"name=Jenkins\" -F \"url=http://jenkins/sonarqube-webhook/\" http://sonarqube:9000/api/webhooks/create"
            }
          }
        }
      }
    }
    stage('Wait for SonarQube Quality Gate') {
      steps {
        script {
          withSonarQubeEnv('sonar') {
            sh 'mvn -T 2 sonar:sonar'
          }
          def qualitygate = waitForQualityGate()
          if (qualitygate.status != "OK") {
            error "Pipeline aborted due to quality gate failure: ${qualitygate.status}"
          }
        }
      }
    }
    stage('OpenShift Deployments') {
      parallel {
        stage('Publish Artifacts') {
          steps {
            sh 'mvn package deploy:deploy -DskipTests -DaltDeploymentRepository=nexus::default::http://nexus:8081/repository/maven-snapshots/'
          }
        }
        stage('Create Binary BuildConfig') {
          steps {
            script {
              buildConfig(PROJECT_NAME, ciProject, UUID.randomUUID().toString())
            }
          }
        }
        stage('Create Test Deployment') {
          steps {
            script {
              deploymentConfig(PROJECT_NAME, ciProject, testProject)
            }
          }
        }
        stage('Create Dev Deployment') {
          steps {
            script {
              deploymentConfig(PROJECT_NAME, ciProject, devProject)
            }
          }
        }
      }
    }
    stage('Build Image') {
      steps {
        script {
          openshift.selector('bc', PROJECT_NAME).startBuild("--from-file=target/${PROJECT_NAME}.jar", '--wait')
        }
      }
    }
    stage('Promote to TEST') {
      steps {
        script {
          openshift.tag("${PROJECT_NAME}:latest", "${testProject}/${PROJECT_NAME}:latest")
        }
      }
    }
    stage('Promote to DEMO') {
      input {
        message "Promote service to DEMO environment?"
        ok "PROMOTE"
      }
      steps {
        script {
          openshift.tag("${PROJECT_NAME}:latest", "${devProject}/${PROJECT_NAME}:latest")
        }
      }
    }
  }
}