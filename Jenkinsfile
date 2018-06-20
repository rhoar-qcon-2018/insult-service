pipeline {
  agent none
  environment {
    PROJECT_NAME = 'insult-service'
  }
  stages {
    stage('Quality And Security') {
      parallel {
        stage('OWASP Dependency Check') {
          agent {
            label 'jenkins-slave-mvn'
          }
          steps {
            sh 'mvn -T 2 dependency-check:check'
          }
        }
        stage('Compile & Test') {
          agent {
            label 'jenkins-slave-mvn'
          }
          steps {
            sh 'mvn -T 2 package vertx:package'
          }
        }
        stage('Ensure SonarQube Webhook is configured') {
          agent {
            label 'jenkins-slave-mvn'
          }
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
      }
    }
    stage('Wait for SonarQube Quality Gate') {
      agent {
        label 'jenkins-slave-mvn'
      }
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
    stage('OpenShift Configuration') {
      parallel {
        stage('Publish Artifacts') {
          agent {
            label 'jenkins-slave-mvn'
          }
          steps {
            sh 'mvn package vertx:package deploy:deploy -DskipTests -DaltDeploymentRepository=nexus::default::http://nexus:8081/repository/maven-snapshots/'
          }
        }
        stage('Create Binary BuildConfig') {
          agent any
          when {
            not {
              expression {
                openshift.withCluster() {
                  return openshift.selector('bc', PROJECT_NAME).exists()
                }
              }
            }
          }
          steps {
            script {
              openshift.withCluster() {
                openshift.newBuild("--name=${PROJECT_NAME}", "--image-stream=redhat-openjdk18-openshift:1.1", "--binary", "--to='${PROJECT_NAME}:latest'")
              }
            }
          }
        }
        stage('Create Test Deployment') {
          agent any
          when {
            not {
              expression {
                openshift.withCluster() {
                  def ciProject = openshift.project()
                  def testProject = ciProject.replaceFirst(/^labs-ci-cd/, 'labs-test')
                  openshift.withProject(testProject) {
                    return openshift.selector('dc', PROJECT_NAME).exists()
                  }
                }
              }
            }
          }
          steps {
            script {
              openshift.withCluster() {
                def ciProject = openshift.project()
                def testProject = ciProject.replaceFirst(/^labs-ci-cd/, 'labs-test')
                sh "oc new-app --namespace=${testProject} --name=${PROJECT_NAME} --allow-missing-imagestream-tags=true --image-stream=${PROJECT_NAME}"
              }
            }
          }
        }
        stage('Create Demo Deployment') {
          agent any
          when {
            not {
              expression {
                openshift.withCluster() {
                  def ciProject = openshift.project()
                  def devProject = ciProject.replaceFirst(/^labs-ci-cd/, 'labs-dev')
                  openshift.withProject(devProject) {
                    return openshift.selector('dc', PROJECT_NAME).exists()
                  }
                }
              }
            }
          }
          steps {
            script {
              openshift.withCluster() {
                def ciProject = openshift.project()
                def devProject = ciProject.replaceFirst(/^labs-ci-cd/, 'labs-dev')
                sh "oc new-app --namespace=${devProject} --name=${PROJECT_NAME} --allow-missing-imagestream-tags=true --image-stream=${PROJECT_NAME}"
              }
            }
          }
        }
      }
    }
    stage('Build Image') {
      agent {
        label 'jenkins-slave-mvn'
      }
      steps {
        script {
          openshift.withCluster() {
            openshift.selector('bc', PROJECT_NAME).startBuild("--from-file=target/${PROJECT_NAME}.jar", '--wait')
          }
        }
      }
    }
    stage('Promote to TEST') {
      agent any
      steps {
        script {
          openshift.withCluster() {
            def ciProject = openshift.project()
            def testProject = ciProject.replaceFirst(/^labs-ci-cd/, 'labs-test')
            openshift.tag("${PROJECT_NAME}:latest", "${testProject}/${PROJECT_NAME}:latest")
          }
        }
      }
    }
    stage('Web Security Analysis') {
      agent {
        label "jenkins-slave-zap"
      }
      steps {
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
      agent any
      input {
        message "Promote service to DEMO environment?"
        ok "PROMOTE"
      }
      steps {
        script {
          openshift.withCluster() {
            def ciProject = openshift.project()
            def devProject = ciProject.replaceFirst(/^labs-ci-cd/, 'labs-dev')
            openshift.tag("${PROJECT_NAME}:latest", "${devProject}/${PROJECT_NAME}:latest")
          }
        }
      }
    }
  }
}