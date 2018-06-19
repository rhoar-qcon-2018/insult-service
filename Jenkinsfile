pipeline {
  agent {
    label 'jenkins-slave-mvn'
  }
  environment {
    PROJECT_NAME = 'insult-service'
  }
  stages {
    stage('Quality And Security') {
      parallel {
        stage('OWASP Dependency Check') {
          steps {
            agent {
              label "jenkins-slave-mvn"
            }
            sh 'mvn dependency-check:check'
          }
        }
        stage('Compile & Test') {
          steps {
            sh 'mvn package vertx:package'
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
              sh "curl -X POST -u \"${SONAR_AUTH_TOKEN}:\" -F \"name=Jenkins\" -F \"url=http://jenkins/sonarqube-webhook/\" http://sonarqube:9000/api/webhooks/update"
            }
          }
        }
        stage('Quality Analysis') {
          steps {
            script {
              withSonarQubeEnv('sonar') {
                sh 'mvn sonar:sonar'
                def qualitygate = waitForQualityGate()
                if (qualitygate.status != "OK") {
                  error "Pipeline aborted due to quality gate failure: ${qualitygate.status}"
                }
              }
            }
          }
        }
      }
    }
    stage('OpenShift Configuration') {
      parallel {

        stage('Create Binary BuildConfig') {
          when {
            expression {
              openshift.withCluster() {
                return !openshift.selector('bc', PROJECT_NAME).exists()
              }
            }
          }
          steps {
            script {
              openshift.withCluster() {
                openshift.newBuild("--name=${PROJECT_NAME}", "--image-stream=redhat-openjdk18-openshift:1.1", "--binary")
              }
            }
          }
        }
        stage('Create Test Deployment') {
          when {
            expression {
              openshift.withCluster() {
                def ciProject = openshift.project()
                def testProject = ciProject.replaceFirst(/^labs-ci-cd/, /labs-test/)
                openshift.withProject(testProject) {
                  return !openshift.selector('dc', PROJECT_NAME).exists()
                }
              }
            }
          }
          steps {
            script {
              openshift.withCluster() {
                def ciProject = openshift.project()
                def testProject = ciProject.replaceFirst(/^labs-ci-cd/, /labs-test/)
                openshift.withProject(testProject) {
                  openshift.newApp("${PROJECT_NAME}:latest", "--name=${PROJECT_NAME}").narrow('svc').expose()
                }
              }
            }
          }
        }
        stage('Create Demo Deployment') {
          when {
            expression {
              openshift.withCluster() {
                def ciProject = openshift.project()
                def demoProject = ciProject.replaceFirst(/^labs-ci-cd/, /labs-demo/)
                openshift.withProject(demoProject) {
                  return !openshift.selector('dc', PROJECT_NAME).exists()
                }
              }
            }
          }
          steps {
            script {
              openshift.withCluster() {
                def ciProject = openshift.project()
                def demoProject = ciProject.replaceFirst(/^labs-ci-cd/, /labs-demo/)
                openshift.withProject(demoProject) {
                  openshift.newApp("${PROJECT_NAME}:latest", "--name=${PROJECT_NAME}").narrow('svc').expose()
                }
              }
            }
          }
        }
      }
    }
    stage('Build Image') {
      steps {
        script {
          openshift.withCluster() {
            openshift.selector('bc', PROJECT_NAME).startBuild("--from-file=target/${PROJECT_NAME}.jar", '--wait')
          }
        }
      }
    }
    stage('Promote to TEST') {
      steps {
        script {
          openshift.withCluster() {
            def ciProject = openshift.project()
            def testProject = ciProject.replaceFirst(/^labs-ci-cd/, /labs-test/)
            openshift.tag("${PROJECT_NAME}:latest", "${testProject}/${PROJECT_NAME}:latest")
          }
        }
      }
    }
    stage('Web Security Analysis') {
      steps {
        agent {
          label "jenkins-slave-zap"
        }
        script {
          def testProject = ciProject.replaceFirst(/^labs-ci-cd/, /labs-test/)
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
          openshift.withCluster() {
            def ciProject = openshift.project()
            def demoProject = ciProject.replaceFirst(/^labs-ci-cd/, /labs-demo/)
            openshift.tag("${PROJECT_NAME}:latest", "${demoProject}/${PROJECT_NAME}:latest")
          }
        }
      }
    }
  }
}