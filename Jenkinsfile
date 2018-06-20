def ciProject = 'labs-ci-cd'
def testProject = 'labs-dev'
def devProject = 'labs-test'

pipeline {
  agent {
    label 'jenkins-slave-mvn'
  }
  environment {
    PROJECT_NAME = 'insult-service'
    KUBERNETES_NAMESPACE = 'labs-ci-cduser1'
  }
  stages {
    stage('Define Variables') {
      steps {
        script {
          openshift.withCluster() {
            ciProject = openshift.project()
            testProject = ciProject.replaceFirst(/^labs-ci-cd/, 'labs-test')
            devProject = ciProject.replaceFirst(/^labs-ci-cd/, 'labs-dev')
          }
        }
      }
    }
    stage('Quality And Security') {
      parallel {
        stage('OWASP Dependency Check') {
          steps {
            sh 'mvn -T 2 dependency-check:check'
          }
        }
        stage('Compile & Test') {
          steps {
            sh 'mvn -T 2 package vertx:package'
          }
        }
        stage('Ensure SonarQube Webhook is configured') {
          when {
            not {
              expression {
                withSonarQubeEnv('sonar') {
                  sh "curl -u \"${SONAR_AUTH_TOKEN}:\" http://sonarqube:9000/api/webhooks/list | grep Jenkins"
                }
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
    stage('OpenShift ImageStreams') {
      parallel {
        stage('CICD Env ImageStream') {
          steps {
            script {
              openshift.apply("""
---
apiVersion: v1
kind: ImageStream
metadata:
  labels:
    build: '${PROJECT_NAME}'
  name: '${PROJECT_NAME}'
  namespace: '${ciProject}'
spec: {}""")
            }
          }
        }
        stage('Test Env ImageStream') {
          steps {
            script {
              openshift.apply("""
---
apiVersion: v1
kind: ImageStream
metadata:
  labels:
    build: '${PROJECT_NAME}'
  name: '${PROJECT_NAME}'
  namespace: '${testProject}'
spec: {}""")
            }
          }
        }
        stage('Dev Env ImageStream') {
          steps {
            script {
              openshift.apply("""
---
apiVersion: v1
kind: ImageStream
metadata:
  labels:
    build: '${PROJECT_NAME}'
  name: '${PROJECT_NAME}'
  namespace: '${devProject}'
spec: {}""")
            }
          }
        }
      }
    }
    stage('OpenShift Deployments') {
      parallel {
        stage('Publish Artifacts') {
          steps {
            sh 'mvn package vertx:package deploy:deploy -DskipTests -DaltDeploymentRepository=nexus::default::http://nexus:8081/repository/maven-snapshots/'
          }
        }
        stage('Create Binary BuildConfig') {
          steps {
            script {
              openshift.apply("""
---
apiVersion: v1
kind: List
objects:
- apiVersion: v1
  kind: BuildConfig
  """)
            }
          }
        }
        stage('Create Test Deployment') {
          when {
            not {
              expression {
                openshift.withProject(testProject) {
                  return openshift.selector('dc', PROJECT_NAME).exists()
                }
              }
            }
          }
          steps {
            script {
              sh "oc new-app --namespace=${testProject} --name=${PROJECT_NAME} --allow-missing-imagestream-tags=true --image-stream=${PROJECT_NAME}"
            }
          }
        }
        stage('Create Demo Deployment') {
          when {
            not {
              expression {
                openshift.withProject(devProject) {
                  return openshift.selector('dc', PROJECT_NAME).exists()
                }
              }
            }
          }
          steps {
            script {
              sh "oc new-app --namespace=${devProject} --name=${PROJECT_NAME} --allow-missing-imagestream-tags=true --image-stream=${PROJECT_NAME}"
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
    stage('Web Security Analysis') {
      steps {
        agent {
          label 'jenkins-slave-zap'
        }
        script {
          sh "/zap/zap-baseline.py -r baseline.html -t http://${PROJECT_NAME}-${testProject}.apps.qcon.openshift.opentlc.com/"
          publishHTML([
                  allowMissing: false, alwaysLinkToLastBuild: false,
                  keepAll: true, reportDir: '/zap/wrk', reportFiles: 'baseline.html',
                  reportName: 'ZAP Baseline Scan', reportTitles: 'ZAP Baseline Scan'
          ])
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